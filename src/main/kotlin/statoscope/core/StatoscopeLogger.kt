package statoscope.core

/**
 * Global, process-wide logging switchboard — ports Swift Statoscope's `StatoscopeLogger`
 * (`Sources/Statoscope/Logging/StatoscopeLogger.swift`) and `statoscope-zustand`'s equivalent
 * (`src/logger.ts`). Not attached per-scope: every [statoscope.core.ScopeStore] created by
 * [createScope] logs into this same singleton, gated by [logLevel].
 *
 * Off by default except framework errors:
 * ```kotlin
 * StatoscopeLogger.logLevel = LogLevelSets.all
 * ```
 *
 * To redirect output — e.g. to Android's `Log.d` once this library grows an Android target,
 * or to capture lines in a test — replace the sink instead of switching levels:
 * ```kotlin
 * StatoscopeLogger.logReplacement = { level, message -> Log.d("Statoscope", message) }
 * ```
 * A non-null [logReplacement] receives every call regardless of [logLevel] — matching Swift's
 * `logEnabled`, which treats "a replacement is installed" as "everything is enabled"; the
 * replacement itself decides what to do with each level.
 */
object StatoscopeLogger {

    @Volatile
    var logLevel: Set<LogLevel> = setOf(LogLevel.Errors)

    @Volatile
    var logReplacement: ((LogLevel, String) -> Unit)? = null

    fun isEnabled(level: LogLevel): Boolean =
        logReplacement != null || logLevel.contains(level)

    /**
     * Logs [message] under [level], prefixed with [prefix] (typically a scope's [scopeLogPrefix]).
     * [message] is a lambda rather than a plain `String` so building it — which can mean
     * pretty-printing an entire state — is skipped entirely when neither a replacement nor
     * [level] itself is enabled.
     */
    fun log(level: LogLevel, prefix: String = "", message: () -> String) {
        // Logging must never change what the store does: a message that can't be built (a dump hitting something unreadable) is
        // reported as such, not thrown into the code that was only trying to log
        try {
            logUnguarded(level, prefix, message)
        } catch (error: Exception) {
            System.err.println(format(prefix, "⚠️ Could not build a log message: $error"))
        }
    }

    private fun logUnguarded(level: LogLevel, prefix: String, message: () -> String) {
        val replacement = logReplacement
        when {
            replacement != null -> replacement(level, format(prefix, message()))
            logLevel.contains(level) -> {
                val line = format(prefix, message())
                if (level == LogLevel.Errors) System.err.println(line) else println(line)
            }
        }
    }

    private fun format(prefix: String, message: String): String =
        if (prefix.isEmpty()) message else "$prefix $message"
}

/** `"<StateTypeName> (<identity hash>):"` — Kotlin's stand-in for Swift's `"Type (pointer):"`. */
fun scopeLogPrefix(initial: Any?, store: Any): String {
    val typeName = initial?.let { it::class.simpleName } ?: "Scope"
    return "$typeName (${Integer.toHexString(System.identityHashCode(store))}):"
}
