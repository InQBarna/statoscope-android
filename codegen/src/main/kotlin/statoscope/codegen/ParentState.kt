package statoscope.codegen

/**
 * Declares [State]'s annotated property as a *live* read of its parent's own state (or of any ANCESTOR's, see below) —
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
 * Mechanically, this generates three things a hand-written relationship would need: a
 * `SuperstateKey<ParentState>` wired as the connecting `SubScope.superstateKey` (previously
 * always `null` for every `@SubState` relationship — see `CODEGEN.md`'s own documented
 * limitation this annotation closes), and — on the CHILD's own generated `ScopeConfig` —
 * `liveAncestorKey`/`deriveFromLiveAncestor` (see `ScopeConfig.kt`'s own doc), which is what
 * actually keeps the property current: not a re-stamp folded into this reducer's own `update()`
 * wrapper (that's still exactly what [ReducerInjected] does, since an injected value doesn't
 * change on its own the way an ancestor's state does), but a synchronous push `ScopeStoreImpl`
 * performs the moment the parent commits ANY new state of its own — including activation itself,
 * before this reducer has processed a single event.
 *
 * **The ancestor's state this property holds leaves out the branch that leads down to this reducer** (the ancestor's field for the first
 * step towards it, `null` there): that branch is this reducer's own state, which would hold the previous copy, which would hold the
 * branch with the copy before it, a chain growing by a full copy of the ancestor on every event. Read what the ancestor itself owns.
 *
 * **This means an `@ParentState` property is genuinely live** — closer to Swift's own
 * `@SuperState`-plus-`observed:true` guarantee than the earlier "fresh as of the last cycle"
 * design this replaced. Reading `state.list.favorites.size` from *inside* this reducer's own
 * `update()` body sees whatever the parent's state was at the moment this `update()` call started
 * running (same as reading any other field of `state` — nothing here is special-cased to lag
 * deliberately); reading it from the UI layer (`statoscope.android.compose.ConnectedChild` or a
 * plain `collectAsState()`) sees the parent's value as of the LAST commit on either side, pushed
 * synchronously, with no polling and no `Flow.combine()` dispatch gap.
 *
 * **The device-confirmed case this was built to fix**: a leaf's `UserTappedFavoriteButton` that
 * [OnChildAction] delegates up as `ToggledFavorite` — this child's own `update()` runs first (the
 * delegation itself only happens afterward, in `SubScope`'s own middleware), so the OLD
 * re-stamp-after-`update()` design always rendered one tap behind whatever its own tap just
 * caused. With the push-based cascade, the moment the parent's `ToggledFavorite` handling commits
 * the new `favorites` list, this child gets pushed the fresh value synchronously, within that
 * same call — before anything else observes either store. `NewsFeedArticleScreen` in
 * `statoscope-kotlin-examples` can read `state.list.favorites.size` directly again; it no longer
 * needs `favoritesCount` threaded down by hand.
 *
 * Reacting to the child's own events (rather than reading the parent's state) is still
 * [OnChildAction]'s job — delegate the action, inject the read: the two mechanisms are
 * independent and commonly used together, matching how the hand-written `SubScope.onChildAction`/
 * `superstateKey` pair already works.
 *
 * **An ancestor above the parent** (added 2026-10-17): the property may be typed as the `State` of any ancestor reducer, not only the
 * direct parent (Swift's `@SuperState` works at any depth). The processor walks up the `@SubState` chain to the reducer with that type, that
 * reducer generates its `SuperstateKey` and wires it into the `@SubState` slot that leads to this descendant, so nothing is declared at the
 * levels in between. Same liveness: pushed at activation and on every commit of that ancestor.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ParentState
