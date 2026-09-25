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
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class SubState
