package statoscope.core

/**
 * A typed key that carries a default value — ports `statoscope-zustand`'s `InjectionKey<T>`
 * (itself modeled on Swift Statoscope's `Injectable` protocol).
 *
 * Declare one per injectable service:
 * ```kotlin
 * val DateProviderKey = InjectionKey(DateProvider { Date() }, "DateProvider")
 * ```
 * Override at scope-creation time via [ScopeConfig.provide], or in a test via
 * [statoscope.testing.TestPlan.withProvide]. Resolution: own `provide` → the key's
 * [defaultValue]. (No ancestor-chain resolution yet — that's future work, alongside scope
 * composition.)
 */
class InjectionKey<T>(val defaultValue: T, val name: String? = null) {

    /** Convenience: builds a type-safe pair for [ScopeConfig.provide] / `withProvide`. */
    fun pair(value: T): Pair<InjectionKey<T>, T> = this to value

    override fun toString(): String = name ?: super.toString()
}
