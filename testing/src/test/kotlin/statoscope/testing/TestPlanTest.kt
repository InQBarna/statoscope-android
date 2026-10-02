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
import statoscope.core.SuperstateKey
import statoscope.core.map

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

// A real .map()-using fixture — NoOpEffect above returns CounterWhen directly, so it can't
// exercise onEffectCompletes() (there's no mapping chain to replay). run() deliberately errors:
// onEffectCompletes() must never call it for real.
private data class NetworkState(val loading: Boolean = false, val value: Int = 0)

private sealed interface NetworkWhen {
    data object Load : NetworkWhen
    data class Loaded(val value: Int) : NetworkWhen
}

private class RawNetworkFetch : Effect<Int> {
    override suspend fun run(): Int = error("must never actually run — completion is simulated")
}

private val networkConfig = ScopeConfig<NetworkState, NetworkWhen>(
    initial = NetworkState(),
    update = { state, action, effects, _ ->
        when (action) {
            is NetworkWhen.Load -> {
                effects.enqueue(RawNetworkFetch().map(NetworkWhen::Loaded))
                state.copy(loading = true)
            }
            is NetworkWhen.Loaded -> state.copy(loading = false, value = action.value)
        }
    }
)

private val greetingSuperstateKey = SuperstateKey<String>("Greeting")

private data class GreetingState(val greeting: String = "")
private sealed interface GreetingWhen {
    data object ReadGreeting : GreetingWhen
}
private val greetingConfig = ScopeConfig<GreetingState, GreetingWhen>(
    initial = GreetingState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is GreetingWhen.ReadGreeting -> state.copy(greeting = dependencies.resolve(greetingSuperstateKey))
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

    @Test
    fun testOnEffectCompletesReplaysTheRealMapFromARawPristineValue() {
        given(networkConfig)
            .on(NetworkWhen.Load)
            .onEffectCompletes(RawNetworkFetch::class, 42)
            .thenEquals(NetworkState::value, 42)
            .thenEquals(NetworkState::loading, false)
            .runTest()
    }

    @Test
    fun testOnEffectCompletesFailsClearlyWhenNoMatchingEffectIsPending() {
        val plan = given(networkConfig).onEffectCompletes(RawNetworkFetch::class, 42)
        assertFailsWith<IllegalStateException> { plan.runTest() }
    }

    // MARK: - withSuperstate (standing in for a SuperstateKey's missing real ancestor)

    @Test
    fun testWithSuperstateSuppliesAFakeAncestorValueForIsolatedTesting() {
        given(greetingConfig)
            .withSuperstate(greetingSuperstateKey, "hello-from-fake")
            .on(GreetingWhen.ReadGreeting)
            .thenEquals(GreetingState::greeting, "hello-from-fake")
            .runTest()
    }

    @Test
    fun testResolvingASuperstateKeyWithoutWithSuperstateFailsWithAHelpfulMessage() {
        val plan = given(greetingConfig).on(GreetingWhen.ReadGreeting)
        val error = assertFailsWith<IllegalStateException> { plan.runTest() }
        assertTrue(
            error.message!!.contains("withSuperstate"),
            "the error should point the tester at withSuperstate(key, value), not just fail blankly"
        )
    }
}
