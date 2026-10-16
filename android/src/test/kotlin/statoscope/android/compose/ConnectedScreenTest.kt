package statoscope.android.compose

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.createScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConnectedScreen]/[ConnectedChild] previously had zero unit tests — only device/emulator
 * verification — despite `:android` being pure Kotlin/JVM with no Android framework dependency
 * (see `TEST_PARITY_MAP.md`, gap 5). Molecule drives these plain `@Composable` functions off a
 * real `Recomposer`, with no compose-ui and no device, the same way `:android` itself only needs
 * `androidx.compose.runtime` to compile against.
 *
 * [UnconfinedTestDispatcher] (not the default `StandardTestDispatcher`) is required here: a
 * `moleculeFlow`'s internal recomposition loop isn't itself a task posted to the test scheduler's
 * virtual-time queue the way a plain `launch { delay(...) }` is, so `advanceUntilIdle()` alone
 * never runs it under the default dispatcher — confirmed empirically (a trivial `moleculeFlow {
 * 42 }` collected under `StandardTestDispatcher` + `advanceUntilIdle()` never emits). Unconfined
 * execution — each coroutine runs eagerly up to its first real suspension point — is what
 * actually drives the recomposition; [RecompositionMode.Immediate] then still matters, since it
 * recomposes synchronously on state change rather than waiting on a frame clock this test never
 * provides.
 *
 * Every probe below appends to its `captured` list from directly *inside* `content`, never by
 * reading a composable-local `var` back out in the calling code after the fact: a `State` read
 * inside `ConnectedChild`'s own body only invalidates `ConnectedChild`'s own recompose scope, not
 * whatever scope called it — so code placed *after* the call, reading a plain local the content
 * lambda closed over, can silently stop re-running after the first composition even though
 * `ConnectedChild` itself keeps recomposing correctly underneath it (confirmed empirically: the
 * child activates for real, `childStore(subScope)` and `store.state` both update correctly, yet a
 * post-call local read stays stuck on its very first value forever). Appending directly inside
 * `content` sidesteps this entirely, the same way [ConnectedScreen]'s own tests already do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedScreenTest {

    private data class CounterState(val count: Int = 0)

    private sealed interface CounterWhen {
        data object Increment : CounterWhen
    }

    private val counterConfig = ScopeConfig<CounterState, CounterWhen>(
        initial = CounterState(),
        update = { state, action, _, _ ->
            when (action) {
                is CounterWhen.Increment -> state.copy(count = state.count + 1)
            }
        }
    )

    private data class ChildState(val count: Int = 0)

    private sealed interface ChildWhen {
        data object Increment : ChildWhen
    }

    private val childConfig = ScopeConfig<ChildState, ChildWhen>(
        initial = ChildState(),
        update = { state, action, _, _ ->
            when (action) {
                is ChildWhen.Increment -> state.copy(count = state.count + 1)
            }
        }
    )

    private data class ParentState(val child: ChildState? = null)

    private sealed interface ParentWhen {
        data object ActivateChild : ParentWhen
        data object DeactivateChild : ParentWhen
    }

    private val childSubScope = SubScope<ParentState, ParentWhen, ChildState, ChildWhen>(
        config = childConfig,
        get = { it.child },
        set = { state, child -> state.copy(child = child) }
    )

    private val parentConfig = ScopeConfig<ParentState, ParentWhen>(
        initial = ParentState(),
        update = { state, action, _, _ ->
            when (action) {
                is ParentWhen.ActivateChild -> state.copy(child = ChildState())
                is ParentWhen.DeactivateChild -> state.copy(child = null)
            }
        },
        subScopes = listOf(childSubScope)
    )

    @Test
    fun testConnectedScreenEmitsCurrentStateAndReinvokesOnChange() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(counterConfig)
        val captured = mutableListOf<CounterState>()
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedScreen(store) { state, _ -> captured.add(state) }
        }
        val job = backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(CounterState(0)), captured)

        store.send(CounterWhen.Increment)
        advanceUntilIdle()
        assertEquals(listOf(CounterState(0), CounterState(1)), captured)
        job.cancel()
    }

    @Test
    fun testConnectedScreenDispatchSendsThroughTheRealStore() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(counterConfig)
        var dispatch: ((CounterWhen) -> Unit)? = null
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedScreen(store) { _, send -> dispatch = send }
        }
        val job = backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()

        dispatch?.invoke(CounterWhen.Increment)
        advanceUntilIdle()
        assertEquals(1, store.state.count)
        job.cancel()
    }

    @Test
    fun testConnectedChildRendersNothingBeforeTheChildExists() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(parentConfig)
        val captured = mutableListOf<ChildState>()
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedChild(store, childSubScope) { state, _ -> captured.add(state) }
        }
        val job = backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()

        assertTrue(captured.isEmpty(), "content must never run while the child doesn't exist")
        assertNull(store.childStore(childSubScope))
        job.cancel()
    }

    @Test
    fun testConnectedChildRendersTheChildOnceActivatedAndReinvokesOnItsOwnStateChange() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(parentConfig)
        val captured = mutableListOf<ChildState>()
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedChild(store, childSubScope) { state, _ -> captured.add(state) }
        }
        val job = backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()

        store.send(ParentWhen.ActivateChild)
        advanceUntilIdle()
        assertEquals(ChildState(0), captured.last())

        store.childStore(childSubScope)!!.send(ChildWhen.Increment)
        advanceUntilIdle()
        assertEquals(ChildState(1), captured.last())
        job.cancel()
    }

    @Test
    fun testConnectedChildStopsRenderingAfterTheChildIsDeactivated() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(parentConfig)
        val captured = mutableListOf<ChildState>()
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedChild(store, childSubScope) { state, _ -> captured.add(state) }
        }
        val job = backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()

        store.send(ParentWhen.ActivateChild)
        advanceUntilIdle()
        assertEquals(ChildState(0), captured.last())
        val capturedCountWhileActive = captured.size

        store.send(ParentWhen.DeactivateChild)
        advanceUntilIdle()
        assertNull(store.childStore(childSubScope))
        assertEquals(capturedCountWhileActive, captured.size, "content must not run again once the child is gone")
        job.cancel()
    }

    @Test
    fun testConnectedChildDispatchSendsIntoTheChildStoreNotTheParent() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(parentConfig)
        var childDispatch: ((ChildWhen) -> Unit)? = null
        val flow = moleculeFlow(RecompositionMode.Immediate) {
            ConnectedChild(store, childSubScope) { _, send -> childDispatch = send }
        }
        val job = backgroundScope.launch { flow.collect {} }
        store.send(ParentWhen.ActivateChild)
        advanceUntilIdle()

        childDispatch?.invoke(ChildWhen.Increment)
        advanceUntilIdle()
        assertEquals(1, store.childStore(childSubScope)!!.state.count)
        assertEquals(ParentState(child = ChildState(1)), store.state)
        job.cancel()
    }
}
