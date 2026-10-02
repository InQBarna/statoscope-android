package statoscope.core

/**
 * Resolves an injectable value by [InjectionKey], or an ancestor's live state by
 * [SuperstateKey]. Passed as the 4th argument to `update`.
 *
 * [InjectionKey] resolution walks the ancestor chain: a scope's own [ScopeConfig.provide] wins if
 * present, otherwise resolution falls through to whatever the nearest ancestor that declared an
 * override provides, all the way up to the root — only once nothing on that whole path overrides
 * it does [InjectionKey.defaultValue] apply. Ports Swift Statoscope's own ancestor-tree injection
 * walk (`InjectionTreeNode._resolve()` climbing `_parentNode`) directly. [SuperstateKey]
 * resolution is unrelated and unchanged: it still requires an explicit [SubScope.superstateKey]
 * declaration on the path, with no key-level default to fall back to.
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
 *
 * This class's own [resolve] never consults an ancestor — it's the flat, single-scope building
 * block the ancestor walk is built *from*, not the thing that performs the walk (see
 * `ScopeStoreImpl.CombinedDependencies` in `ScopeStore.kt`, which uses [hasOwnProvide] to decide
 * whether to use this scope's own value or fall through to an ancestor first). [statoscope.testing.TestPlan]
 * also constructs one directly as a reducer's entire [Dependencies] when testing it in isolation
 * (no real ancestor chain exists there at all), where falling straight back to
 * [InjectionKey.defaultValue] with no ancestor consulted is exactly the desired, documented
 * behavior — this class's contract doesn't change for that caller.
 */
@StatoscopeInternalApi
class MapDependencies(provide: List<Pair<InjectionKey<*>, Any?>>) : Dependencies {

    private val provided: Map<InjectionKey<*>, Any?> = provide.toMap()

    /** Whether *this* scope's own `provide` list overrides [key] — not whether any ancestor does. */
    internal fun hasOwnProvide(key: InjectionKey<*>): Boolean = provided.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun <T> resolve(key: InjectionKey<T>): T =
        if (provided.containsKey(key)) provided[key] as T else key.defaultValue

    // A flat MapDependencies never has an ancestor of its own — [ScopeStoreImpl] is what wires
    // real SuperstateKey resolution when composing scopes (see ScopeStore.kt); reaching here at
    // all means no SubScope on the path to this scope declared this key.
    override fun <T> resolve(key: SuperstateKey<T>): T =
        error("No ancestor provides $key — declare it via a SubScope's superstateKey.")
}
