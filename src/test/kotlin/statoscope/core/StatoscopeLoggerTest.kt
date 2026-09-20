//
// StatoscopeLoggerTest.kt
// statoscope-kotlin
//
// Direct unit tests of StatoscopeLogger itself — not tutorial material. StatoscopeLogger is
// global, mutable, process-wide state, so every test resets it in tearDown; leaving a stray
// logReplacement installed would leak into unrelated tests run afterward in the same process.
//
package statoscope.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatoscopeLoggerTest {

    @AfterTest
    fun resetLogger() {
        StatoscopeLogger.logLevel = setOf(LogLevel.Errors)
        StatoscopeLogger.logReplacement = null
    }

    @Test
    fun testDefaultLevelIsErrorsOnly() {
        assertTrue(StatoscopeLogger.isEnabled(LogLevel.Errors))
        assertFalse(StatoscopeLogger.isEnabled(LogLevel.When))
        assertFalse(StatoscopeLogger.isEnabled(LogLevel.State))
        assertFalse(StatoscopeLogger.isEnabled(LogLevel.StateDiff))
        assertFalse(StatoscopeLogger.isEnabled(LogLevel.Effects))
    }

    @Test
    fun testLogLevelGatesWhichLinesAreEmitted() {
        StatoscopeLogger.logLevel = setOf(LogLevel.When)
        val captured = mutableListOf<Pair<LogLevel, String>>()
        StatoscopeLogger.logReplacement = { level, message -> captured += level to message }

        StatoscopeLogger.log(LogLevel.When) { "hello" }
        StatoscopeLogger.log(LogLevel.Effects) { "should still be captured: a replacement enables everything" }

        // A non-null logReplacement receives every call regardless of logLevel — matching
        // Swift's rule that installing a replacement means "everything is enabled" and the
        // replacement itself decides what to do with each level.
        assertEquals(2, captured.size)
    }

    @Test
    fun testMessageLambdaIsNeverCalledWhenLevelDisabledAndNoReplacement() {
        StatoscopeLogger.logLevel = setOf(LogLevel.Errors)
        var invocations = 0

        StatoscopeLogger.log(LogLevel.When) {
            invocations++
            "expensive to build"
        }

        assertEquals(0, invocations, "message() must not run when When is disabled and no replacement is installed")
    }

    @Test
    fun testMessageLambdaRunsWhenLevelEnabled() {
        StatoscopeLogger.logLevel = setOf(LogLevel.When)
        var invocations = 0

        StatoscopeLogger.log(LogLevel.When) {
            invocations++
            "built"
        }

        assertEquals(1, invocations)
    }

    @Test
    fun testPrefixIsPrependedWithASpace() {
        val captured = mutableListOf<String>()
        StatoscopeLogger.logReplacement = { _, message -> captured += message }

        StatoscopeLogger.log(LogLevel.Errors, "CounterState (abcd1234):") { "boom" }

        assertEquals(listOf("CounterState (abcd1234): boom"), captured)
    }

    @Test
    fun testEmptyPrefixAddsNoLeadingSpace() {
        val captured = mutableListOf<String>()
        StatoscopeLogger.logReplacement = { _, message -> captured += message }

        StatoscopeLogger.log(LogLevel.Errors) { "boom" }

        assertEquals(listOf("boom"), captured)
    }

    @Test
    fun testPresetLevelSetsMatchDocumentedContents() {
        assertEquals(emptySet(), LogLevelSets.none)
        assertEquals(setOf(LogLevel.Errors, LogLevel.When), LogLevelSets.basic)
        assertEquals(setOf(LogLevel.Errors, LogLevel.When, LogLevel.Effects, LogLevel.StateDiff), LogLevelSets.all)
        assertEquals(LogLevelSets.all + LogLevel.State, LogLevelSets.verbose)
    }
}
