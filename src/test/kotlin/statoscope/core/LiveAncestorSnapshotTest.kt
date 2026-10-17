//
// LiveAncestorSnapshotTest.kt
// statoscope-kotlin
//
// What a child holding a whole ancestor state (the generated `@ParentState` copy) receives: the ancestor's state WITHOUT the branch
// down to that child. Otherwise the snapshot holds the child, whose state holds the previous snapshot, and so on: a chain that grew by
// one level (a full copy of the ancestor) with every event, in memory and in the log dump.
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val snapshotRootKey = SuperstateKey<SnapshotRootState>("SnapshotRoot")

private data class SnapshotRootState(val version: Int = 0, val middle: SnapshotMiddleState? = null)
private sealed interface SnapshotRootWhen {
    data object Activate : SnapshotRootWhen
    data object Bump : SnapshotRootWhen
}

private data class SnapshotMiddleState(val leaf: SnapshotLeafState? = null)
private sealed interface SnapshotMiddleWhen {
    data object ActivateLeaf : SnapshotMiddleWhen
}

/** Holds the whole root state, as `@ParentState val plan: PlanReducer.State` does */
private data class SnapshotLeafState(val root: SnapshotRootState = SnapshotRootState(version = -1))
private sealed interface SnapshotLeafWhen {
    data object NoOp : SnapshotLeafWhen
}

private val snapshotLeafConfig = ScopeConfig<SnapshotLeafState, SnapshotLeafWhen>(
    initial = SnapshotLeafState(),
    update = { state, _, _, _ -> state },
    liveAncestorKey = snapshotRootKey,
    deriveFromLiveAncestor = { state, dependencies -> state.copy(root = dependencies.resolve(snapshotRootKey)) }
)

private val snapshotMiddleConfig = ScopeConfig<SnapshotMiddleState, SnapshotMiddleWhen>(
    initial = SnapshotMiddleState(),
    update = { state, action, _, _ ->
        when (action) {
            SnapshotMiddleWhen.ActivateLeaf -> state.copy(leaf = SnapshotLeafState())
        }
    },
    subScopes = listOf(
        SubScope<SnapshotMiddleState, SnapshotMiddleWhen, SnapshotLeafState, SnapshotLeafWhen>(
            config = snapshotLeafConfig,
            get = { it.leaf },
            set = { state, leaf -> state.copy(leaf = leaf) }
        )
    )
)

private val snapshotRootConfig = ScopeConfig<SnapshotRootState, SnapshotRootWhen>(
    initial = SnapshotRootState(),
    update = { state, action, _, _ ->
        when (action) {
            SnapshotRootWhen.Activate -> state.copy(middle = SnapshotMiddleState())
            SnapshotRootWhen.Bump -> state.copy(version = state.version + 1)
        }
    },
    subScopes = listOf(
        SubScope<SnapshotRootState, SnapshotRootWhen, SnapshotMiddleState, SnapshotMiddleWhen>(
            config = snapshotMiddleConfig,
            get = { it.middle },
            set = { state, middle -> state.copy(middle = middle) },
            superstateKey = snapshotRootKey
        )
    )
)

class LiveAncestorSnapshotTest {

    private fun leafOf(root: SnapshotRootState) = root.middle!!.leaf!!

    @Test
    fun testTheSnapshotAChildHoldsLeavesOutTheBranchThatLeadsToIt() {
        val root = createScope(snapshotRootConfig)
        root.send(SnapshotRootWhen.Activate)
        val middle = root.childStore(snapshotRootConfig.subScopes.first() as SubScope<SnapshotRootState, SnapshotRootWhen, SnapshotMiddleState, SnapshotMiddleWhen>)!!
        middle.send(SnapshotMiddleWhen.ActivateLeaf)

        root.send(SnapshotRootWhen.Bump)

        val leaf = leafOf(root.state)
        assertEquals(1, leaf.root.version, "the snapshot is live")
        assertNull(leaf.root.middle, "the snapshot must not contain the branch down to the leaf")
    }

    @Test
    fun testTheStateDoesNotGrowWithEveryEvent() {
        val root = createScope(snapshotRootConfig)
        root.send(SnapshotRootWhen.Activate)
        val middle = root.childStore(snapshotRootConfig.subScopes.first() as SubScope<SnapshotRootState, SnapshotRootWhen, SnapshotMiddleState, SnapshotMiddleWhen>)!!
        middle.send(SnapshotMiddleWhen.ActivateLeaf)

        root.send(SnapshotRootWhen.Bump)
        val sizeAfterOne = describeObject(root.state).length
        repeat(50) { root.send(SnapshotRootWhen.Bump) }

        assertEquals(51, leafOf(root.state).root.version)
        assertTrue(describeObject(root.state).length - sizeAfterOne < 50, "the dump grew from $sizeAfterOne to ${describeObject(root.state).length}")
    }
}
