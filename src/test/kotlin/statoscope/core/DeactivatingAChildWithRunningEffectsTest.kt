//
// DeactivatingAChildWithRunningEffectsTest.kt
// statoscope-kotlin
//
// Removing a child whose effects are still running cancels them. An immediate dispatcher resumes a cancelled effect right inside
// `cancel()`, and that removes it from the store's own map: iterating the map threw a ConcurrentModificationException, after the state
// had changed and before the effects the same update enqueued were launched (the logout of the app stopped there, on a blank screen).
//
package statoscope.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private data class ChildState(val running: Boolean = false)
private sealed interface ChildWhen {
    data object Start : ChildWhen
}

private val childConfig = ScopeConfig<ChildState, ChildWhen>(
    initial = ChildState(),
    update = { state, action, effects, _ ->
        when (action) {
            ChildWhen.Start -> {
                effects.enqueue(NeverEndingEffect())
                effects.enqueue(NeverEndingEffect())
                state.copy(running = true)
            }
        }
    }
)

private class NeverEndingEffect : Effect<ChildWhen> {
    override suspend fun run(): ChildWhen = awaitCancellation()
}

private class DoneEffect : Effect<RootWhen> {
    override suspend fun run(): RootWhen = RootWhen.EffectDone
}

private data class RootState(val child: ChildState? = null, val effectDone: Boolean = false)
private sealed interface RootWhen {
    data object AddChild : RootWhen
    data object RemoveChild : RootWhen
    data object EffectDone : RootWhen
}

private val rootChildSubScope = SubScope<RootState, RootWhen, ChildState, ChildWhen>(
    config = childConfig,
    get = { it.child },
    set = { state, child -> state.copy(child = child) }
)

private val rootConfig = ScopeConfig<RootState, RootWhen>(
    initial = RootState(),
    update = { state, action, effects, _ ->
        when (action) {
            RootWhen.AddChild -> state.copy(child = ChildState())
            // The same update drops the child and asks for an effect of its own
            RootWhen.RemoveChild -> {
                effects.enqueue(DoneEffect())
                state.copy(child = null)
            }
            RootWhen.EffectDone -> state.copy(effectDone = true)
        }
    },
    subScopes = listOf(rootChildSubScope)
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeactivatingAChildWithRunningEffectsTest {

    private val logged = mutableListOf<String>()

    @BeforeTest
    fun realEffectsAndALog() {
        scopeEffectsDisabledInUnitTests = false
        StatoscopeLogger.logReplacement = { _, message -> logged += message }
    }

    @AfterTest
    fun restore() {
        scopeEffectsDisabledInUnitTests = true
        StatoscopeLogger.logReplacement = null
    }

    @Test
    fun testRemovingAChildWithRunningEffectsStillLaunchesTheEffectsOfTheSameUpdate() = runTest(UnconfinedTestDispatcher()) {
        val root = createScope(rootConfig, effectScope = this)
        root.send(RootWhen.AddChild)
        root.childStore(rootChildSubScope)!!.send(ChildWhen.Start)

        root.send(RootWhen.RemoveChild)

        assertNull(root.state.child)
        assertEquals(true, root.state.effectDone, "the effect enqueued by the update that removed the child must run")
        assertFalse(logged.any { it.contains("Exception after sending") }, logged.filter { it.contains("Exception") }.toString())
    }

    @Test
    fun testACancelledEffectIsNotLoggedAsAnUnhandledThrow() = runTest(UnconfinedTestDispatcher()) {
        val root = createScope(rootConfig, effectScope = this)
        root.send(RootWhen.AddChild)
        root.childStore(rootChildSubScope)!!.send(ChildWhen.Start)

        root.send(RootWhen.RemoveChild)

        assertFalse(logged.any { it.contains("Unhandled throw") }, "a cancellation is not an error")
    }
}
