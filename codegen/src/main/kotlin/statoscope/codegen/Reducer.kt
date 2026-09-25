package statoscope.codegen

/**
 * Marks an `object` as a Reducer — the closest Kotlin analog to Swift's `@Reducer` macro
 * target. `:codegen-processor` (a KSP `SymbolProcessor`) validates it has a nested `State`
 * class, a nested `When` type, and an `update(State, When, EffectsState<When>, Dependencies):
 * State` function matching [statoscope.core.Reducer]'s shape — a real compile error, pointing
 * at the object, if any is missing or shaped wrong. It then generates a
 * `val <ReducerObject>.config: ScopeConfig<State, When>` extension property — `ScopeConfig(...)`
 * is never written by hand.
 *
 * ```kotlin
 * @Reducer
 * object NewsFeedArticleReducer {
 *     data class State(val id: String = "", val title: String = "")
 *     enum class When { UserTappedFavoriteButton }
 *     fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State = state
 * }
 *
 * // Generated:
 * // val NewsFeedArticleReducer.config: ScopeConfig<NewsFeedArticleReducer.State, NewsFeedArticleReducer.When>
 * ```
 *
 * Shares its simple name with [statoscope.core.Reducer] — that one's the function-type typealias
 * an `update` value has, this one's the annotation naming the whole bundle. Different packages,
 * so no conflict unless a single file imports both by name; the typealias rarely needs to be,
 * since `ScopeConfig.update`'s declared type is normally all that references it.
 *

 * [SubState]-annotated properties on the nested `State` class are collected automatically into
 * `config`'s `subScopes`, each one's own child `config` resolved by finding whichever OTHER
 * `@Reducer` object declares a `State` matching that property's type — a child reducer's own
 * generated `config` is referenced directly, never re-declared at the call site. An
 * [OnChildAction]-annotated function on the same object, if its parameter types match a child,
 * becomes that child's `onChildAction`; a child with none gets `onChildAction = null`.
 *
 * What stays real, hand-written parameters on the generated `SubScope`, and why:
 * - each child's own `config` — a real reducer, not something inferable from an annotation
 * - `onChildAction` — real domain logic ([OnChildAction]'s own doc), Swift's
 *   `MiddlewareReducer.updateSubstate` equivalent
 * - `superstateKey` is **not** auto-wired, and deliberately so: unlike [SubState], Kotlin's
 *   `State` has no live-injection point a `@SuperState`-equivalent annotation could hook into
 *   without either going stale (reintroducing Swift's own `@SuperState` staleness bug) or
 *   breaking `data class` value semantics (a mutable-holder hack). It stays an explicit
 *   [statoscope.core.SuperstateKey] + `dependencies.resolve(key)` call inside `update()`.
 *
 * One real, new requirement this imposes beyond what a hand-written `ScopeConfig` needs: every
 * `@Reducer`'s `State` must be no-arg-constructible (`State()` must compile) — the generated
 * `config`'s own `initial` needs a value, even though (same as [statoscope.core.SubScope.config]
 * already documents) it's only ever a placeholder for anything actually reached as a child;
 * activation overwrites it with whatever the parent's own `update()` wrote.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Reducer
