package statoscope.core

/** Resolves an injectable value by [InjectionKey]. Passed as the 4th argument to `update`. */
interface Dependencies {
    fun <T> resolve(key: InjectionKey<T>): T
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
}
