package statoscope.codegen

/**
 * Declares [State]'s annotated property as an ambient dependency, resolved from the injection
 * tree and re-stamped onto the property after every `update()` call — never resolved by hand
 * inside `update()` itself. [name] must be the exact name of a top-level `val` of type
 * `InjectionKey<T>` (`T` matching the property's own declared type), visible in the same file —
 * Kotlin annotations can only carry compile-time constants, never a reference to an arbitrary
 * `val`, so the name is how this one is found. The codegen stand-in for Swift's
 * `@ReducerInjected(RealLogger.self)`, which can reference the type directly instead — Swift's
 * macro reads raw source syntax, where `RealLogger.self` is just a type reference; Kotlin's
 * `InjectionKey` is a value, not a type, so there's nothing equivalent to point an annotation at.
 *
 * ```kotlin
 * val LoggerKey = InjectionKey(RealLogger(), "Logger")
 *
 * @Reducer
 * object MyReducer {
 *     data class State(
 *         @ReducerInjected("LoggerKey") val logger: Logger = LoggerKey.defaultValue,
 *         val count: Int = 0
 *     )
 *     ...
 * }
 * ```
 *
 * Prefer `dependencies.resolve(key)` inside `update()` instead when the value is only ever
 * needed there — this exists for the same reason Swift's does: ambient access across the whole
 * `State`, for rendering/display logic that isn't itself inside `update()`.
 *
 * The property's own declared default (`= LoggerKey.defaultValue` above) is what the state holds
 * until the first `update()` call ever runs on this store; resolved fresh after every call from
 * then on, not continuously live on every read (unlike Swift's, which re-injects on every
 * `store.state` access via a computed property). An `inject()`/`provide` change between two
 * events isn't reflected in an already-materialized `State` value until the next `update()` runs
 * — consistent with how every other injection mechanism in this library resolves at a state
 * transition, never polled continuously between them.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ReducerInjected(val name: String)
