//
// TestPlanRegistryTest.kt
// statoscope-kotlin
//
// Direct unit tests of the registry mechanism itself, isolated from the real JUnit extension
// integration (proven separately, by hand, with a deliberately-sabotaged test during
// development — see PARITY_AUDIT.md's Issue 5).
//
package statoscope.testing

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestPlanRegistryTest {

    @Test
    fun testFailsWhenAPendingPlanWasNeverUnregistered() {
        val plan = PendingTestPlan()
        TestPlanRegistry.register(plan)

        val error = assertFailsWith<AssertionError> { TestPlanRegistry.failIfAnyPlanNeverRan() }
        assertTrue(error.message?.contains("runTest()") == true)
    }

    @Test
    fun testDoesNothingWhenEveryPendingPlanWasUnregistered() {
        val plan = PendingTestPlan()
        TestPlanRegistry.register(plan)
        TestPlanRegistry.unregister(plan)

        TestPlanRegistry.failIfAnyPlanNeverRan() // must not throw
    }

    @Test
    fun testFailingClearsTheRegistrySoItDoesNotAlsoFailTheNextCheck() {
        TestPlanRegistry.register(PendingTestPlan())
        assertFailsWith<AssertionError> { TestPlanRegistry.failIfAnyPlanNeverRan() }

        TestPlanRegistry.failIfAnyPlanNeverRan() // must not throw a second time
    }
}
