package statoscope.codegen

/**
 * Marks a function on a [Reducer]-annotated object as its [statoscope.core.ScopeConfig.onDescendantAction]
 * — the codegen counterpart to Swift's `MiddlewareReducer.updateSubstate<Child: Reducer>`, and the
 * broadcast-reach escape hatch [OnChildAction] deliberately doesn't cover (see that annotation's
 * own doc): this reaches *every* descendant at *any* depth, not just a direct [SubState] child,
 * with zero relay needed at any intermediate level.
 *
 * Unlike [OnChildAction] (matched per child type, one function per [SubState] property),
 * `:codegen-processor` requires the exact, type-erased shape
 * `(descendantWhen: Any, descendantState: Any, parentState: ThisReducer.State, dependencies:
 * Dependencies) -> ThisReducer.When?` — matching [statoscope.core.OnDescendantAction]'s own
 * typealias exactly, so the generated `ThisReducer::functionName` reference satisfies
 * [statoscope.core.ScopeConfig.onDescendantAction] directly, no wrapping lambda needed. The first
 * two parameters are `Any` on purpose: a descendant can be any reducer at any depth, so there's no
 * single static type to declare — match the real kind inside the body with `is`/`as`, exactly like
 * Swift's own `childWhen as? SomeReducer.When` inside `updateSubstate`.
 *
 * `:codegen-processor` reports a real compile error, pointing at the function, for any of:
 * - a shape that doesn't match (wrong parameter count, wrong parameter types/order, wrong return type)
 * - a second `@OnDescendantAction` function on the same reducer — only one is allowed, since
 *   [statoscope.core.ScopeConfig.onDescendantAction] is a single nullable slot, not one per
 *   descendant type the way [OnChildAction] is one per child
 *
 * Does not replace [OnChildAction] — a reducer can declare both: [OnChildAction] for a specific,
 * compile-time-validated direct child reaction, [OnDescendantAction] for the broader "see
 * anything, anywhere below me" case. See `PARITY_AUDIT.md`'s Issue 7 for the full history of why
 * the hand-written `ScopeConfig.onDescendantAction` field exists, and `CODEGEN.md` for why this
 * annotation stayed unwritten until now.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class OnDescendantAction
