package statoscope.core

import java.lang.reflect.Modifier

private const val INDENT = "  "

/**
 * Recursive, multi-line pretty-printer for state/event dumps — the Kotlin stand-in for Swift's
 * `Mirror`-based `describeObject` (`Scope+DebugDescription.swift`). Kotlin has no `Mirror`, so
 * this walks [kotlin.reflect] member properties instead; the point either way is the same: one
 * field per line, so [diffLines] can show a single changed field as a single changed line
 * instead of an opaque single-line `toString()`.
 *
 * A `data object` case (no properties) prints as just its type name, matching how Swift prints
 * a parameterless `enum` case.
 */
fun describeObject(value: Any?, indent: String = ""): String = describe(value, indent, DescribeContext())

/** Deepest nesting [describeObject] prints before eliding the rest. */
private const val MAX_DEPTH = 24

/** The objects currently being described (cycle guard, by identity) and how deep the walk is. */
private class DescribeContext(val path: MutableList<Any> = mutableListOf()) {
    val depth: Int get() = path.size
    fun isOnPath(value: Any) = path.any { it === value }
}

private fun describe(value: Any?, indent: String, context: DescribeContext): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Boolean, is Number -> value.toString()
    else -> when {
        context.isOnPath(value) -> "<cycle: ${value.javaClass.simpleName}>"
        context.depth >= MAX_DEPTH -> "<${value.javaClass.simpleName}: too deep>"
        else -> {
            context.path.add(value)
            try {
                when (value) {
                    is Map<*, *> -> describeMap(value, indent, context)
                    is Collection<*> -> describeCollection(value, indent, context)
                    else -> describeReflective(value, indent, context)
                }
            } finally {
                context.path.removeAt(context.path.lastIndex)
            }
        }
    }
}

private fun describeCollection(value: Collection<*>, indent: String, context: DescribeContext): String {
    if (value.isEmpty()) return "[]"
    val childIndent = indent + INDENT
    val items = value.joinToString("\n") { childIndent + describe(it, childIndent, context) }
    return "[\n$items\n$indent]"
}

private fun describeMap(value: Map<*, *>, indent: String, context: DescribeContext): String {
    if (value.isEmpty()) return "{}"
    val childIndent = indent + INDENT
    val items = value.entries.joinToString("\n") { (key, mapValue) ->
        "$childIndent$key: ${describe(mapValue, childIndent, context)}"
    }
    return "{\n$items\n$indent}"
}

/**
 * Types that aren't Kotlin (Firebase, Android, JDK objects held by an effect or a dependency) are not walked: their fields are
 * implementation detail, huge, cyclic, and often unreadable by reflection. They print as their `toString()`.
 */
private fun isKotlinType(value: Any): Boolean = value.javaClass.isAnnotationPresent(Metadata::class.java)

/**
 * Java reflection on the declared fields, not kotlin-reflect's `memberProperties`: that one builds descriptors for EVERY member of the
 * class and its supertypes (functions included) before returning, which on a device took seconds and hundreds of MB for the types an
 * effect holds, freezing the main thread inside a log call. It also ran computed getters (a `val firestore get() = ...`). Fields are a
 * constructor's properties and their stored state, which is all a state dump is for. Sorted by name, as before, so the lines of two
 * dumps of one type line up for [diffLines].
 */
private fun describeReflective(value: Any, indent: String, context: DescribeContext): String {
    if (!isKotlinType(value)) return safely { value.toString() }
    val typeName = value.javaClass.simpleName.ifEmpty { "Unknown" }
    val fields = try {
        generateSequence<Class<*>>(value.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }
            .sortedBy { it.name }
            .toList()
    } catch (error: Throwable) {
        return "$typeName(<unreadable: $error>)"
    }
    if (fields.isEmpty()) return typeName

    val childIndent = indent + INDENT
    val lines = fields.joinToString("\n") { field ->
        val text = try {
            field.isAccessible = true
            describe(field.get(value), childIndent, context)
        } catch (error: Throwable) {
            "<unreadable: $error>"
        }
        "$childIndent${field.name}: $text"
    }
    return "$typeName(\n$lines\n$indent)"
}

private inline fun safely(block: () -> String): String = try {
    block()
} catch (error: Throwable) {
    "<unreadable: $error>"
}
