package statoscope.codegen

/**
 * Declares [State]'s annotated property as a *live* read of its direct parent's own state —
 * the codegen stand-in for Swift's `@SuperState var parent: ParentReducer.State`. Unlike
 * [ReducerInjected], this needs no string-named workaround: the property's own declared TYPE
 * (some other `@Reducer`'s `State`) is exactly what KSP matches on, the same way [SubState]
 * matches a child by type — a real compile error, pointing at the property, for a type that
 * names no reducer, or names one that isn't genuinely this reducer's parent (no other `@Reducer`
 * declares a `@SubState` property of this reducer's own `State` type).
 *
 * ```kotlin
 * @Reducer
 * object NewsFeedListReducer {
 *     data class State(val favorites: List<String> = emptyList(), ...)
 *     ...
 * }
 *
 * @Reducer
 * object NewsFeedArticleReducer {
 *     data class State(
 *         @ParentState val list: NewsFeedListReducer.State = NewsFeedListReducer.State(),
 *         ...
 *     )
 *     ...
 * }
 * ```
 *
 * Mechanically, this generates the same two things a hand-written relationship would need: a
 * `SuperstateKey<ParentState>` wired as the connecting `SubScope.superstateKey` (previously
 * always `null` for every `@SubState` relationship — see `CODEGEN.md`'s own documented
 * limitation this annotation closes), and — exactly like [ReducerInjected] — a generated
 * `update()` wrapper that re-stamps the annotated property from `dependencies.resolve(key)`
 * right after the reducer's own `update()` returns.
 *
 * The property's own declared default (`= NewsFeedListReducer.State()` above) is what the state
 * holds until the first `update()` call ever runs on this store; resolved fresh after every call
 * from then on, not continuously live on every read (unlike Swift's `@SuperState`, which
 * re-injects on every `store.state` access via a computed property) — same trade-off, and the
 * same reasoning, as [ReducerInjected].
 *
 * **The one easy-to-miss trap, worth stating plainly**: because the re-stamp happens *after*
 * this reducer's own `update()` returns, `update()` itself always sees the PREVIOUS cycle's
 * value for an `@ParentState` property — never the one being resolved for the call currently
 * running. `state.list.favorites.size` read from *inside* this reducer's own `update()` body is
 * stale by exactly one cycle, not live. Reading it is for code that runs after a cycle
 * completes — UI rendering, a later `update()` call — never from within the same `update()` call
 * whose own action might have changed what it resolves to.
 *
 * **A second, less obvious case of the same trap — confirmed on a real device, not just reasoned
 * about**: `@ParentState` lags by one of THIS reducer's own cycles even for UI display, whenever
 * the parent's data changes as a *side effect* of the very event reading it. E.g. a leaf's
 * `UserTappedFavoriteButton` that [OnChildAction] delegates up as `ToggledFavorite`: this
 * reducer's own `update()` runs (and the `@ParentState` wrapper re-stamps `list`) BEFORE
 * `onChildAction` ever delegates anything — the parent's `favorites` hasn't changed yet at that
 * point — and nothing re-triggers this reducer's own `update()` afterward to catch up once it
 * has. The displayed count is then permanently one tap behind whatever its own taps just
 * caused — toggle on, it still shows the old count; toggle off, it shows the count from the
 * *previous* toggle, and so on. For data a view needs to show that changes this way, read it
 * from the ANCESTOR's own state at the UI layer instead (already current there, with no resolve
 * timing at all — see `statoscope.android.compose.ConnectedChild`) — never through a descendant's
 * `@ParentState` property.
 *
 * Reacting to the child's own events (rather than reading the parent's state) is still
 * [OnChildAction]'s job — delegate the action, inject the read: the two mechanisms are
 * independent and commonly used together, matching how the hand-written `SubScope.onChildAction`/
 * `superstateKey` pair already works. Just don't expect the injected read to already reflect
 * what that same delegation is about to cause.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ParentState
