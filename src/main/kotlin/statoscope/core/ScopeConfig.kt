package statoscope.core

/**
 * The pure function that turns one [State] plus one [When] event into the next [State] — the
 * actual "reducer," in the Redux sense the whole pattern is named after. Named as its own type
 * (not just an inline lambda on [ScopeConfig.update]) so a Swift-background reader has
 * something to recognize by name: Swift's own central concept is a `@Reducer`-annotated struct
 * (`Reducer/MiddlewareReducer`/`ReducerDependencies` throughout its codebase). Kotlin has no
 * macro generating a `Store<R>` from a declared type the way Swift's `@Reducer` does — this
 * project follows `statoscope-zustand`'s runtime-config-object approach instead (no
 * codegen/KSP) — so there's no analogous *declared* Reducer type to conform to, only this
 * function shape, bundled into a [ScopeConfig] alongside the scope's initial state and any
 * [InjectionKey] overrides.
 *
 * Returns a new state rather than mutating one in place — Kotlin has no equivalent to Swift's
 * `inout`, so a `State` is expected to be an immutable `data class`, updated via `copy()`.
 *
 * This signature has grown twice already as new tutorials introduced capabilities every
 * reducer needs to at least accept, even unused — [EffectsState] (Tutorial 02) and
 * [Dependencies] (Tutorial 04). Both are present from the start now; a reducer that doesn't
 * need one simply ignores that parameter (see Tutorial 01's `counterUpdate`). This isn't a
 * guarantee the signature can never grow again — it's a pattern for handling it when it does:
 * add the parameter, retrofit existing examples to accept (and ignore) it, and say so in their
 * docs rather than silently changing them.
 */
typealias Reducer<State, When> = (state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies) -> State

/**
 * Configuration for a scope: its initial state, its [Reducer], and any [InjectionKey]
 * overrides for that scope.
 */
data class ScopeConfig<State, When>(
    val initial: State,
    val update: Reducer<State, When>,
    val provide: List<Pair<InjectionKey<*>, Any?>> = emptyList()
)
