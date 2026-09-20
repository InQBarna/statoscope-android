package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Tests MutableEffectsState directly, the internal implementation itself — not app-facing usage
// (that's ScopeStoreTest/tutorial examples, which only ever see the public EffectsState
// interface). A legitimate same-module reason to opt in, same as ScopeStoreImpl itself.
@OptIn(StatoscopeInternalApi::class)
class EffectsStateTest {

    private class NamedEffect(private val name: String) : Effect<String> {
        override suspend fun run(): String = name
        override fun toString(): String = name
    }

    @Test
    fun testFlushReturnsEnqueuedEffectsInOrder() {
        val effects = MutableEffectsState<String>()
        val first = NamedEffect("a")
        val second = NamedEffect("b")

        effects.enqueue(first)
        effects.enqueue(second)

        assertEquals(listOf(first, second), effects.flush())
    }

    @Test
    fun testFlushClearsState() {
        val effects = MutableEffectsState<String>()
        effects.enqueue(NamedEffect("a"))

        effects.flush()

        assertEquals(emptyList(), effects.flush(), "A second flush must not re-return already-flushed effects")
    }
}
