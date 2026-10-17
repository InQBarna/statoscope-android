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
import kotlin.test.assertTrue
import statoscope.codegen.OnChildAction
import statoscope.codegen.OnDescendantAction
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
        // This reducer's own update() never touches `parent` at all — the generated
        // liveAncestorKey/deriveFromLiveAncestor wiring (ScopeConfig.kt's own doc) is what keeps
        // it current, pushed synchronously by ScopeStoreImpl the moment the parent commits a new
        // state, including right at this child's own activation — never waiting for this
        // reducer's own next update() the way the old re-stamp-after-update() approach did.
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

// @OnDescendantAction — three real levels (Root -> Middle -> Grandchild), proving the codegen path
// can reach a GRANDCHILD directly from the ROOT with zero relay at the Middle level, mirroring
// ScopeStoreTest's hand-written broadcastRootConfig fixture one-for-one.
@Reducer
object TestBroadcastGrandchildReducer {
    data class State(val tapped: Boolean = false)

    sealed interface When {
        data object Tap : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.Tap -> state.copy(tapped = true)
        }
}

@Reducer
object TestBroadcastMiddleReducer {
    // Deliberately NO @OnChildAction here — proving the root sees the grandchild's event with no
    // relay from this level at all, not even an unconditional pass-through one.
    data class State(@SubState val grandchild: TestBroadcastGrandchildReducer.State? = null)

    sealed interface When {
        data object ActivateGrandchild : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.ActivateGrandchild -> state.copy(grandchild = TestBroadcastGrandchildReducer.State())
        }
}

@Reducer
object TestBroadcastRootReducer {
    data class State(
        @SubState val middle: TestBroadcastMiddleReducer.State? = null,
        val grandchildTapsSeenAtRoot: Int = 0
    )

    sealed interface When {
        data object ActivateMiddle : When
        data object RecordGrandchildTap : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.ActivateMiddle -> state.copy(middle = TestBroadcastMiddleReducer.State())
            is When.RecordGrandchildTap -> state.copy(grandchildTapsSeenAtRoot = state.grandchildTapsSeenAtRoot + 1)
        }

    @OnDescendantAction
    fun onAnyDescendantAction(descendantWhen: Any, descendantState: Any, parentState: State, dependencies: Dependencies): When? =
        if (descendantWhen is TestBroadcastGrandchildReducer.When.Tap) When.RecordGrandchildTap else null
}

// @SubState(provideSuperstate = true): a grandchild reads its GRANDPARENT's live state with dependencies.resolve(key), skipping the
// middle reducer, which declares nothing (Swift's `@SuperState` at any depth).
@Reducer
object TestSkipLeafReducer {
    data class State(val seenRoot: Int = -1)

    enum class When { ReadRoot }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.ReadRoot -> state.copy(seenRoot = dependencies.resolve(testSkipRootReducerSuperstateKey).counter)
        }
}

@Reducer
object TestSkipMiddleReducer {
    data class State(@SubState val leaf: TestSkipLeafReducer.State? = null)

    enum class When { CreateLeaf }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.CreateLeaf -> state.copy(leaf = TestSkipLeafReducer.State())
        }
}

@Reducer
object TestSkipRootReducer {
    data class State(
        val counter: Int = 0,
        @SubState(provideSuperstate = true) val middle: TestSkipMiddleReducer.State? = null
    )

    enum class When { CreateMiddle, Increment }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.CreateMiddle -> state.copy(middle = TestSkipMiddleReducer.State())
            When.Increment -> state.copy(counter = state.counter + 1)
        }
}

// Ports Swift's DefaultTriggerReducerTests (`MiddlewareReducerTests.swift`): the child declares
// `static var defaultTrigger: When? { .onAppear }` there, `val defaultTrigger: When?` here.
@Reducer
object TestDefaultTriggerChildReducer {
    data class State(val initializeCount: Int = 0, val otherEventCount: Int = 0)

    enum class When { OnAppear, SomeOtherEvent }

    val defaultTrigger: When? = When.OnAppear

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.OnAppear -> state.copy(initializeCount = state.initializeCount + 1)
            When.SomeOtherEvent -> state.copy(otherEventCount = state.otherEventCount + 1)
        }
}

// Same shape, deliberately no defaultTrigger.
@Reducer
object TestNoDefaultTriggerChildReducer {
    data class State(val initializeCount: Int = 0)

    enum class When { OnAppear }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.OnAppear -> state.copy(initializeCount = state.initializeCount + 1)
        }
}

@Reducer
object TestDefaultTriggerParentReducer {
    data class State(
        @SubState val child: TestDefaultTriggerChildReducer.State? = null,
        @SubState val plainChild: TestNoDefaultTriggerChildReducer.State? = null
    )

    enum class When { CreateChild, DestroyChild, CreatePlainChild }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            When.CreateChild -> state.copy(child = TestDefaultTriggerChildReducer.State())
            When.DestroyChild -> state.copy(child = null)
            When.CreatePlainChild -> state.copy(plainChild = TestNoDefaultTriggerChildReducer.State())
        }
}

class SubScopeProcessorTest {

    // MARK: - @SubState(provideSuperstate = true)

    @Test
    fun testAGrandchildReadsItsGrandparentsLiveStateSkippingTheMiddleLevel() {
        val root = createScope(TestSkipRootReducer.config)
        root.send(TestSkipRootReducer.When.Increment)
        root.send(TestSkipRootReducer.When.CreateMiddle)
        val middle = root.childStore(testSkipRootReducerMiddleSubScope)!!
        middle.send(TestSkipMiddleReducer.When.CreateLeaf)
        val leaf = middle.childStore(testSkipMiddleReducerLeafSubScope)!!

        leaf.send(TestSkipLeafReducer.When.ReadRoot)
        assertEquals(1, leaf.state.seenRoot, "the leaf sees the root's counter as it is now")

        // The root changes AFTER the leaf exists: the read is live, not a snapshot taken at activation
        root.send(TestSkipRootReducer.When.Increment)
        root.send(TestSkipRootReducer.When.Increment)
        leaf.send(TestSkipLeafReducer.When.ReadRoot)
        assertEquals(3, leaf.state.seenRoot, "and a later read sees the new value")
    }

    // MARK: - defaultTrigger

    @Test
    fun testDefaultTriggerIsSentWhenChildIsWired() {
        val root = createScope(TestDefaultTriggerParentReducer.config)
        root.send(TestDefaultTriggerParentReducer.When.CreateChild)

        val child = root.childStore(testDefaultTriggerParentReducerChildSubScope)!!
        assertEquals(1, child.state.initializeCount, "defaultTrigger should fire exactly once on wiring")
    }

    @Test
    fun testDefaultTriggerFiresOnlyOnce() {
        val root = createScope(TestDefaultTriggerParentReducer.config)
        root.send(TestDefaultTriggerParentReducer.When.CreateChild)
        val child = root.childStore(testDefaultTriggerParentReducerChildSubScope)!!

        child.send(TestDefaultTriggerChildReducer.When.SomeOtherEvent)
        child.send(TestDefaultTriggerChildReducer.When.SomeOtherEvent)

        assertEquals(1, child.state.initializeCount, "defaultTrigger fires only on wiring, not on subsequent events")
        assertEquals(2, child.state.otherEventCount)
    }

    @Test
    fun testDefaultTriggerFiresAgainWhenChildIsRecreated() {
        val root = createScope(TestDefaultTriggerParentReducer.config)
        root.send(TestDefaultTriggerParentReducer.When.CreateChild)
        root.send(TestDefaultTriggerParentReducer.When.DestroyChild)
        root.send(TestDefaultTriggerParentReducer.When.CreateChild)

        val child = root.childStore(testDefaultTriggerParentReducerChildSubScope)!!
        assertEquals(1, child.state.initializeCount, "a new child store gets its own defaultTrigger")
    }

    @Test
    fun testNoDefaultTriggerForReducerWithoutIt() {
        val root = createScope(TestDefaultTriggerParentReducer.config)
        root.send(TestDefaultTriggerParentReducer.When.CreatePlainChild)

        val child = root.childStore(testDefaultTriggerParentReducerPlainChildSubScope)!!
        assertEquals(0, child.state.initializeCount, "no defaultTrigger means no automatic event")
    }

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
    fun testParentStateIsAlreadyCorrectAtActivationWithNoEventNeeded() {
        val root = createScope(TestParentStateParentReducer.config)
        root.send(TestParentStateParentReducer.When.AddFavorite("a"))
        root.send(TestParentStateParentReducer.When.AddFavorite("b"))
        root.send(TestParentStateParentReducer.When.CreateChild)

        val child = root.childStore(testParentStateParentReducerChildSubScope)!!

        // No event sent to the child at all — the generated liveAncestorKey/deriveFromLiveAncestor
        // wiring pushes the parent's CURRENT state synchronously at activation, closing the
        // "before any event, still holds the placeholder default" gap the old
        // re-stamp-after-update() approach had.
        assertEquals(
            listOf("a", "b"), child.state.parent.favorites,
            "a @ParentState property should already reflect the parent's current state right at " +
                "activation, with no hand-written SubScope and no event needed"
        )
    }

    @Test
    fun testParentStatePushesANewFavoriteWithNoEventOnTheChildAtAll() {
        val root = createScope(TestParentStateParentReducer.config)
        root.send(TestParentStateParentReducer.When.CreateChild)
        val child = root.childStore(testParentStateParentReducerChildSubScope)!!
        assertEquals(emptyList(), child.state.parent.favorites)

        // A later change at the PARENT, with no event sent to the child at all — the cascade
        // (ScopeStoreImpl.notifyLiveDependents) pushes it straight through, proving @ParentState
        // is genuinely live, not just resolved once at activation.
        root.send(TestParentStateParentReducer.When.AddFavorite("c"))

        assertEquals(
            listOf("c"), child.state.parent.favorites,
            "a @ParentState property should reflect a later parent-level change with no event " +
                "sent to the child at all"
        )
    }

    // MARK: - @OnDescendantAction

    @Test
    fun testOnDescendantActionReachesAGrandchildWithNoRelayAtTheMiddleLevel() {
        val root = createScope(TestBroadcastRootReducer.config)
        root.send(TestBroadcastRootReducer.When.ActivateMiddle)
        val middle = root.childStore(testBroadcastRootReducerMiddleSubScope)!!
        middle.send(TestBroadcastMiddleReducer.When.ActivateGrandchild)
        val grandchild = middle.childStore(testBroadcastMiddleReducerGrandchildSubScope)!!

        grandchild.send(TestBroadcastGrandchildReducer.When.Tap)

        assertTrue(grandchild.state.tapped, "the grandchild's own update() still ran normally")
        assertEquals(
            1, root.state.grandchildTapsSeenAtRoot,
            "the generated @OnDescendantAction wiring should see the grandchild's event directly, " +
                "with no relay anywhere on the middle level's generated SubScope"
        )
    }

    @Test
    fun testOnDescendantActionDoesNotFireForTheRootsOwnSelfSentEvents() {
        val root = createScope(TestBroadcastRootReducer.config)

        // ActivateMiddle is the root's OWN event, sent to itself — not a descendant's.
        root.send(TestBroadcastRootReducer.When.ActivateMiddle)

        assertEquals(
            0, root.state.grandchildTapsSeenAtRoot,
            "a store's own self-sent event must never trigger its own generated @OnDescendantAction"
        )
    }
}
