package statoscope.core

import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

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
fun describeObject(value: Any?, indent: String = ""): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> describeMap(value, indent)
    is Collection<*> -> describeCollection(value, indent)
    else -> describeReflective(value, indent)
}

private fun describeCollection(value: Collection<*>, indent: String): String {
    if (value.isEmpty()) return "[]"
    val childIndent = indent + INDENT
    val items = value.joinToString("\n") { childIndent + describeObject(it, childIndent) }
    return "[\n$items\n$indent]"
}

private fun describeMap(value: Map<*, *>, indent: String): String {
    if (value.isEmpty()) return "{}"
    val childIndent = indent + INDENT
    val items = value.entries.joinToString("\n") { (key, mapValue) ->
        "$childIndent$key: ${describeObject(mapValue, childIndent)}"
    }
    return "{\n$items\n$indent}"
}

private fun describeReflective(value: Any, indent: String): String {
    val kClass = value::class
    val typeName = kClass.simpleName ?: "Unknown"
    val properties = kClass.memberProperties.toList()
    if (properties.isEmpty()) return typeName

    val childIndent = indent + INDENT
    val fields = properties.joinToString("\n") { property ->
        property.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val propertyValue = (property as kotlin.reflect.KProperty1<Any, *>).get(value)
        "$childIndent${property.name}: ${describeObject(propertyValue, childIndent)}"
    }
    return "$typeName(\n$fields\n$indent)"
}
