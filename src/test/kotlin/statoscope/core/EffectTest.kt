package statoscope.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private class Failing(private val error: Throwable) : Effect<Int> {
        override suspend fun run(): Int = throw error
    }

    @Test
    fun testMapToResultWrapsASuccessfulRunInResultSuccess() = runTest {
        val effect = RawFetch(1).mapToResult()
        assertEquals(Result.success(1), effect.run())
    }

    @Test
    fun testMapToResultWrapsAThrownExceptionInResultFailure() = runTest {
        val boom = IllegalStateException("boom")
        val effect = Failing(boom).mapToResult()
        assertEquals(Result.failure<Int>(boom), effect.run())
    }

    @Test
    fun testMapToResultComposesWithAPriorMap() = runTest {
        val effect = RawFetch(1).map { it.toString() }.mapToResult()
        assertEquals(Result.success("1"), effect.run())
    }

    @Test
    fun testMapToResultNeverSwallowsCancellation() = runTest {
        val effect = Failing(CancellationException("cancelled")).mapToResult()
        assertFailsWith<CancellationException> { effect.run() }
    }

    @Test
    fun testMapToResultPristineWalksBackThroughTheWrapper() {
        val raw = RawFetch(1)
        val mapped = raw.mapToResult()
        assertTrue(mapped.pristine() === raw)
    }

    @Test
    fun testMapToResultReplaysFromPristineAsASuccessWithoutCallingRun() {
        var ran = false
        val effect = (object : Effect<Int> {
            override suspend fun run(): Int {
                ran = true
                return -1
            }
        }).mapToResult()

        assertEquals(Result.success(5), effect.completePristine(5))
        assertTrue(!ran, "completePristine must never call the real run()")
    }

    private enum class FetchError { NETWORK }

    @Test
    fun testMapToResultWithErrorMapperWrapsASuccessInEffectResultSuccess() = runTest {
        val effect = RawFetch(1).mapToResult { FetchError.NETWORK }
        assertEquals(EffectResult.Success(1), effect.run())
    }

    @Test
    fun testMapToResultWithErrorMapperAppliesTheMapperOnFailure() = runTest {
        val effect = Failing(IllegalStateException("boom")).mapToResult { FetchError.NETWORK }
        assertEquals(EffectResult.Failure(FetchError.NETWORK), effect.run())
    }

    @Test
    fun testMapToResultWithErrorMapperNeverSwallowsCancellation() = runTest {
        val effect = Failing(CancellationException("cancelled")).mapToResult { FetchError.NETWORK }
        assertFailsWith<CancellationException> { effect.run() }
    }

    // MARK: - completePristineFailure (simulating a pristine effect's FAILURE, not its success)

    @Test
    fun testCompletePristineFailureOnAnUnmappedEffectRethrowsDirectly() {
        val boom = IllegalStateException("boom")
        val effect = RawFetch(1)
        val error = assertFailsWith<IllegalStateException> { effect.completePristineFailure(boom) }
        assertTrue(error === boom, "nothing here catches anything — the exact same error must propagate")
    }

    @Test
    fun testCompletePristineFailurePropagatesUncaughtThroughAnyNumberOfPlainMaps() {
        val boom = IllegalStateException("boom")
        val effect = RawFetch(1).map { it.toString() }.map { "value=$it" }
        val error = assertFailsWith<IllegalStateException> { effect.completePristineFailure(boom) }
        assertTrue(error === boom, "a plain map() never catches — it must rethrow unchanged, same as a real run() would")
    }

    @Test
    fun testCompletePristineFailureNeverCallsTheRealRun() {
        var ran = false
        val effect = (object : Effect<Int> {
            override suspend fun run(): Int {
                ran = true
                return -1
            }
        }).map { it.toString() }

        assertFailsWith<IllegalStateException> { effect.completePristineFailure(IllegalStateException("boom")) }
        assertTrue(!ran, "completePristineFailure must never call the real run()")
    }

    @Test
    fun testCompletePristineFailureThroughMapToResultIsCaughtAsResultFailure() {
        val boom = IllegalStateException("boom")
        val effect = RawFetch(1).mapToResult()
        assertEquals(Result.failure<Int>(boom), effect.completePristineFailure(boom))
    }

    @Test
    fun testCompletePristineFailureThroughMapToResultWithErrorMapperAppliesTheMapper() {
        val effect = RawFetch(1).mapToResult { FetchError.NETWORK }
        assertEquals(EffectResult.Failure(FetchError.NETWORK), effect.completePristineFailure(IllegalStateException("boom")))
    }

    // The real point of the recursive design, not just "mapToResult catches, map rethrows" in
    // isolation: once something DOES catch, whatever's layered ON TOP of it treats the caught
    // value as an ORDINARY success to transform — exactly like a real run() where the exception
    // never escapes past the first catcher, so anything above it just sees a plain value.
    @Test
    fun testCompletePristineFailureThroughAMapLayeredOnTopOfMapToResultTransformsTheCaughtResult() {
        val boom = IllegalStateException("boom")
        val effect = RawFetch(1).mapToResult().map { result -> result.isFailure }
        assertEquals(true, effect.completePristineFailure(boom))
    }
}
