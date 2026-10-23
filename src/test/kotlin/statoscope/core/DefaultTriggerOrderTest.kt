//
// DefaultTriggerOrderTest.kt
// statoscope-kotlin
//
// What a child's defaultTrigger sees of its ancestors' state. In Swift, a parent that changes one of its own
// fields and creates a child in the same update() has the child's defaultWhen read the NEW field through
// @SuperState. Here the child is pre-populated inside a descendant created in that same update, so it is activated while
// the store above it is still being constructed (ScopeStoreImpl's init), before process() publishes the new state.
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals

private val orderRootKey = SuperstateKey<OrderRootState>("OrderRoot")

private data class OrderLeafState(val linkSeenAtActivation: String? = null)
private sealed interface OrderLeafWhen {
    data object OnAppear : OrderLeafWhen
}
private val orderLeafConfig = ScopeConfig<OrderLeafState, OrderLeafWhen>(
    initial = OrderLeafState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is OrderLeafWhen.OnAppear -> state.copy(linkSeenAtActivation = dependencies.resolve(orderRootKey).link)
        }
    },
    defaultTrigger = OrderLeafWhen.OnAppear
)

private data class OrderMiddleState(val leaf: OrderLeafState? = null)
private sealed interface OrderMiddleWhen {
    data object NoOp : OrderMiddleWhen
}
private val orderMiddleLeafSubScope = SubScope<OrderMiddleState, OrderMiddleWhen, OrderLeafState, OrderLeafWhen>(
    config = orderLeafConfig,
    get = { it.leaf },
    set = { state, leaf -> state.copy(leaf = leaf) }
)
private val orderMiddleConfig = ScopeConfig<OrderMiddleState, OrderMiddleWhen>(
    initial = OrderMiddleState(),
    update = { state, _, _, _ -> state },
    subScopes = listOf(orderMiddleLeafSubScope)
)

private data class OrderRootState(val link: String? = null, val middle: OrderMiddleState? = null)
private sealed interface OrderRootWhen {
    data class OpenLink(val link: String) : OrderRootWhen
}
private val orderRootMiddleSubScope = SubScope<OrderRootState, OrderRootWhen, OrderMiddleState, OrderMiddleWhen>(
    config = orderMiddleConfig,
    get = { it.middle },
    set = { state, middle -> state.copy(middle = middle) },
    superstateKey = orderRootKey
)
private val orderRootConfig = ScopeConfig<OrderRootState, OrderRootWhen>(
    initial = OrderRootState(),
    update = { state, action, _, _ ->
        when (action) {
            // The field and the new subtree change in one update, like PreLanding's `link` + `account.acceptInvitations`
            is OrderRootWhen.OpenLink -> state.copy(link = action.link, middle = OrderMiddleState(leaf = OrderLeafState()))
        }
    },
    subScopes = listOf(orderRootMiddleSubScope)
)

class DefaultTriggerOrderTest {

    @Test
    fun testAPrePopulatedGrandchildsDefaultTriggerSeesTheStateTheParentPublishedInTheSameUpdate() {
        val root = createScope(orderRootConfig)
        root.send(OrderRootWhen.OpenLink("fmp://acceptinvite/1"))

        val leaf = root.childStore(orderRootMiddleSubScope)!!.childStore(orderMiddleLeafSubScope)!!
        assertEquals("fmp://acceptinvite/1", leaf.state.linkSeenAtActivation)
    }
}
