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
 * Reacts to an event from **any** descendant, at **any** depth — not just a direct [SubScope]
 * child (that's [SubScope.onChildAction], typed per child and KSP-validated). Ports Swift
 * Statoscope's `MiddlewareReducer.updateSubstate<Child: Reducer>` directly, generics included:
 * Swift's `Child` type parameter (cast at the call site via `childWhen as? SomeReducer.When`)
 * becomes [descendantWhen]/[descendantState] typed as `Any`, matched the same way inside the
 * handler body (`descendantWhen is SomeReducer.When`) — Kotlin has no equivalent of a single
 * generic function dispatched per concrete type at the call site, so runtime `is`/`as` checks are
 * the direct, idiomatic port rather than a workaround.
 *
 * Runs *after* the descendant's own `update()` — [descendantState] is already that descendant's
 * post-update state, exactly like Swift's `childState` parameter — so a reaction can safely read
 * it, and even go on to destroy/replace that subtree, without racing a stale event still being
 * delivered into it. [parentState] is this reducer's own current state, read-only — any reaction
 * has to go through the returned `When?`, which lands in this reducer's own `update()`, the only
 * place `State` changes. Return `null` for no reaction.
 *
 * See [SubScope.onChildAction] for the direct-child, compile-time-validated equivalent — reach
 * for this only when a reducer needs to see further than one level down; the two mechanisms are
 * independent and both can fire for the same event (see `ScopeStoreImpl.notifyAncestors`'s own
 * doc for the exact ordering).
 */
typealias OnDescendantAction<State, When> =
    (descendantWhen: Any, descendantState: Any, parentState: State, dependencies: Dependencies) -> When?

/**
 * Configuration for a scope: its initial state, its [Reducer], any [InjectionKey] overrides for
 * that scope, any [SubScope]s it composes with (Tutorial 05) — child scopes it creates, mirrors,
 * and tears down as fields of its own [State] go non-null/null — and an optional
 * [OnDescendantAction] for reacting to any descendant's event at any depth.
 */
data class ScopeConfig<State, When>(
    val initial: State,
    val update: Reducer<State, When>,
    val provide: List<Pair<InjectionKey<*>, Any?>> = emptyList(),
    val subScopes: List<SubScope<State, When, *, *>> = emptyList(),
    val onDescendantAction: OnDescendantAction<State, When>? = null
)
