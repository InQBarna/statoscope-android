//
// StateDiffTest.kt
// statoscope-kotlin
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateDiffTest {

    @Test
    fun testIdenticalInputsProduceNoDiff() {
        val lines = listOf("a", "b", "c")
        assertEquals(emptyList(), diffLines(lines, lines))
    }

    @Test
    fun testOneChangedLineProducesOneRemovalAndOneAddition() {
        val before = listOf("Point(", "  x: 1", "  y: 2", ")")
        val after = listOf("Point(", "  x: 1", "  y: 3", ")")

        assertEquals(listOf("- ${"  y: 2"}", "+ ${"  y: 3"}"), diffLines(before, after))
    }

    @Test
    fun testAppendedLineProducesOnlyOneAddition() {
        val before = listOf("a", "b")
        val after = listOf("a", "b", "c")

        assertEquals(listOf("+ c"), diffLines(before, after))
    }

    @Test
    fun testRemovedLineProducesOnlyOneRemoval() {
        val before = listOf("a", "b", "c")
        val after = listOf("a", "c")

        assertEquals(listOf("- b"), diffLines(before, after))
    }

    @Test
    fun testEmptyBeforeAgainstNonEmptyAfterIsAllAdditions() {
        assertEquals(listOf("+ a", "+ b"), diffLines(emptyList(), listOf("a", "b")))
    }

    @Test
    fun testALargeDumpWithOneChangedLineDoesNotNeedAQuadraticTable() {
        val before = List(200_000) { "line $it" }
        val after = before.toMutableList().also { it[100_000] = "changed" }

        assertEquals(listOf("- line 100000", "+ changed"), diffLines(before, after))
    }

    @Test
    fun testTwoLargeDifferentDumpsStillDiffInLinearMemory() {
        val before = List(20_000) { "a $it" }
        val after = List(30_000) { "b $it" } + listOf("a 5")

        val diff = diffLines(before, after)

        assertEquals(20_000 - 1 + 30_000, diff.size)
        assertTrue(diff.contains("- a 7") && diff.contains("+ b 29999") && !diff.contains("- a 5"))
    }
}
