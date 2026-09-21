package statoscope.core

/**
 * Declares that [ParentState] owns, at any given moment, at most one live child scope — a
 * [ChildState] field that's `null` when the child doesn't exist and non-null while it does.
 * [ScopeStore] watches this field after every [ParentState] update: `null → non-null` creates a
 * real nested [ScopeStore] (seeded with the exact value just written by [ParentState]'s own
 * `update()`, not [config]'s own `initial` — see [config]'s doc); `non-null → null` tears it
 * down. Ports `statoscope-zustand`'s `SubScopeDescriptor`/`ScopeConfig.subScopes` — this
 * project's runtime-config equivalent of Swift Statoscope's `@SubState`-annotated property plus
 * its macro-generated child-lifecycle wiring (no macro/codegen exists here, by design — see
 * [Reducer]'s doc).
 *
 * [get]/[set] are a lens into [ParentState] rather than a `KProperty1`, because [ParentState] is
 * expected to be an immutable `data class`: creating/destroying/updating the child field means
 * producing a new parent state via its own generated `copy(...)`, which only code written
 * against the concrete type can do — passed in explicitly here rather than reached for through
 * reflection.
 *
 * [onChildAction] is how a parent reacts to something that happened in the child — ports Swift's
 * `MiddlewareReducer.updateSubstate`. It always runs *after* the child's own `update()`: the
 * action is forwarded to the child unconditionally first, exactly like
 * `Sources/Statoscope/Reducer/Store.swift`'s `updateSubscope` — there is no way to intercept or
 * veto it, and none is needed. So [childState] here is already the child's *new* state, not the
 * state it had when the action arrived — the class of bug where a parent's own reaction reads a
 * child's about-to-be-stale state is structurally impossible by this ordering, not just
 * mitigated. Returning `null` means "no reaction"; returning a [ParentWhen] sends it through the
 * parent's own `update()`, same as any other event.
 *
 * There is deliberately no way for a parent to inject an action *into* a child — only to react
 * after the fact, or to replace the child outright by writing a new [ChildState] through [set].
 * Ported as a hard constraint from `statoscope-zustand`, which removed an earlier
 * `onParentAction` for the same reason: a parent reaching into a child's own action stream
 * breaks the child's ability to reason about its own state changes in isolation.
 *
 * [superstateKey] optionally lets the child — and everything nested under it, transitively —
 * read this parent's own state live via [Dependencies.resolve]; see [SuperstateKey].
 */
class SubScope<ParentState, ParentWhen, ChildState, ChildWhen>(
    /**
     * The child's own configuration. Its `initial` is only ever a placeholder — a real child is
     * always created via `config.copy(initial = <the value [ParentState]'s own update() just
     * wrote>)`, never with this value. [ScopeConfig] has no way to express "no default," so a
     * harmless placeholder is the cost of reusing the same type rather than inventing a second,
     * initial-less config type just for this.
     */
    val config: ScopeConfig<ChildState, ChildWhen>,
    val get: (ParentState) -> ChildState?,
    val set: (ParentState, ChildState?) -> ParentState,
    val onChildAction: ((childWhen: ChildWhen, childState: ChildState, dependencies: Dependencies) -> ParentWhen?)? = null,
    val superstateKey: SuperstateKey<ParentState>? = null
)
