package statoscope.testing

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks every [TestPlan] created but not yet run — the mechanism behind the "don't forget to
 * call `runTest()`" safety net. Ports the *guarantee* of Swift's `StoreTestPlan.deinit` (which
 * fails the test if `runTest()` was never called), not its mechanism: the JVM's GC has no
 * `deinit` equivalent and no deterministic collection timing, so this can't rely on
 * finalization the way Swift does. Instead, a [TestPlan] registers itself on creation and
 * deregisters at the start of [TestPlan.runTest] (whether or not that run's assertions actually
 * pass); [TestPlanLeakDetectionExtension] then checks what's left after every test method.
 */
internal object TestPlanRegistry {

    private val pending = ConcurrentHashMap.newKeySet<PendingTestPlan>()

    fun register(plan: PendingTestPlan) {
        pending += plan
    }

    fun unregister(plan: PendingTestPlan) {
        pending -= plan
    }

    /**
     * Fails loudly if any [TestPlan] created since the last call was never run. Called
     * automatically after every test method by [TestPlanLeakDetectionExtension]; exposed
     * separately so tests can exercise the registry itself without a real JUnit run.
     */
    fun failIfAnyPlanNeverRan() {
        if (pending.isEmpty()) return
        val descriptions = pending.joinToString("\n") { it.describeOrigin() }
        pending.clear()
        throw AssertionError("Don't forget to call runTest() on every TestPlan you create:\n$descriptions")
    }
}

/**
 * One [TestPlan]'s registration token. Captures the caller's stack at creation time (Kotlin has
 * no `#file`/`#line` default-argument equivalent) so a failure can point back at roughly where
 * the forgotten `given(...)` call was made.
 */
internal class PendingTestPlan {
    private val origin = Throwable()

    fun describeOrigin(): String =
        origin.stackTrace.firstOrNull { it.className !in infrastructureClassNames }?.toString()
            ?: "(unknown call site)"

    private companion object {
        // Exact class names, not a package prefix — an actual test class may well live in
        // statoscope.testing too, and must not be filtered out along with these.
        val infrastructureClassNames = setOf(
            PendingTestPlan::class.java.name,
            TestPlan::class.java.name,
            "statoscope.testing.TestPlanKt", // the file housing the top-level given() function
            StoreTestPlan::class.java.name,
            RootStoreTestPlan::class.java.name,
            WithStoreTestPlan::class.java.name,
            "statoscope.testing.StoreTestPlanKt" // the file housing the top-level givenStore() function
        )
    }
}
