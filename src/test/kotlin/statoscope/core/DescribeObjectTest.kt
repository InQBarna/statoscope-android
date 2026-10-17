//
// DescribeObjectTest.kt
// statoscope-kotlin
//
package statoscope.core

import kotlin.test.Test
import kotlin.test.assertEquals

private data class Point(val x: Int, val y: Int)
private data class Named(val name: String, val point: Point)
private sealed interface Shape {
    data object Empty : Shape
    data class Circle(val radius: Int) : Shape
}

class DescribeObjectTest {

    @Test
    fun testPrimitivesDescribeAsThemselves() {
        assertEquals("null", describeObject(null))
        assertEquals("42", describeObject(42))
        assertEquals("true", describeObject(true))
        assertEquals("\"hi\"", describeObject("hi"))
    }

    @Test
    fun testDataClassDescribesOneFieldPerLine() {
        assertEquals(
            "Point(\n  x: 1\n  y: 2\n)",
            describeObject(Point(1, 2))
        )
    }

    @Test
    fun testNestedDataClassIndentsFurther() {
        assertEquals(
            "Named(\n  name: \"a\"\n  point: Point(\n    x: 1\n    y: 2\n  )\n)",
            describeObject(Named("a", Point(1, 2)))
        )
    }

    @Test
    fun testDataObjectWithNoFieldsIsJustItsTypeName() {
        assertEquals("Empty", describeObject(Shape.Empty))
    }

    @Test
    fun testSealedInterfaceDataClassCaseDescribesLikeAnyDataClass() {
        assertEquals("Circle(\n  radius: 3\n)", describeObject(Shape.Circle(3)))
    }

    @Test
    fun testEmptyListDescribesAsEmptyBrackets() {
        assertEquals("[]", describeObject(emptyList<Int>()))
    }

    @Test
    fun testListDescribesOneElementPerLine() {
        assertEquals("[\n  1\n  2\n]", describeObject(listOf(1, 2)))
    }

    @Test
    fun testListOfDataClassesIndentsEachElement() {
        assertEquals(
            "[\n  Point(\n    x: 1\n    y: 2\n  )\n]",
            describeObject(listOf(Point(1, 2)))
        )
    }

    @Test
    fun testChangingOneFieldChangesExactlyOneLine() {
        val before = describeObject(Point(1, 2)).lines()
        val after = describeObject(Point(1, 3)).lines()

        assertEquals(before.size, after.size, "field count shouldn't change")
        val differingLines = before.indices.count { before[it] != after[it] }
        assertEquals(1, differingLines, "only the y field's line should differ")
    }

    private class Node(var next: Node? = null, val label: String = "n")

    private class Unreadable {
        val fine = 1
        val broken: Int get() = error("boom")
    }

    @Test
    fun testACycleIsCutInsteadOfOverflowingTheStack() {
        val a = Node(label = "a")
        a.next = Node(next = a, label = "b")
        val text = describeObject(a)
        assert(text.contains("<cycle: Node>")) { text }
    }

    @Test
    fun testAVeryDeepStructureIsElided() {
        var chain = Node(label = "end")
        repeat(100) { chain = Node(chain, "n") }
        assert(describeObject(chain).contains("too deep"))
    }

    @Test
    fun testAPropertyThatThrowsIsReportedAndTheRestStillPrints() {
        val text = describeObject(Unreadable())
        assert(text.contains("fine: 1")) { text }
        assert(text.contains("broken: <unreadable:")) { text }
    }

    @Test
    fun testANonKotlinObjectPrintsAsItsToStringWithoutWalkingItsFields() {
        assertEquals("a-b", describeObject(StringBuilder("a-b")))
    }
}
