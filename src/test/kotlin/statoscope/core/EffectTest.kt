package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests map()/pristine()/completePristine() directly — the chain-composition machinery itself,
// independent of any ScopeStore/TestPlan machinery built on top of it (that's ScopeStoreTest/
// TestPlanTest/StoreTestPlanCompositionTest, which only ever see completeEffect/onEffectCompletes).
@OptIn(StatoscopeInternalApi::class)
class EffectTest {

    private class RawFetch(private val value: Int) : Effect<Int> {
        override suspend fun run(): Int = value
    }

    @Test
    fun testUnmappedEffectPristineIsItself() {
        val effect = RawFetch(1)
        assertTrue(effect.pristine() === effect)
    }

    @Test
    fun testCompletePristineOnAnUnmappedEffectReturnsTheValueDirectly() {
        val effect = RawFetch(1)
        assertEquals(42, effect.completePristine(42))
    }

    @Test
    fun testSingleMapIsReplayedFromThePristineRawValue() {
        val effect = RawFetch(1).map { it.toString() }
        assertEquals("99", effect.completePristine(99))
    }

    @Test
    fun testChainedMapsAreBothReplayedInOrder() {
        val effect = RawFetch(1)
            .map { it.toString() }
            .map { "value=$it" }

        assertEquals("value=7", effect.completePristine(7))
    }

    @Test
    fun testPristineWalksBackThroughAnyNumberOfChainedMaps() {
        val raw = RawFetch(1)
        val mapped = raw.map { it.toString() }.map { "value=$it" }

        assertTrue(mapped.pristine() === raw)
    }

    @Test
    fun testCompletePristineNeverCallsTheRealRun() {
        var ran = false
        val effect = (object : Effect<Int> {
            override suspend fun run(): Int {
                ran = true
                return -1
            }
        }).map { it.toString() }

        assertEquals("5", effect.completePristine(5))
        assertTrue(!ran, "completePristine must never call the real run()")
    }
}
