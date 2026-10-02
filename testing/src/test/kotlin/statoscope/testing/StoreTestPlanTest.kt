//
// StoreTestPlanTest.kt
// statoscope-kotlin
//
// Exercises the composed-tree DSL (StoreTestPlan.kt) end to end: real navigation via with()/pop(),
// real dispatch via on(), real effect completion simulation via onOldestEffectCompletes(), and a
// child event bubbling back up to its parent via onChildAction — the exact "favorite toggle
// bubbles up" shape statoscope-kotlin-examples' newsfeed Android app was manually verified for on
// a real emulator this session, now automated.
//
package statoscope.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import statoscope.core.Effect
import statoscope.core.ScopeConfig
import statoscope.core.SubScope

private data class ChildState(val loading: Boolean = false, val loadedValue: String? = null)

private sealed interface ChildWhen {
    data object Load : ChildWhen
    data class Loaded(val value: String) : ChildWhen
    data class LoadFailed(val reason: String) : ChildWhen
}

/** Never actually run by any test here — every completion is simulated via
 *  [StoreTestPlan.onOldestEffectCompletes], proving that call never falls through to real work. */
private class FixedLoadEffect(private val value: String) : Effect<ChildWhen> {
    override suspend fun run(): ChildWhen = error("must never actually run — completion is simulated")
}

private val childConfig = ScopeConfig<ChildState, ChildWhen>(
    initial = ChildState(),
    update = { state, action, effects, _ ->
        when (action) {
            is ChildWhen.Load -> {
                effects.enqueue(FixedLoadEffect("real-value"))
                state.copy(loading = true)
            }
            is ChildWhen.Loaded -> state.copy(loading = false, loadedValue = action.value)
            is ChildWhen.LoadFailed -> state.copy(loading = false, loadedValue = "error:${action.reason}")
        }
    }
)

private data class ParentState(val child: ChildState? = null, val loadedCount: Int = 0)

private sealed interface ParentWhen {
    data object ActivateChild : ParentWhen
    data object ChildReportedLoaded : ParentWhen
}

private val childSubScope = SubScope<ParentState, ParentWhen, ChildState, ChildWhen>(
    config = childConfig,
    get = { it.child },
    set = { state, child -> state.copy(child = child) },
    onChildAction = { childWhen, _, _ ->
        if (childWhen is ChildWhen.Loaded) ParentWhen.ChildReportedLoaded else null
    }
)

private val parentConfig = ScopeConfig<ParentState, ParentWhen>(
    initial = ParentState(),
    update = { state, action, _, _ ->
        when (action) {
            is ParentWhen.ActivateChild -> state.copy(child = ChildState())
            is ParentWhen.ChildReportedLoaded -> state.copy(loadedCount = state.loadedCount + 1)
        }
    },
    subScopes = listOf(childSubScope)
)

class StoreTestPlanTest {

    @Test
    fun testNavigateDispatchCompleteEffectAndBubbleUpToTheParent() {
        givenStore(parentConfig)
            .on(ParentWhen.ActivateChild)
            .with(childSubScope)
            .on(ChildWhen.Load)
            .thenEffects { effects -> assertTrue(effects.any { it is FixedLoadEffect }) }
            .onOldestEffectCompletes(ChildWhen.Loaded("real-value"))
            .then { state -> assertEquals("real-value", state.loadedValue) }
            .thenNoEffects()
            .pop()
            .thenEquals(ParentState::loadedCount, 1)
            .runTest()
    }

    @Test
    fun testWithOnAnInactiveChildFailsClearlyInsteadOfSilently() {
        val plan = givenStore(parentConfig)
            .with(childSubScope) // ActivateChild was never sent — no child exists yet
            .on(ChildWhen.Load)

        val error = assertFailsWith<IllegalStateException> { plan.runTest() }
        assertTrue(error.message!!.contains("no active child"))
    }

    @Test
    fun testCallingRunTestTwiceOnTheSamePlanFails() {
        val plan = givenStore(parentConfig).on(ParentWhen.ActivateChild)
        plan.runTest()

        assertFailsWith<IllegalStateException> { plan.runTest() }
    }

    @Test
    fun testForkAtTheChildLevelTestsTheFailurePathWithoutAffectingTheMainSuccessPath() {
        givenStore(parentConfig)
            .on(ParentWhen.ActivateChild)
            .with(childSubScope)
            .on(ChildWhen.Load)
            .FORK(ChildWhen.LoadFailed("network")) {
                it.then { state -> assertEquals("error:network", state.loadedValue) }
                    .pop()
                    .thenEquals(ParentState::loadedCount, 0) // LoadFailed never bubbles up
            }
            .onOldestEffectCompletes(ChildWhen.Loaded("real-value"))
            .then { state -> assertEquals("real-value", state.loadedValue) }
            .pop()
            .thenEquals(ParentState::loadedCount, 1) // the main path's own Loaded did bubble up
            .runTest()
    }

    @Test
    fun testForkAtTheRootLevelRunsAgainstItsOwnIndependentStore() {
        givenStore(parentConfig)
            .FORK(ParentWhen.ChildReportedLoaded) {
                it.thenEquals(ParentState::loadedCount, 1)
            }
            .thenEquals(ParentState::loadedCount, 0) // the main path never sent ChildReportedLoaded
            .runTest()
    }

    @Test
    fun testForkCanBeNestedInsideAnotherForksElseFlow() {
        givenStore(parentConfig)
            .on(ParentWhen.ActivateChild)
            .with(childSubScope)
            .FORK(ChildWhen.LoadFailed("first")) { firstFork ->
                firstFork
                    .FORK(ChildWhen.LoadFailed("nested")) { nestedFork ->
                        nestedFork.then { state -> assertEquals("error:nested", state.loadedValue) }
                    }
                    .then { state -> assertEquals("error:first", state.loadedValue) }
            }
            .on(ChildWhen.Load)
            .thenEquals(ChildState::loading, true) // the main path never dispatched either LoadFailed
            .pop()
            .runTest()
    }
}
