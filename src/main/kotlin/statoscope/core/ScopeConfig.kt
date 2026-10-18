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
 * and tears down as fields of its own [State] go non-null/null — an optional
 * [OnDescendantAction] for reacting to any descendant's event at any depth, and an optional
 * [defaultTrigger] dispatched automatically, once, when a scope using this config is activated
 * as a child (see that property's own doc).
 */
data class ScopeConfig<State, When>(
    val initial: State,
    val update: Reducer<State, When>,
    val provide: List<Pair<InjectionKey<*>, Any?>> = emptyList(),
    val subScopes: List<SubScope<State, When, *, *>> = emptyList(),
    val onDescendantAction: OnDescendantAction<State, When>? = null,
    /**
     * Declares that this scope has a *live* dependency on [SuperstateKey] — paired with
     * [deriveFromLiveAncestor], which re-derives this scope's own [State] from it. Together
     * these replace the old, documented-as-stale approach of folding a [SuperstateKey] resolve
     * into [update]'s own wrapper (still what `@ReducerInjected` does for [InjectionKey]s, since
     * those don't change on their own the way an ancestor's state does): instead of waiting for
     * this scope's *own* next `update()` to catch up, [ScopeStoreImpl] pushes a fresh value the
     * moment the ancestor that owns [SuperstateKey] commits ANY new state of its own — including
     * one caused by this scope's own event delegating upward through [SubScope.onChildAction],
     * which is exactly the case the old approach permanently lagged on (see `@ParentState`'s own
     * doc, written before this field existed, for the device-confirmed bug this closes).
     *
     * Both this and [deriveFromLiveAncestor] are generated together by `@ParentState` — there is
     * no supported hand-written use outside codegen today, though nothing here is codegen-specific.
     */
    val liveAncestorKey: SuperstateKey<*>? = null,
    /**
     * Re-derives this scope's own [State] from fresh [Dependencies] — called by
     * [ScopeStoreImpl], never by [update] — whenever [liveAncestorKey]'s owner commits a new
     * state, and once more immediately at activation, before this scope has processed a single
     * event of its own. Expected to do exactly one thing: `state.copy(theAnnotatedField =
     * dependencies.resolve(liveAncestorKey))` — anything reading [liveAncestorKey] through
     * [dependencies] here always gets the owner's true current value, never a cycle-stale one.
     *
     * `null` whenever [liveAncestorKey] is `null` — there's nothing to derive.
     */
    val deriveFromLiveAncestor: ((State, Dependencies) -> State)? = null,
    /**
     * An event dispatched automatically, exactly once, right after a scope built from this
     * config is activated as a child (see [ScopeStoreImpl.activateChild]) — ports Swift's
     * `Reducer.defaultTrigger` (`Reducer.swift`): `static var defaultTrigger: When? { .onAppear }`.
     * The common "fetch as soon as this scope exists" case this replaces: before this existed,
     * the UI layer had to send that first event itself (a Compose
     * `LaunchedEffect(listStore) { listStore.send(...) }` was the usual way) — a responsibility
     * that belongs to the reducer declaring what it needs on creation, not to whichever UI
     * happens to render it.
     *
     * Deliberately a *fixed* value, not a function of [dependencies][Dependencies] or anything
     * else — if the trigger's own handling needs to resolve something, it does that the normal
     * way, inside its own `update()` case, exactly like any other event.
     *
     * Fires for every activation, not just the ones reached through a normal one-field-at-a-time
     * `update()` transition — including a child that arrives already non-null as part of a
     * parent's own initial construction (see [ScopeStoreImpl]'s init-time activation, Issue 10 in
     * the parity review) — a deliberate choice, not a strict port of Swift's own recursive
     * `triggerDefaults: false` suppression for that case: "this child now exists" is true either
     * way, and firing uniformly is simpler to reason about than firing only sometimes depending
     * on how the child came to exist. Never fires for a plain [createScope] root (nothing to be
     * "activated as a child" of) — call [ScopeStore.send] yourself right after construction for
     * that, the same way an app always could.
     */
    val defaultTrigger: When? = null
)
