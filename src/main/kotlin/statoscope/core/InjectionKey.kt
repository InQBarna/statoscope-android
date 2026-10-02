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
 * [statoscope.testing.TestPlan.withProvide]. Resolution walks the ancestor chain: this scope's
 * own `provide` wins if present, otherwise the nearest ancestor's `provide` that declares this
 * key, all the way up to the root — [defaultValue] only applies once nothing on that whole path
 * overrides it. A composition root can provide a logger/network-client/date-provider once and
 * have it reach every descendant for free, the same way Swift Statoscope's `@Injected` walks its
 * own injection tree. See [Dependencies]'s own doc for the exact resolution order.
 */
class InjectionKey<T>(val defaultValue: T, val name: String? = null) {

    /** Convenience: builds a type-safe pair for [ScopeConfig.provide] / `withProvide`. */
    fun pair(value: T): Pair<InjectionKey<T>, T> = this to value

    override fun toString(): String = name ?: super.toString()
}
