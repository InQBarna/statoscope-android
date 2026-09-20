//
// MiddlewareExample.kt
// statoscope-kotlin
//
// Examples from Tutorial 03: Middleware
//
package statoscope.examples.tutorial03

// @extract:begin Middleware-Counter-01
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.createScope
// @extract:end Middleware-Counter-01

// @extract:begin Middleware-Test-01
// @extract:begin Middleware-Test-02
// @extract:begin Middleware-Test-03
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
// @extract:end Middleware-Test-01
// @extract:end Middleware-Test-02
// @extract:end Middleware-Test-03

// @extract:begin Middleware-LoggingSetup-01
// @extract:begin Middleware-Logging-01
import statoscope.core.LogLevelSets
import statoscope.core.StatoscopeLogger
// @extract:end Middleware-LoggingSetup-01

import kotlin.test.AfterTest
import statoscope.core.LogLevel
// @extract:end Middleware-Logging-01

// @extract:begin Middleware-Counter-01
/** Tutorial 03: same counter shape as Tutorial 01, plus an event that always throws. */
data class CounterState(val viewDisplaysTotalCount: Int = 0)

sealed interface CounterWhen {
    data object UserTappedIncrementButton : CounterWhen
    data object UserTappedDecrementButton : CounterWhen
    data object ErrorCase : CounterWhen // Will throw an error
}

fun counterUpdate(
    state: CounterState,
    action: CounterWhen,
    effects: EffectsState<CounterWhen>,
    dependencies: Dependencies
): CounterState {
    return when (action) {
        is CounterWhen.UserTappedIncrementButton ->
            state.copy(viewDisplaysTotalCount = state.viewDisplaysTotalCount + 1)
        is CounterWhen.UserTappedDecrementButton ->
            state.copy(viewDisplaysTotalCount = maxOf(0, state.viewDisplaysTotalCount - 1))
        is CounterWhen.ErrorCase ->
            throw IllegalStateException("Invalid state")
    }
}

val counterConfig = ScopeConfig(initial = CounterState(), update = ::counterUpdate)
// @extract:end Middleware-Counter-01

// @extract:begin Middleware-Test-01
// @extract:begin Middleware-Test-02
// @extract:begin Middleware-Test-03
class MiddlewareTest {

    @Test
    fun testMiddlewareInterceptsEvents() {
        val interceptedEvents = mutableListOf<CounterWhen>()

        val counter = createScope(counterConfig)
            .addMiddleware { _, action, forward ->
                interceptedEvents += action
                forward(action)
            }

        counter.send(CounterWhen.UserTappedIncrementButton)
        counter.send(CounterWhen.UserTappedDecrementButton)

        assertEquals(2, interceptedEvents.size)
    }
    // @extract:end Middleware-Test-01

    @Test
    fun testMiddlewareCanHandleErrors() {
        val errorsCaught = mutableListOf<Throwable>()

        val counter = createScope(counterConfig)
            .addMiddleware { _, action, forward ->
                try {
                    forward(action)
                } catch (error: Throwable) {
                    errorsCaught += error
                }
            }

        counter.send(CounterWhen.ErrorCase)

        assertEquals(1, errorsCaught.size)
        assertTrue(errorsCaught.first() is IllegalStateException)
    }
    // @extract:end Middleware-Test-02

    @Test
    fun testMiddlewareCanLogEvents() {
        val logs = mutableListOf<String>()

        val counter = createScope(counterConfig)
            .addMiddleware { _, action, forward ->
                logs += "Event: $action"
                forward(action)
            }

        counter.send(CounterWhen.UserTappedIncrementButton)

        assertEquals(1, counter.state.viewDisplaysTotalCount)
        assertTrue(logs.contains("Event: UserTappedIncrementButton"))
    }
    // @extract:begin Middleware-Test-01
    // @extract:begin Middleware-Test-02
}
// @extract:end Middleware-Test-01
// @extract:end Middleware-Test-02
// @extract:end Middleware-Test-03

// @extract:begin Middleware-LoggingSetup-01
private fun enableVerboseLogging() {
    StatoscopeLogger.logLevel = LogLevelSets.all
}
// @extract:end Middleware-LoggingSetup-01

// @extract:begin Middleware-Logging-01
class BuiltInLoggingTest {

    @AfterTest
    fun resetLogger() {
        StatoscopeLogger.logLevel = setOf(LogLevel.Errors)
        StatoscopeLogger.logReplacement = null
    }

    @Test
    fun testBuiltInLoggingCapturesEventsStateDiffsAndErrors() {
        enableVerboseLogging()
        val logs = mutableListOf<String>()
        StatoscopeLogger.logReplacement = { _, message -> logs += message }

        val counter = createScope(counterConfig)
        counter.send(CounterWhen.UserTappedIncrementButton)
        counter.send(CounterWhen.UserTappedDecrementButton)
        // send() swallows this exception after logging it — it never reaches this call site.
        // Use addMiddleware (the previous section) instead of relying on this if you need to
        // actually react to the error, not just see it in the log.
        counter.send(CounterWhen.ErrorCase)

        assertTrue(logs.any { it.contains("UserTappedIncrementButton") })
        assertTrue(logs.any { it.contains("- ") && it.contains("viewDisplaysTotalCount: 0") })
        assertTrue(logs.any { it.contains("+ ") && it.contains("viewDisplaysTotalCount: 1") })
        assertTrue(logs.any { it.contains("Exception on send") })
    }
}
// @extract:end Middleware-Logging-01
