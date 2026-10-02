//
// ScopeStoreTest.kt
// statoscope-kotlin
//
// Direct unit tests of the library's own core classes — not tutorial material, not extracted
// into any doc. Mirrors Swift Statoscope's top-level Tests/StatoscopeTests/*.swift, kept
// separate from Tests/StatoscopeTests/Examples/ there and from statoscope.examples here, so a
// tutorial rewrite can never accidentally break the library's own correctness net or vice versa.
//
package statoscope.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class TestState(val count: Int = 0, val marker: String = "initial")

private sealed interface TestWhen {
    data object Increment : TestWhen
    data class MarkerSet(val value: String) : TestWhen
}

private val testConfig = ScopeConfig<TestState, TestWhen>(
    initial = TestState(),
    update = { state, action, _, _ ->
        when (action) {
            is TestWhen.Increment -> state.copy(count = state.count + 1)
            is TestWhen.MarkerSet -> state.copy(marker = action.value)
        }
    }
)

private class FixedResultEffect(private val result: TestWhen) : Effect<TestWhen> {
    override suspend fun run(): TestWhen = result
}

// Two differently-typed raw effects, both real .map()-ped — for proving completeEffect() finds
// the one matching the REQUESTED type among several simultaneously pending, and really replays
// its own map (not just accepts whatever value it's handed).
private class RawIntFetch(private val value: Int) : Effect<Int> {
    override suspend fun run(): Int = value
}

private class RawStringFetch(private val value: String) : Effect<String> {
    override suspend fun run(): String = value
}

private sealed interface MultiEffectWhen {
    data object StartBoth : MultiEffectWhen
    data class IntLoaded(val value: Int) : MultiEffectWhen
    data class StringLoaded(val value: String) : MultiEffectWhen
}

private val multiEffectConfig = ScopeConfig<TestState, MultiEffectWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is MultiEffectWhen.StartBoth -> {
                // Enqueued oldest-first: RawIntFetch, then RawStringFetch — deliberately, so a
                // completeEffect() that ignored the requested type and just grabbed the oldest
                // would find the wrong one here.
                effects.enqueue(RawIntFetch(0).map(MultiEffectWhen::IntLoaded))
                effects.enqueue(RawStringFetch("").map(MultiEffectWhen::StringLoaded))
                state
            }
            is MultiEffectWhen.IntLoaded -> state.copy(count = action.value)
            is MultiEffectWhen.StringLoaded -> state.copy(marker = action.value)
        }
    }
)

private sealed interface CancelWhen {
    data object Start : CancelWhen
    data object CancelOngoing : CancelWhen
    data class Completed(val value: String) : CancelWhen
}

/** Never completes within a test's virtual time unless something actually advances past its
 *  delay — the point being that a *cancelled* SlowEffect must never get the chance to. */
private class SlowEffect : Effect<CancelWhen> {
    override suspend fun run(): CancelWhen {
        delay(10_000)
        return CancelWhen.Completed("should never arrive")
    }
}

private val cancelConfig = ScopeConfig<TestState, CancelWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is CancelWhen.Start -> {
                effects.enqueue(SlowEffect())
                state.copy(marker = "started")
            }
            is CancelWhen.CancelOngoing -> {
                effects.cancelEffect { it is SlowEffect }
                state.copy(marker = "cancelled")
            }
            is CancelWhen.Completed -> state.copy(marker = action.value)
        }
    }
)

/** A parent that composes [cancelConfig] as a child — reused as-is, [SlowEffect] included, so
 *  deactivating the child can be proven to cancel its own still-running effect Job. */
private data class ParentWithChildState(val child: TestState? = null)

private sealed interface ParentWithChildWhen {
    data object ActivateChild : ParentWithChildWhen
    data object DeactivateChild : ParentWithChildWhen
}

private val childSubScope = SubScope<ParentWithChildState, ParentWithChildWhen, TestState, CancelWhen>(
    config = cancelConfig,
    get = { it.child },
    set = { state, child -> state.copy(child = child) }
)

private val parentWithChildConfig = ScopeConfig<ParentWithChildState, ParentWithChildWhen>(
    initial = ParentWithChildState(),
    update = { state, action, _, _ ->
        when (action) {
            is ParentWithChildWhen.ActivateChild -> state.copy(child = TestState())
            is ParentWithChildWhen.DeactivateChild -> state.copy(child = null)
        }
    },
    subScopes = listOf(childSubScope)
)

private sealed interface QueryWhen {
    data object StartIfNotAlreadyRunning : QueryWhen
    data class Completed(val value: String) : QueryWhen
}

private class CountingSlowEffect : Effect<QueryWhen> {
    override suspend fun run(): QueryWhen {
        delay(10_000)
        return QueryWhen.Completed("effect-completed")
    }
}

/** Mirrors Swift's own documented `guard nil == effects.first(where: { $0 is NetworkEffect<DTO> })`
 *  pattern — a reducer reading its own currently-ongoing effects to avoid double-triggering. */
private val queryConfig = ScopeConfig<TestState, QueryWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is QueryWhen.StartIfNotAlreadyRunning ->
                if (effects.effects.none { it is CountingSlowEffect }) {
                    effects.enqueue(CountingSlowEffect())
                    state.copy(marker = "started")
                } else {
                    state.copy(marker = "already running, skipped")
                }
            is QueryWhen.Completed -> state.copy(marker = action.value)
        }
    }
)

/** Like [testConfig], but Increment also enqueues an effect completing with a marker only the
 *  effect's own completion could have produced — proof the effect actually ran, not just that
 *  the synchronous part of update() did. */
private val effectConfig = ScopeConfig<TestState, TestWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is TestWhen.Increment -> {
                effects.enqueue(FixedResultEffect(TestWhen.MarkerSet("effect-completed")))
                state.copy(count = state.count + 1)
            }
            is TestWhen.MarkerSet -> state.copy(marker = action.value)
        }
    }
)

// Three real levels (Root -> Middle -> Grandchild), proving ScopeConfig.onDescendantAction
// reaches a GRANDCHILD directly from the ROOT, with zero relay at the Middle level — the
// runtime-broadcast counterpart to SubScope.onChildAction's one-hop, compile-time-validated
// relay. Mirrors the shape (if not the exact mechanism) SubScopeProcessorTest proves for the
// codegen path.
private data class BroadcastGrandchildState(val tapped: Boolean = false)
private sealed interface BroadcastGrandchildWhen {
    data object Tap : BroadcastGrandchildWhen
}
private val broadcastGrandchildConfig = ScopeConfig<BroadcastGrandchildState, BroadcastGrandchildWhen>(
    initial = BroadcastGrandchildState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastGrandchildWhen.Tap -> state.copy(tapped = true)
        }
    }
)

private data class BroadcastMiddleState(val grandchild: BroadcastGrandchildState? = null)
private sealed interface BroadcastMiddleWhen {
    data object ActivateGrandchild : BroadcastMiddleWhen
}

// Deliberately NO onChildAction here — proving the root sees the grandchild's event with no
// relay from this level at all, not even an unconditional pass-through one.
private val broadcastMiddleGrandchildSubScope =
    SubScope<BroadcastMiddleState, BroadcastMiddleWhen, BroadcastGrandchildState, BroadcastGrandchildWhen>(
        config = broadcastGrandchildConfig,
        get = { it.grandchild },
        set = { state, grandchild -> state.copy(grandchild = grandchild) }
    )

private val broadcastMiddleConfig = ScopeConfig<BroadcastMiddleState, BroadcastMiddleWhen>(
    initial = BroadcastMiddleState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastMiddleWhen.ActivateGrandchild -> state.copy(grandchild = BroadcastGrandchildState())
        }
    },
    subScopes = listOf(broadcastMiddleGrandchildSubScope)
)

private data class BroadcastRootState(
    val middle: BroadcastMiddleState? = null,
    val grandchildTapsSeenAtRoot: Int = 0
)
private sealed interface BroadcastRootWhen {
    data object ActivateMiddle : BroadcastRootWhen
    data object RecordGrandchildTap : BroadcastRootWhen
}
private val broadcastRootMiddleSubScope =
    SubScope<BroadcastRootState, BroadcastRootWhen, BroadcastMiddleState, BroadcastMiddleWhen>(
        config = broadcastMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )

private val broadcastRootConfig = ScopeConfig<BroadcastRootState, BroadcastRootWhen>(
    initial = BroadcastRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastRootWhen.ActivateMiddle -> state.copy(middle = BroadcastMiddleState())
            is BroadcastRootWhen.RecordGrandchildTap ->
                state.copy(grandchildTapsSeenAtRoot = state.grandchildTapsSeenAtRoot + 1)
        }
    },
    subScopes = listOf(broadcastRootMiddleSubScope),
    onDescendantAction = { descendantWhen, _, _, _ ->
        if (descendantWhen is BroadcastGrandchildWhen.Tap) BroadcastRootWhen.RecordGrandchildTap else null
    }
)

class ScopeStoreTest {

    @Test
    fun testInitialStateIsConfigInitial() {
        val store = createScope(testConfig)
        assertEquals(TestState(), store.state)
    }

    @Test
    fun testStateFlowValueAlwaysMatchesStateAfterSend() {
        val store = createScope(testConfig)

        assertEquals(store.state, store.stateFlow.value)

        store.send(TestWhen.Increment)

        assertEquals(1, store.stateFlow.value.count, "stateFlow must reflect a synchronous send()")
        assertEquals(store.state, store.stateFlow.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testStateFlowEmitsWhenAnEffectCompletionUpdatesState() = runTest {
        scopeEffectsDisabledInUnitTests = false
        val store = createScope(effectConfig, effectScope = this)

        val seenMarkers = mutableListOf<String>()
        val collector = launch { store.stateFlow.collect { seenMarkers += it.marker } }

        store.send(TestWhen.Increment)
        advanceUntilIdle()
        collector.cancel()

        // Only the effect's own completion event could have produced this — proves a collector
        // sees the async, effect-driven update, not just the synchronous part of send().
        assertTrue(seenMarkers.contains("effect-completed"), "expected stateFlow to emit the effect-driven marker, got: $seenMarkers")
    }

    @Test
    fun testSendUpdatesStateWithoutMiddleware() {
        val store = createScope(testConfig)
        store.send(TestWhen.Increment)
        assertEquals(1, store.state.count)
    }

    @Test
    fun testAddMiddlewareReturnsSameStore() {
        val store = createScope(testConfig)
        val returned = store.addMiddleware { _, action, forward -> forward(action) }
        assertSame(store, returned)
    }

    @Test
    fun testMiddlewareCanBlockAnEventByNeverCallingForward() {
        val store = createScope(testConfig)
            .addMiddleware { _, _, _ -> /* never calls forward: the event is blocked */ }

        store.send(TestWhen.Increment)

        assertEquals(0, store.state.count, "Blocked event must never reach update()")
    }

    // scopeEffectsDisabledInUnitTests defaults to true under JUnit (see ScopeStore.kt) so an
    // app-level test calling createScope(...) directly doesn't accidentally run real effects.
    // This test's whole point is proving effect execution genuinely works, so it opts back in —
    // exactly the setUp/tearDown pattern Swift's own StoreEffectsTests.swift uses around its
    // identical scopeEffectsDisabledInUnitTests flag.
    @BeforeTest
    fun enableRealEffects() {
        scopeEffectsDisabledInUnitTests = false
    }

    @AfterTest
    fun restoreEffectsDisabled() {
        scopeEffectsDisabledInUnitTests = true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEnqueuedEffectActuallyRunsAndFeedsBackResult() = runTest {
        // Passing this test's own TestScope as effectScope lets advanceUntilIdle() below
        // deterministically drive the effect's coroutine to completion — no real delay, no
        // flaky polling.
        val store = createScope(effectConfig, effectScope = this)

        store.send(TestWhen.Increment)
        // The synchronous part of update() has run; the effect it enqueued has not yet.
        assertEquals(1, store.state.count)
        assertEquals("initial", store.state.marker)

        advanceUntilIdle()

        // Only the effect's own completion event could have produced this value.
        assertEquals("effect-completed", store.state.marker)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectDoesNotRunWhenDisabledInUnitTests() = runTest {
        scopeEffectsDisabledInUnitTests = true // the actual default; set explicitly for clarity

        val store = createScope(effectConfig, effectScope = this)
        store.send(TestWhen.Increment)
        advanceUntilIdle()

        assertEquals(1, store.state.count, "the synchronous part of update() still ran")
        assertEquals("initial", store.state.marker, "the enqueued effect must never have been launched")
    }

    @Test
    fun testEffectCompletionsAlwaysArriveOnTheSameDedicatedThread() {
        scopeEffectsDisabledInUnitTests = false

        // Thread.currentThread().id (a stable OS-thread identity), not .name, proves this —
        // kotlinx.coroutines' debug mode (auto-enabled by Gradle's -ea) suffixes the thread's
        // *name* with " @coroutine#N" per coroutine, so two completions on the very same
        // physical thread can still show different names.
        val threadIds = ConcurrentLinkedQueue<Long>()
        var oneThreadName: String? = null
        val bothCompleted = CountDownLatch(2)
        val config = ScopeConfig<TestState, TestWhen>(
            initial = TestState(),
            update = { state, action, effects, _ ->
                when (action) {
                    is TestWhen.Increment -> {
                        effects.enqueue(object : Effect<TestWhen> {
                            override suspend fun run(): TestWhen {
                                threadIds += Thread.currentThread().threadId()
                                oneThreadName = Thread.currentThread().name
                                bothCompleted.countDown()
                                return TestWhen.MarkerSet("done")
                            }
                        })
                        state.copy(count = state.count + 1)
                    }
                    is TestWhen.MarkerSet -> state.copy(marker = action.value)
                }
            }
        )

        // Uses the real default effectScope (defaultEffectDispatcher), not a TestScope — the
        // point is proving genuine cross-thread delivery lands on one consistent thread, which
        // a virtual test dispatcher wouldn't demonstrate.
        val store = createScope(config)
        store.send(TestWhen.Increment)
        store.send(TestWhen.Increment)

        assertTrue(bothCompleted.await(2, TimeUnit.SECONDS), "both effects should have completed within 2s")
        assertEquals(2, threadIds.size)
        assertEquals(1, threadIds.toSet().size, "expected both effect completions on the same OS thread, got ids: $threadIds")
        assertTrue(
            oneThreadName?.startsWith("Statoscope-EffectDispatcher") == true,
            "expected the dedicated dispatcher's thread name, got: $oneThreadName"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCancelEffectActuallyCancelsTheRunningJob() = runTest {
        val store = createScope(cancelConfig, effectScope = this)

        store.send(CancelWhen.Start)
        // SlowEffect is now launched, suspended in delay(10_000) — genuinely ongoing, not completed.
        store.send(CancelWhen.CancelOngoing)

        // If cancelEffect only removed bookkeeping without cancelling the real Job, advancing
        // past the 10s delay here would let SlowEffect complete anyway and overwrite the marker.
        advanceUntilIdle()

        assertEquals("cancelled", store.state.marker, "a cancelled effect's completion must never arrive")
    }

    @OptIn(StatoscopeInternalApi::class)
    @Test
    fun testCompleteEffectFindsTheRequestedTypeAmongMultiplePendingAndReplaysItsRealMap() {
        val store = createScope(multiEffectConfig)
        store.send(MultiEffectWhen.StartBoth)
        // Both RawIntFetch and RawStringFetch are pending, oldest (Int) first — completeEffect
        // must find RawStringFetch specifically, not whichever happens to be oldest.
        assertTrue(store.completeEffect(RawStringFetch::class, "real-string"))

        assertEquals("real-string", store.state.marker, "the real .map() must have run, not a guessed value")
        assertEquals(0, store.state.count, "the still-pending RawIntFetch must be untouched")

        assertTrue(store.completeEffect(RawIntFetch::class, 7))
        assertEquals(7, store.state.count)
    }

    @OptIn(StatoscopeInternalApi::class)
    @Test
    fun testCompleteEffectReturnsFalseWhenNoPendingEffectMatchesTheType() {
        val store = createScope(multiEffectConfig)
        assertTrue(!store.completeEffect(RawStringFetch::class, "unused"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDeactivatingAChildCancelsItsOwnRunningEffect() = runTest {
        val store = createScope(parentWithChildConfig, effectScope = this)

        store.send(ParentWithChildWhen.ActivateChild)
        val childStore = store.childStore(childSubScope)!!
        childStore.send(CancelWhen.Start)
        // SlowEffect is now launched under the child, suspended in delay(10_000) — genuinely ongoing.
        assertEquals("started", childStore.state.marker)

        store.send(ParentWithChildWhen.DeactivateChild)
        assertEquals(null, store.state.child, "child was deactivated")

        // If deactivateChild only dropped the child from activeChildren without cancelling its
        // real Job, advancing past the 10s delay here would let SlowEffect complete anyway,
        // silently updating this now-orphaned store's own state that nothing in the tree
        // references anymore.
        advanceUntilIdle()

        assertEquals(
            "started", childStore.state.marker,
            "a deactivated child's own running effect must never complete"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectsLetsAReducerAvoidDoubleTriggeringAnAlreadyOngoingEffect() = runTest {
        val store = createScope(queryConfig, effectScope = this)

        store.send(QueryWhen.StartIfNotAlreadyRunning)
        assertEquals("started", store.state.marker)

        // CountingSlowEffect is still suspended in its own delay(10_000) — genuinely ongoing.
        store.send(QueryWhen.StartIfNotAlreadyRunning)
        assertEquals(
            "already running, skipped", store.state.marker,
            "effects.effects should have shown the still-ongoing CountingSlowEffect to this second call"
        )
    }

    // MARK: - onDescendantAction (broadcast reach, Issue 7)

    @Test
    fun testOnDescendantActionReachesAGrandchildWithNoRelayAtTheMiddleLevel() {
        val root = createScope(broadcastRootConfig)
        root.send(BroadcastRootWhen.ActivateMiddle)
        val middle = root.childStore(broadcastRootMiddleSubScope)!!
        middle.send(BroadcastMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(broadcastMiddleGrandchildSubScope)!!

        grandchild.send(BroadcastGrandchildWhen.Tap)

        assertTrue(grandchild.state.tapped, "the grandchild's own update() still ran normally")
        assertEquals(
            1, root.state.grandchildTapsSeenAtRoot,
            "the root saw the grandchild's event directly, with no onChildAction relay anywhere " +
                "on the middle level's SubScope"
        )
    }

    @Test
    fun testOnDescendantActionFiresExactlyOncePerDescendantEvent() {
        val root = createScope(broadcastRootConfig)
        root.send(BroadcastRootWhen.ActivateMiddle)
        val middle = root.childStore(broadcastRootMiddleSubScope)!!
        middle.send(BroadcastMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(broadcastMiddleGrandchildSubScope)!!

        grandchild.send(BroadcastGrandchildWhen.Tap)
        grandchild.send(BroadcastGrandchildWhen.Tap)
        grandchild.send(BroadcastGrandchildWhen.Tap)

        assertEquals(
            3, root.state.grandchildTapsSeenAtRoot,
            "each descendant event should notify ancestors exactly once — not zero, not more than once"
        )
    }

    @Test
    fun testOnDescendantActionDoesNotFireForTheRootsOwnSelfSentEvents() {
        val root = createScope(broadcastRootConfig)

        // ActivateMiddle is the root's OWN event, sent to itself — not a descendant's. The walk
        // starts at `parent`, not `this`, so this must never reach the root's own
        // onDescendantAction (which only matches BroadcastGrandchildWhen.Tap anyway, but the
        // real point is this is a *self*-sent event with no descendant involved at all).
        root.send(BroadcastRootWhen.ActivateMiddle)

        assertEquals(
            0, root.state.grandchildTapsSeenAtRoot,
            "a store's own self-sent event must never trigger its own onDescendantAction"
        )
    }
}
