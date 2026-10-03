//
// SubScopeProcessorTest.kt
// :codegen-processor
//
// Self-test: this module applies its OWN processor (see build.gradle.kts's `kspTest`) to the
// @Reducer-annotated objects below, then this test exercises the REAL generated config end to
// end through a real ScopeStore — not just that KSP ran without error. Three real levels (Root
// -> List -> Article) specifically to prove a grandparent can react to a grandchild's event
// through nothing but @OnChildAction — see each annotation's own doc for why no separate
// mechanism is needed for that.
//
package statoscope.codegen.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.codegen.OnChildAction
import statoscope.codegen.ParentState
import statoscope.codegen.Reducer
import statoscope.codegen.ReducerInjected
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.createScope

@Reducer
object TestArticleReducer {
    data class State(val id: String = "", val title: String = "")

    enum class When {
        UserTappedFavoriteButton
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State = state
}

@Reducer
object TestListReducer {
    data class State(
        val articles: List<TestArticleReducer.State> = emptyList(),
        @SubState val selectedArticle: TestArticleReducer.State? = null
    )

    sealed interface When {
        data class UserSelectedArticle(val id: String) : When
        // Carries the leaf's own event unchanged — pure relay, no decision made at this level.
        data class ArticleRelay(val articleWhen: TestArticleReducer.When, val articleId: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserSelectedArticle -> state.copy(selectedArticle = state.articles.first { it.id == action.id })
            is When.ArticleRelay -> state
        }

    @OnChildAction
    fun relayArticleEvents(
        childWhen: TestArticleReducer.When,
        childState: TestArticleReducer.State,
        dependencies: Dependencies
    ): When = When.ArticleRelay(childWhen, childState.id)
}

@Reducer
object TestFeedReducer {
    data class State(
        val favorites: List<String> = emptyList(),
        @SubState val list: TestListReducer.State? = null
    )

    sealed interface When {
        data object SystemStarted : When
        data class ToggledFavorite(val articleId: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.SystemStarted -> state.copy(
                list = TestListReducer.State(articles = listOf(TestArticleReducer.State(id = "1", title = "Article One")))
            )
            is When.ToggledFavorite -> state.copy(favorites = state.favorites + action.articleId)
        }

    // Matched to the `list` @SubState property by type — but reaches straight past it into
    // what the article, two levels down, actually did.
    @OnChildAction
    fun onListAction(
        childWhen: TestListReducer.When,
        childState: TestListReducer.State,
        dependencies: Dependencies
    ): When? =
        if (childWhen is TestListReducer.When.ArticleRelay &&
            childWhen.articleWhen == TestArticleReducer.When.UserTappedFavoriteButton
        ) {
            When.ToggledFavorite(childWhen.articleId)
        } else {
            null
        }
}

val TestLoggerKey = InjectionKey("default-logger", "TestLogger")

@Reducer
object TestInjectedReducer {
    data class State(
        @ReducerInjected("TestLoggerKey") val logger: String = TestLoggerKey.defaultValue,
        val count: Int = 0
    )

    sealed interface When {
        data object Increment : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            // Deliberately never touches `logger` itself — proving the generated wrapper
            // re-stamps it after EVERY update(), regardless of which event fired.
            is When.Increment -> state.copy(count = state.count + 1)
        }
}

// Verifies a scenario the original @ReducerInjected tests (above) never exercised: the key
// injected only at the ROOT, a @ReducerInjected property two levels... one level down, on a real
// @SubState child, through the generated update() wrapper's own dependencies.resolve(key) call
// — which already ancestor-walks (see ScopeStoreTest's own InjectionKey tests) — rather than on
// an isolated, uncomposed reducer. Flagged by this session's test-level parity audit as "likely
// already works by construction, but unverified."
@Reducer
object TestInjectedChildReducer {
    data class State(
        @ReducerInjected("TestLoggerKey") val logger: String = TestLoggerKey.defaultValue,
        val tick: Int = 0
    )

    sealed interface When {
        data object Tick : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            // Deliberately never touches `logger` — same point as TestInjectedReducer's own Increment.
            is When.Tick -> state.copy(tick = state.tick + 1)
        }
}

@Reducer
object TestInjectedParentReducer {
    data class State(
        @SubState val child: TestInjectedChildReducer.State? = null
    )

    sealed interface When {
        data object CreateChild : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.CreateChild -> state.copy(child = TestInjectedChildReducer.State())
        }
}

// @ParentState — the real-world motivation: a leaf reading data a sibling level up (here,
// `favorites`) actually owns, without the UI having to plumb it down by hand as an extra
// parameter (the exact seam `statoscope-kotlin-examples`' NewsFeedArticleScreen/favoritesCount
// has today). Matched by TYPE — no string-named workaround needed, unlike @ReducerInjected.
@Reducer
object TestParentStateChildReducer {
    data class State(
        @ParentState val parent: TestParentStateParentReducer.State = TestParentStateParentReducer.State()
    )

    sealed interface When {
        // Deliberately a no-op in THIS reducer's own update() — see ParentState's own doc on
        // timing: the generated wrapper re-stamps `parent` AFTER update() returns, so update()
        // itself always sees the PREVIOUS cycle's value, never the one about to be resolved for
        // the current call. Reading `state.parent` is for everything OUTSIDE update() — UI
        // rendering, a later update() call — never within the same call that triggers the stamp.
        data object Observe : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.Observe -> state
        }
}

@Reducer
object TestParentStateParentReducer {
    data class State(
        val favorites: List<String> = emptyList(),
        @SubState val child: TestParentStateChildReducer.State? = null
    )

    sealed interface When {
        data object CreateChild : When
        data class AddFavorite(val id: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.CreateChild -> state.copy(child = TestParentStateChildReducer.State())
            is When.AddFavorite -> state.copy(favorites = state.favorites + action.id)
        }
}

class SubScopeProcessorTest {

    @Test
    fun testGeneratedConfigWiresThreeRealLevelsWithZeroHandWrittenSubScopes() {
        val store = createScope(TestFeedReducer.config)
        store.send(TestFeedReducer.When.SystemStarted)

        val listStore = store.childStore(testFeedReducerListSubScope)!!
        listStore.send(TestListReducer.When.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(testListReducerSelectedArticleSubScope)!!
        articleStore.send(TestArticleReducer.When.UserTappedFavoriteButton)

        assertEquals(
            listOf("1"), store.state.favorites,
            "a leaf-level tap should have reached the root's favorites two levels up, through generated @OnChildAction wiring alone"
        )
    }

    @Test
    fun testNoMatchingArticleEventLeavesFavoritesAlone() {
        val store = createScope(TestFeedReducer.config)
        store.send(TestFeedReducer.When.SystemStarted)

        val listStore = store.childStore(testFeedReducerListSubScope)!!
        listStore.send(TestListReducer.When.UserSelectedArticle("1"))

        // No favorite tap sent — favorites must stay empty, proving the previous test's green
        // result isn't a false positive from some other path populating it.
        assertEquals(emptyList(), store.state.favorites)
    }

    // MARK: - @ReducerInjected

    @Test
    fun testReducerInjectedStartsWithTheKeysOwnDefaultBeforeAnyUpdate() {
        val store = createScope(TestInjectedReducer.config)
        assertEquals("default-logger", store.state.logger)
    }

    @Test
    fun testReducerInjectedIsReStampedFromDependenciesAfterEveryUpdate() {
        val store = createScope(TestInjectedReducer.config)
        store.inject(TestLoggerKey, "injected-logger")

        // Increment never touches `logger` in TestInjectedReducer's own update() — the
        // generated wrapper is what re-stamps it, for every event, not just ones that mention it.
        store.send(TestInjectedReducer.When.Increment)

        assertEquals("injected-logger", store.state.logger)
        assertEquals(1, store.state.count)
    }

    @Test
    fun testReducerInjectedResolvesThroughAComposedTreeWithTheKeyInjectedOnlyAtTheRoot() {
        val root = createScope(TestInjectedParentReducer.config)
        root.inject(TestLoggerKey, "injected-at-root")

        root.send(TestInjectedParentReducer.When.CreateChild)
        val child = root.childStore(testInjectedParentReducerChildSubScope)!!

        // Before any event, the child holds its OWN default — not yet resolved from the tree
        // (see ReducerInjected's own doc: re-stamped after update(), not continuously live).
        assertEquals("default-logger", child.state.logger)

        // Tick never touches `logger` itself — the generated wrapper is what re-stamps it, and
        // dependencies.resolve(key) ancestor-walks all the way up to the root's own inject(),
        // with zero plumbing at any level in between.
        child.send(TestInjectedChildReducer.When.Tick)

        assertEquals(
            "injected-at-root", child.state.logger,
            "a @ReducerInjected property on a composed child should resolve an ancestor's " +
                "inject()/provide, exactly like a plain dependencies.resolve(key) call would"
        )
    }

    // MARK: - @ParentState

    @Test
    fun testParentStateResolvesTheParentsLiveStateAfterEveryChildUpdate() {
        val root = createScope(TestParentStateParentReducer.config)
        root.send(TestParentStateParentReducer.When.AddFavorite("a"))
        root.send(TestParentStateParentReducer.When.AddFavorite("b"))
        root.send(TestParentStateParentReducer.When.CreateChild)

        val child = root.childStore(testParentStateParentReducerChildSubScope)!!

        // Before any event, the child holds its own placeholder default — not yet resolved
        // (same "fresh as of the last update()" rule as @ReducerInjected).
        assertEquals(0, child.state.parent.favorites.size)

        // Observe is a no-op in the child's own update() — the generated wrapper is what
        // re-stamps `parent`, visible on this state only once this call returns.
        child.send(TestParentStateChildReducer.When.Observe)

        assertEquals(
            listOf("a", "b"), child.state.parent.favorites,
            "a @ParentState property should resolve the parent's CURRENT state via the " +
                "generated superstateKey wiring, with no hand-written SubScope needed"
        )
    }
}
