//
// ReplaceDescendantInjectionTest.kt
//
// PARITY_AUDIT.md Issue 21: what survives when a root replaces a grandchild by writing through the
// middle level (`state.copy(middle = middle.copy(leaf = ...))`)? Mirrors Swift's
// ReplaceDescendantInjectionTests (`state.mid?.leaf = ...`). Both sides recreate the middle store.
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val stampKey = InjectionKey("missing", "stamp")

private data class RdiLeafState(val label: String = "")
private sealed interface RdiLeafWhen { data object NoOp : RdiLeafWhen }
private val rdiLeafConfig = ScopeConfig<RdiLeafState, RdiLeafWhen>(
    initial = RdiLeafState(), update = { state, _, _, _ -> state }
)

private data class RdiMidState(val value: Int = 0, val readStamp: String = "", val leaf: RdiLeafState? = null)
private sealed interface RdiMidWhen {
    data class Inject(val stamp: String) : RdiMidWhen
    data object Read : RdiMidWhen
    data object Bump : RdiMidWhen
}
private val rdiMidLeafSubScope = SubScope<RdiMidState, RdiMidWhen, RdiLeafState, RdiLeafWhen>(
    config = rdiLeafConfig,
    get = { it.leaf },
    set = { state, leaf -> state.copy(leaf = leaf) }
)
private val rdiMidConfig = ScopeConfig<RdiMidState, RdiMidWhen>(
    initial = RdiMidState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is RdiMidWhen.Inject -> { dependencies.inject(stampKey, action.stamp); state }
            RdiMidWhen.Read -> state.copy(readStamp = dependencies.resolve(stampKey))
            RdiMidWhen.Bump -> state.copy(value = state.value + 1)
        }
    },
    subScopes = listOf(rdiMidLeafSubScope)
)

private data class RdiRootState(val mid: RdiMidState? = null)
private sealed interface RdiRootWhen {
    data object Create : RdiRootWhen
    data object ReplaceLeafThroughMid : RdiRootWhen
}
private val rdiRootMidSubScope = SubScope<RdiRootState, RdiRootWhen, RdiMidState, RdiMidWhen>(
    config = rdiMidConfig,
    get = { it.mid },
    set = { state, mid -> state.copy(mid = mid) }
)
private val rdiRootConfig = ScopeConfig<RdiRootState, RdiRootWhen>(
    initial = RdiRootState(),
    update = { state, action, _, _ ->
        when (action) {
            RdiRootWhen.Create -> state.copy(mid = RdiMidState(leaf = RdiLeafState("a")))
            RdiRootWhen.ReplaceLeafThroughMid -> state.copy(mid = state.mid!!.copy(leaf = RdiLeafState("b")))
        }
    },
    subScopes = listOf(rdiRootMidSubScope)
)

class ReplaceDescendantInjectionTest {
    @Test
    fun testReplacingALeafThroughTheMiddleRecreatesTheMiddleAndLosesItsInjection() {
        val root = createScope(rdiRootConfig)
        root.send(RdiRootWhen.Create)
        val mid1 = root.childStore(rdiRootMidSubScope)!!
        mid1.send(RdiMidWhen.Bump)
        mid1.send(RdiMidWhen.Inject("injected"))
        mid1.send(RdiMidWhen.Read)
        assertEquals("injected", mid1.state.readStamp, "sanity: the injection resolves before the replacement")

        root.send(RdiRootWhen.ReplaceLeafThroughMid)
        val mid2 = root.childStore(rdiRootMidSubScope)!!
        mid2.send(RdiMidWhen.Read)

        assertFalse(mid1 === mid2, "the middle store is recreated, as in Swift")
        assertEquals(1, mid2.state.value, "its state is carried over: the new store starts from the parent's copy")
        assertEquals("b", mid2.state.leaf?.label)
        assertEquals("missing", mid2.state.readStamp, "what the old store injected is lost, as in Swift")
    }
}
