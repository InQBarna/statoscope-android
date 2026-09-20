//
// StateDiffTest.kt
// statoscope-kotlin
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
