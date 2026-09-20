//
// TestPlanTest.kt
// statoscope-kotlin
//
// Direct unit tests of TestPlan itself — not tutorial material.
//
package statoscope.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig

private data class CounterState(val count: Int = 0)

private sealed interface CounterWhen {
    data object IncrementWithEffect : CounterWhen
    data object IncrementWithoutEffect : CounterWhen
    data object EffectFired : CounterWhen
}

private class NoOpEffect : Effect<CounterWhen> {
    override suspend fun run(): CounterWhen = CounterWhen.EffectFired
}

private val config = ScopeConfig<CounterState, CounterWhen>(
    initial = CounterState(),
    update = { state, action, effects: EffectsState<CounterWhen>, _ ->
        when (action) {
            is CounterWhen.IncrementWithEffect -> {
                effects.enqueue(NoOpEffect())
                state.copy(count = state.count + 1)
            }
            is CounterWhen.IncrementWithoutEffect -> state.copy(count = state.count + 1)
            is CounterWhen.EffectFired -> state
        }
    }
)

class TestPlanTest {

    @Test
    fun testThenEffectsSeesTheEffectEnqueuedByTheImmediatelyPrecedingOn() {
        var sawExactlyOneEffect = false

        given(config)
            .on(CounterWhen.IncrementWithEffect)
            .thenEffects { effects -> sawExactlyOneEffect = effects.size == 1 }
            .runTest()

        assertTrue(sawExactlyOneEffect, "expected exactly one enqueued effect to be visible to thenEffects")
    }

    @Test
    fun testThenNoEffectsPassesWhenNothingWasEnqueued() {
        given(config)
            .on(CounterWhen.IncrementWithoutEffect)
            .thenNoEffects()
            .runTest()
    }

    @Test
    fun testThenEffectsOnlySeesTheMostRecentOnStepNotEarlierOnes() {
        var effectCountAfterSecondStep = -1

        given(config)
            .on(CounterWhen.IncrementWithEffect)
            .on(CounterWhen.IncrementWithoutEffect)
            .thenEffects { effects -> effectCountAfterSecondStep = effects.size }
            .runTest()

        assertEquals(0, effectCountAfterSecondStep, "effects from the first on() must not leak into the second's assertion")
    }

    @Test
    fun testCallingRunTestTwiceOnTheSamePlanFails() {
        val plan = given(config).on(CounterWhen.IncrementWithoutEffect)
        plan.runTest()

        assertFailsWith<IllegalStateException> { plan.runTest() }
    }

    @Test
    fun testEnqueuedEffectIsNeverActuallyRun() {
        // Same rule as everywhere else in TestPlan: dispatching the effect's result is the only
        // way it takes effect. Sending only IncrementWithEffect and never EffectFired means
        // count must be exactly 1, never touched a second time by the effect completing.
        given(config)
            .on(CounterWhen.IncrementWithEffect)
            .thenEquals(CounterState::count, 1)
            .runTest()
    }
}
