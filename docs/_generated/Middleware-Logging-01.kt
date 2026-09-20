import statoscope.core.LogLevelSets
import statoscope.core.StatoscopeLogger

import kotlin.test.AfterTest
import statoscope.core.LogLevel
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
