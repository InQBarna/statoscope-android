//
// ScopeStoreLoggingTest.kt
// statoscope-kotlin
//
// Integration tests proving ScopeStore actually calls into StatoscopeLogger, with the right
// LogLevel and content, at the points documented in ScopeStore.kt's process()/launchEffect() —
// not just that StatoscopeLogger and describeObject/diffLines work correctly in isolation
// (covered by their own test files).
//
// Every test here installs a logReplacement to capture (level, message) pairs. A non-null
// logReplacement receives every level regardless of logLevel (see StatoscopeLoggerTest), so
// these tests filter captured pairs by level themselves rather than relying on logLevel to
// exclude other categories — logLevel gating itself is StatoscopeLoggerTest's job, not this
// file's.
//
package statoscope.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class LogTestState(val count: Int = 0)

private sealed interface LogTestWhen {
    data object Increment : LogTestWhen
    data object ThrowError : LogTestWhen
    data object EffectFired : LogTestWhen
}

private val logTestConfig = ScopeConfig<LogTestState, LogTestWhen>(
    initial = LogTestState(),
    update = { state, action, _, _ ->
        when (action) {
            is LogTestWhen.Increment -> state.copy(count = state.count + 1)
            is LogTestWhen.ThrowError -> throw IllegalStateException("boom")
            is LogTestWhen.EffectFired -> state
        }
    }
)

private class EchoEffect(private val result: LogTestWhen) : Effect<LogTestWhen> {
    override suspend fun run(): LogTestWhen = result
}

// EffectFired, not Increment, is what the effect completes with — Increment's own handler must
// never re-enqueue on the completion it produces, or the store loops forever re-triggering itself.
private val effectLogTestConfig = ScopeConfig<LogTestState, LogTestWhen>(
    initial = LogTestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is LogTestWhen.Increment -> {
                effects.enqueue(EchoEffect(LogTestWhen.EffectFired))
                state.copy(count = state.count + 1)
            }
            is LogTestWhen.ThrowError -> throw IllegalStateException("boom")
            is LogTestWhen.EffectFired -> state
        }
    }
)

class ScopeStoreLoggingTest {

    private val captured = mutableListOf<Pair<LogLevel, String>>()

    // Only testEffectsLevelLogsEnqueueAndCompletion actually enqueues an effect; opting in for
    // the whole class is harmless for the others and mirrors Swift's setUp/tearDown pattern for
    // the equivalent scopeEffectsDisabledInUnitTests flag.
    @BeforeTest
    fun enableRealEffects() {
        scopeEffectsDisabledInUnitTests = false
    }

    @AfterTest
    fun restoreEffectsDisabled() {
        scopeEffectsDisabledInUnitTests = true
    }

    @AfterTest
    fun resetLogger() {
        StatoscopeLogger.logLevel = setOf(LogLevel.Errors)
        StatoscopeLogger.logReplacement = null
    }

    private fun installCapture() {
        StatoscopeLogger.logReplacement = { level, message -> captured += level to message }
    }

    @Test
    fun testWhenLevelLogsTheDispatchedEvent() {
        installCapture()

        createScope(logTestConfig).send(LogTestWhen.Increment)

        val whenMessages = captured.filter { it.first == LogLevel.When }.map { it.second }
        assertTrue(whenMessages.any { it.contains("Increment") }, "expected a When log mentioning Increment, got: $whenMessages")
    }

    @Test
    fun testNoLoggingHappensAtDefaultLevelWithoutAReplacement() {
        // Default: logLevel = {Errors}, no replacement installed (verified fresh by resetLogger
        // running after every prior test). Just confirms turning logging off doesn't affect the
        // store's normal, functional behavior.
        val store = createScope(logTestConfig)
        store.send(LogTestWhen.Increment)

        assertEquals(1, store.state.count)
    }

    @Test
    fun testStateDiffLevelLogsExactlyOneDiffMessageForTheChangedField() {
        installCapture()

        createScope(logTestConfig).send(LogTestWhen.Increment)

        val diffMessages = captured.filter { it.first == LogLevel.StateDiff }.map { it.second }
        val diffMessage = diffMessages.singleOrNull()
        assertTrue(diffMessage != null, "expected exactly one stateDiff message, got: $diffMessages")
        assertTrue(diffMessage.contains("- ") && diffMessage.contains("+ "), "expected both a removal and an addition line")
        assertTrue(diffMessage.contains("count"), "expected the diff to mention the changed field")
    }

    @Test
    fun testErrorsLevelLogsAndSwallowsTheException() {
        installCapture()

        val store = createScope(logTestConfig)
        // Must not throw: send() swallows any exception update() throws, matching Swift's real
        // app-facing send() contract — see ScopeStore.kt's send() doc comment.
        store.send(LogTestWhen.ThrowError)

        assertEquals(0, store.state.count, "update() threw before returning a new state, so state must be unchanged")
        val errorMessages = captured.filter { it.first == LogLevel.Errors }.map { it.second }
        assertTrue(errorMessages.any { it.contains("boom") }, "expected an errors log mentioning the exception, got: $errorMessages")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectsLevelLogsEnqueueAndCompletion() = runTest {
        installCapture()

        val store = createScope(effectLogTestConfig, effectScope = this)
        store.send(LogTestWhen.Increment)
        advanceUntilIdle()

        val effectMessages = captured.filter { it.first == LogLevel.Effects }.map { it.second }
        assertTrue(effectMessages.any { it.contains("↗") }, "expected an enqueue log, got: $effectMessages")
        assertTrue(effectMessages.any { it.contains("↩") }, "expected a completion log, got: $effectMessages")
    }
}
