package statoscope.codegen

/**
 * Marks a function on a [Reducer]-annotated object as the `onChildAction` for whichever
 * [SubState] property's child type matches its own `childState` parameter — matched by TYPE,
 * not by name, so the function can be called anything. `:codegen-processor` requires the exact
 * shape `(childWhen: ChildReducer.When, childState: ChildReducer.State, dependencies:
 * Dependencies) -> ParentReducer.When` (nullable or not — either satisfies
 * [statoscope.core.SubScope.onChildAction]'s nullable slot) and reports a real compile error,
 * pointing at the function, for any of:
 * - a shape that doesn't match (wrong parameter count/types/order, wrong return type)
 * - a `childState` type with no [SubState] property of that type anywhere on this reducer
 * - two `@OnChildAction` functions both matching the same child type
 *
 * "Multi-level" responsiveness — a grandparent reacting to a grandchild's event — needs the
 * intermediate reducer's own `@OnChildAction` to relay the child event *unchanged*, wrapped as an
 * associated value of its own `When`, and the level above matches on that wrapped value. This is
 * a genuine, deliberate divergence from Swift, not a port of its behavior: `MiddlewareReducer
 * .updateSubstate` there broadcasts the ORIGINAL, unmodified event to *every*
 * `MiddlewareReducer`-conforming ancestor at any depth (`callParentEnclosedHierarchialUpdate`/
 * `allHierarchialScopeMiddlewareParents` in `ScopeImplementation.swift`) — a grandparent sees the
 * grandchild's raw `When` type directly, no relay needed. Swift can do this because
 * `updateSubstate<Child: Reducer>` is one function, generic over `Child`, cast at runtime
 * (`childWhen as? SomeReducer.When`); Kotlin's `onChildAction` is typed per [SubState] property
 * instead, matched and validated by `:codegen-processor` at compile time (see below) — there's no
 * single generic entry point a broadcast could invoke uniformly the way Swift's can. Adopting
 * Swift's broadcast would mean widening `onChildAction` to something type-erased (`Any`/`is`
 * checks), giving up the compile-time validation this design was built for. Kept as one hop plus
 * explicit relay, on purpose — see `PARITY_AUDIT.md`'s entry on this for the fuller reasoning.
 *
 * This design — type-matched, KSP-validated functions rather than one name-matched or
 * signature-sniffed function — replaced an earlier version of this idea that matched by an
 * `on<PropertyName>Action` naming convention. That version failed *silently*: a typo in the name
 * just meant nothing was found, and the child's actions never reached the parent, with no error
 * at all. Type-based matching with real validation was built specifically to close that hole.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class OnChildAction
