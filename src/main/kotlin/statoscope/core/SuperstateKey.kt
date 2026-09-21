package statoscope.core

/**
 * A typed key an ancestor scope exposes a *live* read of its own state through, resolved via
 * [Dependencies.resolve] exactly like an [InjectionKey] — but where an [InjectionKey] resolves
 * to a value fixed at scope-creation time, a [SuperstateKey] resolves to whatever the ancestor's
 * state currently is, re-read fresh on every call. Declared by whichever reducer owns the state
 * a descendant needs, then wired up via [SubScope.superstateKey]; there's no
 * ancestor-independent default — resolving one with no ancestor providing it (nothing declared
 * it on the path from the root down to this scope) is a programmer error, not a fallback case.
 *
 * Ports `statoscope-zustand`'s `SuperstateKey<T>`. Deliberately a *live* read (a closure over
 * the real ancestor state, re-evaluated on every [Dependencies.resolve] call) rather than a
 * value snapshot copied in before each `update()` runs — unlike Swift Statoscope's own
 * `@SuperState`, whose snapshot-copy design has a documented staleness gotcha (reading through a
 * descendant that merely mirrors an ancestor's data, rather than the reducer that actually owns
 * it, can observe a value one step stale). A live read makes that whole bug class structurally
 * impossible here — see [SubScope] for how the read is wired down to a specific descendant.
 */
class SuperstateKey<T>(val name: String? = null) {
    override fun toString(): String = name ?: super.toString()
}
