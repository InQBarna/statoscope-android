//
// CreateAndroidScopeTest.kt
// statoscope-kotlin :android
//
package statoscope.android

import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.core.Effect
import statoscope.core.ScopeConfig

private data class CounterState(val count: Int = 0)

private sealed interface CounterWhen {
    data object Increment : CounterWhen
    data object EffectFired : CounterWhen
}

private class NoOpEffect : Effect<CounterWhen> {
    override suspend fun run(): CounterWhen = CounterWhen.EffectFired
}

private val config = ScopeConfig<CounterState, CounterWhen>(
    initial = CounterState(),
    update = { state, action, effects, _ ->
        when (action) {
            is CounterWhen.Increment -> {
                effects.enqueue(NoOpEffect())
                state.copy(count = state.count + 1)
            }
            is CounterWhen.EffectFired -> state
        }
    }
)

class CreateAndroidScopeTest {

    /**
     * The one thing this test *can* prove outside a real Android runtime: constructing the
     * store — including referencing `Dispatchers.Main.immediate` to build its `effectScope` —
     * never throws, and `send()` still updates state synchronously exactly like plain
     * `createScope`. `Dispatchers.Main` only throws when something actually dispatches onto it
     * without a real Looper registered; merely referencing the dispatcher to build a
     * `CoroutineScope` does not. Proving effect completions genuinely arrive on Android's main
     * thread needs a real device/emulator or Robolectric — out of scope for a plain-JVM unit
     * test, and not attempted here.
     */
    @Test
    fun testConstructingAndSendingNeverTouchesTheRealMainDispatcher() {
        val store = createAndroidScope(config)

        assertEquals(CounterState(), store.state)

        store.send(CounterWhen.Increment)

        assertEquals(1, store.state.count, "the synchronous part of update() must still run")
        // scopeEffectsDisabledInUnitTests (auto-detected true under JUnit) means the enqueued
        // NoOpEffect is recorded but never launched — so nothing ever actually dispatches onto
        // Dispatchers.Main.immediate here, which is exactly why this call is safe under plain
        // JUnit with no real Android Looper present.
    }
}
