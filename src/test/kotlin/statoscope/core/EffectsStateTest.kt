package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests MutableEffectsState directly, the internal implementation itself — not app-facing usage
// (that's ScopeStoreTest/tutorial examples, which only ever see the public EffectsState
// interface). A legitimate same-module reason to opt in, same as ScopeStoreImpl itself.
@OptIn(StatoscopeInternalApi::class)
class EffectsStateTest {

    private class NamedEffect(private val name: String) : Effect<String> {
        override suspend fun run(): String = name
        override fun toString(): String = name
    }

    private class OtherEffect : Effect<String> {
        override suspend fun run(): String = "other"
    }

    @Test
    fun testFlushNewlyEnqueuedReturnsEffectsInOrder() {
        val effects = MutableEffectsState<String>()
        val first = NamedEffect("a")
        val second = NamedEffect("b")

        effects.enqueue(first)
        effects.enqueue(second)

        assertEquals(listOf(first, second), effects.flushNewlyEnqueued())
    }

    @Test
    fun testFlushNewlyEnqueuedDrains() {
        val effects = MutableEffectsState<String>()
        effects.enqueue(NamedEffect("a"))

        effects.flushNewlyEnqueued()

        assertEquals(emptyList(), effects.flushNewlyEnqueued(), "A second flush must not re-return already-flushed effects")
    }

    @Test
    fun testOngoingEffectsAreVisibleViaEffects() {
        val ongoing = NamedEffect("ongoing")
        val effects = MutableEffectsState<String>(ongoing = listOf(1L to ongoing))

        assertEquals(listOf(ongoing), effects.effects)
    }

    @Test
    fun testNewlyEnqueuedEffectsAreVisibleViaEffectsBeforeFlushing() {
        val effects = MutableEffectsState<String>()
        val enqueued = NamedEffect("just enqueued")

        effects.enqueue(enqueued)

        assertEquals(listOf(enqueued), effects.effects, "enqueue() must be visible via effects immediately, same call")
    }

    @Test
    fun testCancelEffectRemovesAMatchingOngoingEffectAndRecordsItsRealId() {
        val ongoing = NamedEffect("ongoing")
        val effects = MutableEffectsState<String>(ongoing = listOf(7L to ongoing))

        effects.cancelEffect { it is NamedEffect }

        assertTrue(effects.effects.isEmpty())
        assertEquals(setOf(7L), effects.flushCancelledExistingIds())
    }

    @Test
    fun testCancelEffectLeavesNonMatchingOngoingEffectsAlone() {
        val ongoing = NamedEffect("ongoing")
        val effects = MutableEffectsState<String>(ongoing = listOf(1L to ongoing))

        effects.cancelEffect { it is OtherEffect }

        assertEquals(listOf(ongoing), effects.effects)
        assertTrue(effects.flushCancelledExistingIds().isEmpty())
    }

    @Test
    fun testCancelEffectOnSomethingEnqueuedThisSameCallDropsItWithoutRecordingARealIdToCancel() {
        val effects = MutableEffectsState<String>()
        effects.enqueue(NamedEffect("about to be cancelled"))

        effects.cancelEffect { it is NamedEffect }

        assertTrue(effects.effects.isEmpty())
        assertTrue(effects.flushNewlyEnqueued().isEmpty())
        assertTrue(
            effects.flushCancelledExistingIds().isEmpty(),
            "Nothing was ever really launched — there's no real job for ScopeStore to cancel"
        )
    }

    @Test
    fun testCancelAllEffectsClearsBothOngoingAndNewlyEnqueued() {
        val ongoing = NamedEffect("ongoing")
        val effects = MutableEffectsState<String>(ongoing = listOf(1L to ongoing))
        effects.enqueue(NamedEffect("just enqueued"))

        effects.cancelAllEffects()

        assertTrue(effects.effects.isEmpty())
        assertTrue(effects.flushNewlyEnqueued().isEmpty())
        assertEquals(setOf(1L), effects.flushCancelledExistingIds())
    }

    @Test
    fun testPristineWalksBackThroughMap() {
        val original = NamedEffect("a")
        val mapped: Effect<Int> = original.map { it.length }

        assertEquals(original, mapped.pristine())
    }

    @Test
    fun testEffectsExposesThePristineTypeEvenWhenEnqueuedAsAMappedWrapper() {
        val effects = MutableEffectsState<Int>()

        effects.enqueue(NamedEffect("a").map { it.length })

        assertTrue(effects.effects.single() is NamedEffect, "effects.effects already resolves to the pristine type")
    }
}
