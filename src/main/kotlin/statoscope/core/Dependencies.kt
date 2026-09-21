package statoscope.core

/**
 * Resolves an injectable value by [InjectionKey], or an ancestor's live state by
 * [SuperstateKey]. Passed as the 4th argument to `update`.
 */
interface Dependencies {
    fun <T> resolve(key: InjectionKey<T>): T

    /**
     * Resolves [key] to whatever the ancestor that declared it (via a [SubScope.superstateKey])
     * currently holds. Throws if nothing on the path from the root down to this scope declared
     * [key] — there's no default to fall back to, unlike [InjectionKey].
     */
    fun <T> resolve(key: SuperstateKey<T>): T
}

/**
 * Builds a [Dependencies] from a flat list of [InjectionKey]-to-value overrides, falling back
 * to each key's own [InjectionKey.defaultValue] when not present. Public rather than `internal`
 * only so the separate `:testing` Gradle module can construct one directly (see
 * [StatoscopeInternalApi]) — app code should never touch this, [ScopeConfig.provide] plus
 * [statoscope.testing.TestPlan.withProvide] are the only entry points app/test code needs.
 */
@StatoscopeInternalApi
class MapDependencies(provide: List<Pair<InjectionKey<*>, Any?>>) : Dependencies {

    private val provided: Map<InjectionKey<*>, Any?> = provide.toMap()

    @Suppress("UNCHECKED_CAST")
    override fun <T> resolve(key: InjectionKey<T>): T =
        if (provided.containsKey(key)) provided[key] as T else key.defaultValue

    // A flat MapDependencies never has an ancestor of its own — [ScopeStoreImpl] is what wires
    // real SuperstateKey resolution when composing scopes (see ScopeStore.kt); reaching here at
    // all means no SubScope on the path to this scope declared this key.
    override fun <T> resolve(key: SuperstateKey<T>): T =
        error("No ancestor provides $key — declare it via a SubScope's superstateKey.")
}
