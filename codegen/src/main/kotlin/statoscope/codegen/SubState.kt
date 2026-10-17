package statoscope.codegen

/**
 * Marks a nullable property on a [Reducer]-annotated object's `State` class as owning a child
 * scope — the codegen stand-in for Swift's `@SubState`. `:codegen-processor` generates the
 * `get`/`set` lens a [statoscope.core.SubScope] needs (`{ it.child }` /
 * `{ state, child -> state.copy(child = child) }`), resolves the child's own [Reducer]-generated
 * `config` by matching this property's type against every other `@Reducer` object's `State`, and
 * collects the result into a stable, top-level `SubScope` `val` referenced from the owning
 * [Reducer]'s generated `config` — see [Reducer]'s own doc for the full picture, including what
 * still has to be supplied by hand ([OnChildAction], each child's own reducer).
 *
 * A property this is on must be a class type (not `List<T>`, not a type alias resolving to one)
 * owned by some other `@Reducer` object as its `State` — a real KSP compile error, pointing at
 * the property, otherwise.
 *
 * [provideSuperstate] makes this child's whole subtree able to read the OWNING reducer's live
 * state with `dependencies.resolve(<owner>SuperstateKey)` — the generated top-level
 * `val <reducerName>SuperstateKey: SuperstateKey<State>` (first letter lowercased), wired as the
 * connecting `SubScope.superstateKey`. This is what Swift's `@SuperState` gives a descendant of
 * ANY depth, not only a direct child: [ParentState] covers the direct parent as a `State` field,
 * this covers every ancestor above it, as an on-demand read inside `update()` (live at the moment
 * of the call, never a snapshot taken earlier). Off by default: it is only generated when asked
 * for, or when a direct child declares [ParentState].
 *
 * ```kotlin
 * @Reducer object PreLandingReducer {
 *     data class State(val link: String? = null, @SubState(provideSuperstate = true) val account: AccountReducer.State? = null)
 * }
 * // anywhere below `account`, inside update():
 * val link = dependencies.resolve(preLandingReducerSuperstateKey).link
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class SubState(val provideSuperstate: Boolean = false)
