# Advanced / 02: Code Generation

> Every code block on this page is copied verbatim from `docs/_generated/CodeGen-*.kt`, generated
> by `./gradlew extractDocSnippets` from the real, compiling, tested source at
> `src/test/kotlin/statoscope/examples/advanced/codegen/CodeGenerationExample.kt`. See Tutorial
> 01's own header note for what that guarantees and what it doesn't (manual re-sync after a
> source change).
>
> **Following Tutorial 05 (Scopes)**: this article builds the exact same news-feed domain — the
> same `Article`/`Favorite` types, the same three-level composition (Article → List → Root) — but
> with `@Reducer`/`@SubState`/`@OnChildAction` from `:codegen` instead of hand-written
> `ScopeConfig(...)`/`SubScope(...)`. Read Tutorial 05 first if you haven't; this one assumes its
> domain and composition concepts, and spends its own words on what's different about building
> the same tree this way. See `CODEGEN.md` at the repo root for the full design writeup.

## The leaf

`NewsFeedArticleReducer` handles two kinds of event completely differently: toggling expanded is
purely local — nothing outside this one article cares whether its body is shown. Tapping favorite
only *reports* the tap; whether it's actually allowed is a decision that belongs to whichever
level owns the data needed to make it, not to the article itself.

```kotlin
import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
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
```

## The middle level

`NewsFeedListReducer` is both a child (of the root, below) and a parent (of the article, above)
at once. `@SubState val selectedArticle` declares that parent relationship directly as a
property: `:codegen-processor` finds `NewsFeedArticleReducer` as the matching child type and
generates the create/mirror/tear-down wiring for it — the thing a hand-written `SubScope(...)`'s
`get`/`set`/`config` lens would otherwise spell out by hand.

Favoriting is enforced here, not in the article, because this is the level that actually owns
`favorites` — deciding whether there's room for one more needs nothing beyond this reducer's own
state. `@OnChildAction` is how this level reacts to something that happened in the article,
matched to `selectedArticle` by *type*, not by name, so a wrong shape is a compile error rather
than a silent no-op.

```kotlin
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
```

### Reading an ancestor's live state

Some decisions genuinely need to know what an ancestor currently holds — not a value copied once
at creation, but live, right now. `SuperstateKey` plus `dependencies.resolve(key)` (Tutorial 05)
is how a reducer reads that: a descendant declares a key, an ancestor's `SubScope` wires itself up
to answer it, and anything below resolves it fresh on every call. It exists so a leaf doesn't
have to duplicate data that's naturally owned several levels up.

This example never needs one: the favorites limit is a question the list can already answer using
data it owns outright (`favorites.size`), so the article never has to ask an ancestor anything.
That's the right design in general, not just a convenience here — reach for a live ancestor read
only when the data genuinely lives elsewhere, not reflexively.

Worth knowing either way: `@SubState` has no annotation-only way to attach a `superstateKey` to
the child relationship it generates. A composition that does need a descendant to read an
ancestor live still builds that one relationship with the hand-written `SubScope(...)` form.

## The root

No `@OnChildAction` here — composition doesn't require one; only declare it when there's an
actual reaction to make.

```kotlin
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
```

That's the entire tree — three `object`s, zero `ScopeConfig(...)`/`SubScope(...)` calls written
by hand anywhere. `NewsFeedReducer.config: ScopeConfig<NewsFeedReducer.State, NewsFeedReducer.When>`
is generated; so is a stable top-level `newsFeedReducerListSubScope` and
`newsFeedListReducerSelectedArticleSubScope` (named `<reducer><property>SubScope`), should you
ever need to reach a child directly the way the test below does.

## Proving it works

Favoriting an article reaches the list two levels up with no relay code written in any reducer —
proven here against the real, generated `.config`, not a hand-built one.

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.core.createScope
import statoscope.examples.tutorial05.Article
import statoscope.examples.tutorial05.Favorite
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
```

## Also available

- **`@SubState`'s own validation**: the property it's applied to must be a class type owned by
  some *other* `@Reducer` object as its `State` — a real KSP compile error, pointing at the
  property, for a wrong type, a `List<T>`, or a type alias resolving to one.
- **`@OnChildAction`'s own validation**: a real compile error, pointing at the function, for a
  wrong shape (parameter count/types/order, return type), a `childState` type with no matching
  `@SubState` property anywhere on the reducer, or two `@OnChildAction` functions both claiming
  the same child type.
- **Multi-level reach today is still one hop plus relay**, same as the hand-written path — an
  intermediate reducer's own `@OnChildAction` has to relay a child's event unchanged, wrapped in
  its own `When`, for a grandparent to react to it directly. `ScopeConfig.onDescendantAction` is
  the broadcast-reaching alternative (the parity review's issue 7); it has no codegen annotation
  yet, so it's wired by hand on whichever reducer needs it.
