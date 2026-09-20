package statoscope.core

/**
 * Categories the built-in [StatoscopeLogger] can be switched on independently — ports Swift
 * Statoscope's `LogLevel` enum. `injection` isn't ported: Kotlin's [Dependencies.resolve] always
 * falls back to a key's own default rather than failing, so there's no ancestor-tree resolution
 * (or failure) to log — same simplification `statoscope-zustand` made for the same reason.
 */
enum class LogLevel {
    Errors,
    When,
    Effects,
    StateDiff,
    State
}

/** Predefined [LogLevel] combinations — mirrors Swift's `.basic`/`.all`/`.verbose` statics. */
object LogLevelSets {
    val none: Set<LogLevel> = emptySet()
    val basic: Set<LogLevel> = setOf(LogLevel.Errors, LogLevel.When)
    val all: Set<LogLevel> = setOf(LogLevel.Errors, LogLevel.When, LogLevel.Effects, LogLevel.StateDiff)
    val verbose: Set<LogLevel> = all + LogLevel.State
}
