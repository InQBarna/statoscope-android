//
// CodeGenerationExample.kt
// statoscope-kotlin
//
// Examples from Advanced/02: Code Generation. Following Tutorial 05 (Scopes)'s own news-feed
// domain — same Article/Favorite types, same three-level composition
// (Tests/kotlin/statoscope/examples/tutorial05/ScopesExample.kt) — built here with
// @Reducer/@SubState/@OnChildAction instead of hand-written ScopeConfig/SubScope.
//
package statoscope.examples.advanced.codegen

// @extract:begin CodeGen-Article-01
import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
// @extract:end CodeGen-Article-01

// @extract:begin CodeGen-IntegrationTest-01
import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.core.createScope
import statoscope.examples.tutorial05.Article
import statoscope.examples.tutorial05.Favorite
// @extract:end CodeGen-IntegrationTest-01

// @extract:begin CodeGen-Article-01
/**
 * The leaf, as a `@Reducer` object. Two kinds of event live here, handled completely
 * differently: toggling expanded is purely local — nothing outside this one article cares
 * whether its body is shown. Tapping favorite only *reports* the tap; whether it's actually
 * allowed is a decision that belongs to whichever level owns the data needed to make it — see
 * `NewsFeedListReducer` below.
 */
@Reducer
object NewsFeedArticleReducer {
    data class State(
        val id: String = "",
        val title: String = "",
        val content: String = "",
        val isExpanded: Boolean = false
    )

    sealed interface When {
        data object UserToggledExpanded : When
        data object UserTappedFavoriteButton : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserToggledExpanded -> state.copy(isExpanded = !state.isExpanded)
            // Purely a report upward — see NewsFeedListReducer.onArticleFavorited for the decision.
            is When.UserTappedFavoriteButton -> state
        }
}
// @extract:end CodeGen-Article-01

// @extract:begin CodeGen-List-01
/**
 * The middle level — both a child (of the root, below) and a parent (of the article, above) at
 * once. `@SubState val selectedArticle` declares that parent relationship directly as a
 * property: `:codegen-processor` finds `NewsFeedArticleReducer` as the matching child type and
 * generates the create/mirror/tear-down wiring for it.
 *
 * Favoriting is enforced here, not in the article, because this is the level that actually owns
 * `favorites` — deciding whether there's room for one more needs nothing beyond this reducer's
 * own state. See the note on `SuperstateKey` below `onArticleFavorited` for the general case this
 * example doesn't need.
 */
@Reducer
object NewsFeedListReducer {
    const val MAX_FAVORITES = 3

    data class State(
        val articles: List<Article> = emptyList(),
        val favorites: List<Favorite> = emptyList(),
        @SubState val selectedArticle: NewsFeedArticleReducer.State? = null
    )

    sealed interface When {
        data class UserSelectedArticle(val id: String) : When
        data object UserDismissedArticle : When
        data class ToggledFavorite(val id: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserSelectedArticle -> {
                val article = state.articles.first { it.id == action.id }
                state.copy(
                    selectedArticle = NewsFeedArticleReducer.State(id = article.id, title = article.title, content = article.content)
                )
            }

            is When.UserDismissedArticle -> state.copy(selectedArticle = null)

            is When.ToggledFavorite -> {
                val alreadyFavorited = state.favorites.any { it.id == action.id }
                val newFavorites = when {
                    alreadyFavorited -> state.favorites.filterNot { it.id == action.id }
                    state.favorites.size >= MAX_FAVORITES -> state.favorites
                    else -> state.favorites + Favorite(id = action.id, order = state.favorites.size)
                }
                state.copy(favorites = newFavorites)
            }
        }

    /**
     * Matched to `selectedArticle` by type, not by name — this function could be called anything.
     *
     * A reducer that needs to read an ancestor's state *live*, rather than just react to a
     * child's events, reaches for `SuperstateKey` + `dependencies.resolve(key)` instead (see
     * Tutorial 05): a descendant declares a key, an ancestor's `SubScope` wires itself up to
     * answer it, and anything below resolves it fresh on every call. `@SubState` doesn't yet
     * generate that wiring on its own — there's no annotation-only way to attach a
     * `superstateKey` to a generated child relationship today, so a composition that genuinely
     * needs one still uses the hand-written `SubScope(...)` form for that relationship.
     */
    @OnChildAction
    fun onArticleFavorited(
        childWhen: NewsFeedArticleReducer.When,
        childState: NewsFeedArticleReducer.State,
        dependencies: Dependencies
    ): When? =
        if (childWhen is NewsFeedArticleReducer.When.UserTappedFavoriteButton) {
            When.ToggledFavorite(childState.id)
        } else {
            null
        }
}
// @extract:end CodeGen-List-01

// @extract:begin CodeGen-Root-01
/**
 * The root. No `@OnChildAction` at all — composition doesn't require one; only declare it when
 * there's an actual reaction to make.
 */
@Reducer
object NewsFeedReducer {
    data class State(
        val featureEnabled: Boolean = false,
        @SubState val list: NewsFeedListReducer.State? = null
    )

    sealed interface When {
        data object UserEnabledFeature : When
        data object UserDisabledFeature : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserEnabledFeature -> state.copy(
                featureEnabled = true,
                list = NewsFeedListReducer.State(
                    articles = listOf(
                        Article(id = "1", title = "Kotlin Coroutines 101", content = "..."),
                        Article(id = "2", title = "Composing Statoscope Scopes", content = "...")
                    )
                )
            )

            is When.UserDisabledFeature -> state.copy(featureEnabled = false, list = null)
        }
}
// @extract:end CodeGen-Root-01

// @extract:begin CodeGen-IntegrationTest-01
/**
 * Favoriting an article reaches the list two levels up with no relay code written in any
 * reducer — proven here against the real, generated `.config`, not a hand-built one.
 */
class CodeGenerationIntegrationTest {

    @Test
    fun testFavoritingAnArticleReachesTheListWithZeroHandWrittenSubScopes() {
        val store = createScope(NewsFeedReducer.config)
        store.send(NewsFeedReducer.When.UserEnabledFeature)

        val listStore = store.childStore(newsFeedReducerListSubScope)!!
        listStore.send(NewsFeedListReducer.When.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(newsFeedListReducerSelectedArticleSubScope)!!
        articleStore.send(NewsFeedArticleReducer.When.UserTappedFavoriteButton)

        assertEquals(listOf(Favorite("1", 0)), store.state.list?.favorites)
    }

    @Test
    fun testFavoritingStopsAtTheLimit() {
        val store = createScope(NewsFeedReducer.config)
        store.send(NewsFeedReducer.When.UserEnabledFeature)
        val listStore = store.childStore(newsFeedReducerListSubScope)!!

        listStore.send(NewsFeedListReducer.When.ToggledFavorite("a"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("b"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("c"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("d"))

        assertEquals(3, listStore.state.favorites.size, "a fourth favorite must be silently dropped at the limit")
    }
}
// @extract:end CodeGen-IntegrationTest-01
