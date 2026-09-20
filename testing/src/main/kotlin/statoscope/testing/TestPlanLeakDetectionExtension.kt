package statoscope.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Auto-registered (see `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
 * and `src/test/resources/junit-platform.properties`) so every test in this module gets the
 * "don't forget to call runTest()" check for free, with no per-test-class opt-in — the same way
 * Swift's equivalent `deinit` check applies automatically to every `StoreTestPlan` without the
 * test author doing anything.
 */
class TestPlanLeakDetectionExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        TestPlanRegistry.failIfAnyPlanNeverRan()
    }
}
