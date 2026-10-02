# Advanced / 02: Code Generation

> Every code block on this page is copied verbatim from `docs/_generated/CodeGen-*.kt`, generated
> by `./gradlew extractDocSnippets` from the real, compiling, tested source at
> `src/test/kotlin/statoscope/examples/advanced/codegen/CodeGenerationExample.kt`. See Tutorial
> 01's own header note for what that guarantees and what it doesn't (manual re-sync after a
> source change).
>
> This article rebuilds Tutorial 05's own news-feed example — the same `Article`/`Favorite` types,
> the same three levels, the same "favoriting an article reaches the list two levels up" proof —
> using `@Reducer`/`@SubState`/`@OnChildAction` from `:codegen` (processed by
> `:codegen-processor`) instead of hand-written `ScopeConfig(...)`/`SubScope(...)`. The point
> isn't new behavior; it's the same behavior with a fraction of the plumbing, so you can compare
> the two side by side rather than take "it's shorter" on faith. See `CODEGEN.md` at the repo root
> for the full design writeup.
>
> One real difference, not an oversight: Tutorial 05's version reads a live `SuperstateKey` inside
> the article to decide a favorites limit before reporting a tap upward. `superstateKey` isn't
> auto-wired by `@SubState` — deliberately, see `@Reducer`'s own doc comment for why — so there's
> no annotation-only way to attach one to a generated `SubScope` yet. This version moves the limit
> check to the list's own `@OnChildAction`, which already has the data it needs without reading
> anything live at all.

## The leaf

Compare this against `NewsFeedArticleState`/`NewsFeedArticleWhen`/`newsFeedArticleUpdate` in
Tutorial 05 — same shape, no `ScopeConfig(...)` built by hand, and the whole thing is a plain
`object` instead of three separate top-level declarations plus a free function.

```kotlin
import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
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
```

## The middle level

`@SubState val selectedArticle` replaces Tutorial 05's entire hand-written `get`/`set`/`config`
lens (`newsFeedArticleSubScope`, eleven lines) — `:codegen-processor` resolves
`NewsFeedArticleReducer` by matching this property's type against every other `@Reducer` object's
`State`, and generates the lens itself. `@OnChildAction` replaces the `onChildAction` closure that
used to live embedded inside that same `SubScope(...)` call — now a real, independently-named,
independently-testable function, matched to `selectedArticle` by type, not by name.

```kotlin
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
```

## The root

No `@OnChildAction` here at all — composition doesn't require one; only declare it when there's
an actual reaction to make, exactly like Tutorial 05's root having no `onChildAction` either.

```kotlin
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
```

That's the entire tree — three `object`s, zero `ScopeConfig(...)`/`SubScope(...)` calls written
by hand anywhere. `NewsFeedReducer.config: ScopeConfig<NewsFeedReducer.State, NewsFeedReducer.When>`
is generated; so is a stable top-level `newsFeedReducerListSubScope` and
`newsFeedListReducerSelectedArticleSubScope` (named `<reducer><property>SubScope`), should you
ever need to reach a child directly the way the test below does.

## Proving it's the same behavior

The exact same proof as Tutorial 05's `ScopesIntegrationTest` — favoriting an article reaches the
list two levels up with no relay code written in any reducer — now against generated `.config`
properties instead of hand-written ones, plus a second test for the limit-check that moved.

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.core.createScope
import statoscope.examples.tutorial05.Article
import statoscope.examples.tutorial05.Favorite
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
```

## Also available, not shown above

- **`@SubState`'s own validation**: a property it's applied to must be a class type owned by some
  *other* `@Reducer` object as its `State` — a real KSP compile error, pointing at the property,
  for a wrong type, a `List<T>`, or a type alias resolving to one.
- **`@OnChildAction`'s own validation**: a real compile error, pointing at the function, for a
  wrong shape (parameter count/types/order, return type), a `childState` type with no matching
  `@SubState` property anywhere on the reducer, or two `@OnChildAction` functions both claiming
  the same child type.
- **Multi-level reach today is still one hop plus relay**, same as the hand-written path — an
  intermediate reducer's own `@OnChildAction` has to relay a child's event unchanged, wrapped in
  its own `When`, for a grandparent to react to it. See `PARITY_AUDIT.md`'s Issue 7 for the
  broadcast-reaching alternative (`ScopeConfig.onDescendantAction`), which has no codegen
  annotation yet — it's wired by hand on whichever reducer needs it.
