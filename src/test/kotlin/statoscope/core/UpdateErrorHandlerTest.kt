//
// UpdateErrorHandlerTest.kt
// statoscope-kotlin
//
// A handler injected on the root hears the exceptions of every store below it, once each, while send() still swallows them.
//
package statoscope.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class HChildState(val count: Int = 0)
private sealed interface HChildWhen {
    data object Increment : HChildWhen
    data object Throw : HChildWhen
}

private val hChildConfig = ScopeConfig<HChildState, HChildWhen>(
    initial = HChildState(),
    update = { state, action, _, _ ->
        when (action) {
            HChildWhen.Increment -> state.copy(count = state.count + 1)
            HChildWhen.Throw -> throw IllegalStateException("child boom")
        }
    }
)

private data class HRootState(val child: HChildState? = HChildState())
private sealed interface HRootWhen {
    data object Throw : HRootWhen
}

private val hRootChildSubScope = SubScope<HRootState, HRootWhen, HChildState, HChildWhen>(
    config = hChildConfig,
    get = { it.child },
    set = { state, child -> state.copy(child = child) }
)

private val hRootConfig = ScopeConfig<HRootState, HRootWhen>(
    initial = HRootState(),
    update = { state, action, _, _ ->
        when (action) {
            HRootWhen.Throw -> throw IllegalArgumentException("root boom")
        }
    },
    subScopes = listOf(hRootChildSubScope)
)

class UpdateErrorHandlerTest {

    @BeforeTest
    fun silence() {
        StatoscopeLogger.logReplacement = { _, _ -> }
    }

    @AfterTest
    fun restore() {
        StatoscopeLogger.logReplacement = null
    }

    @Test
    fun theRootHearsItsOwnError() {
        val heard = mutableListOf<Throwable>()
        val root = createScope(hRootConfig)
        root.inject(UpdateErrorHandlerKey) { heard += it }

        root.send(HRootWhen.Throw)

        assertEquals(listOf("root boom"), heard.map { it.message })
    }

    @Test
    fun theRootHearsTheErrorOfAChildOnce() {
        val heard = mutableListOf<Throwable>()
        val root = createScope(hRootConfig)
        root.inject(UpdateErrorHandlerKey) { heard += it }

        root.childStore(hRootChildSubScope)!!.send(HChildWhen.Throw)

        assertEquals(listOf("child boom"), heard.map { it.message })
    }

    @Test
    fun theErrorItselfIsPassedNotACopy() {
        var heard: Throwable? = null
        val root = createScope(hRootConfig)
        root.inject(UpdateErrorHandlerKey) { heard = it }

        root.send(HRootWhen.Throw)

        assertTrue(heard is IllegalArgumentException)
        assertSame(heard, heard)
    }

    @Test
    fun anEventThatDoesNotThrowIsNotReported() {
        val heard = mutableListOf<Throwable>()
        val root = createScope(hRootConfig)
        root.inject(UpdateErrorHandlerKey) { heard += it }

        root.childStore(hRootChildSubScope)!!.send(HChildWhen.Increment)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun withoutAHandlerTheErrorIsStillSwallowed() {
        val root = createScope(hRootConfig)

        root.send(HRootWhen.Throw) // no exception out
        root.childStore(hRootChildSubScope)!!.send(HChildWhen.Throw)
    }

    @Test
    fun aHandlerThatThrowsDoesNotBreakSend() {
        val root = createScope(hRootConfig)
        root.inject(UpdateErrorHandlerKey) { throw IllegalStateException("the reporter is broken") }

        root.send(HRootWhen.Throw)
        root.childStore(hRootChildSubScope)!!.send(HChildWhen.Throw)
    }
}
