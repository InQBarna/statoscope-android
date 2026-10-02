//
// CodeGenerationExample.kt
// statoscope-kotlin
//
// Examples from Advanced/02: Code Generation — the same news-feed domain as Tutorial 05
// (Tests/kotlin/statoscope/examples/tutorial05/ScopesExample.kt), rebuilt with @Reducer/@SubState/
// @OnChildAction instead of hand-written ScopeConfig/SubScope, to show the ergonomic difference
// directly rather than describe it.
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
 * The leaf, as a `@Reducer` object. Compare against `NewsFeedArticleState`/`NewsFeedArticleWhen`/
 * `newsFeedArticleUpdate` in Tutorial 05 — same shape, no `ScopeConfig(...)` to build by hand.
 *
 * One real difference from Tutorial 05's version, not an oversight: that version's
 * `UserTappedFavoriteButton` branch reads `NewsFeedListSuperstateKey` live to decide
 * `favoritesLimitReached` *before* reporting the tap upward. `superstateKey` isn't auto-wired by
 * `@SubState` — deliberately, see `@Reducer`'s own doc — so there's no annotation-only way to
 * attach one to a generated `SubScope` today. This version moves the limit check to
 * `NewsFeedListReducer.onArticleFavorited` below instead, which already has `parentState.favorites`
 * in scope without needing to read anything live at all.
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
 * The middle level. `@SubState val selectedArticle` replaces `newsFeedArticleSubScope`'s entire
 * hand-written `get`/`set`/`config` lens — `:codegen-processor` resolves `NewsFeedArticleReducer`
 * by matching this property's type, and generates the lens itself.
 *
 * `MAX_FAVORITES` is enforced here now (see `NewsFeedArticleReducer`'s doc above for why) — this
 * level already owns `favorites`, so checking its own size needs no live ancestor read at all.
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
     * Compare against `newsFeedArticleSubScope`'s `onChildAction` lambda in Tutorial 05: same
     * decision, now a real, independently-named, independently-testable function instead of a
     * closure embedded in a `SubScope(...)` call.
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
 * there's an actual reaction to make, exactly like Tutorial 05's root having no `onChildAction`.
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
 * The exact same proof as Tutorial 05's `ScopesIntegrationTest`, against generated `.config`
 * properties instead of hand-written `ScopeConfig(...)`/`SubScope(...)` — favoriting an article
 * reaches the list two levels up with no relay code written in any reducer.
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
