# `@Reducer` code generation

An optional layer on top of Tutorial 05's hand-written `ScopeConfig`/`SubScope` — the Kotlin
answer to "how do we get iOS's `@Reducer` macro ergonomics without a macro system." Two modules:

- **`:codegen`** — `@Reducer`, `@SubState`, `@OnChildAction`, `@OnDescendantAction`,
  `@ReducerInjected`, `@ParentState`. Pure marker annotations (`SOURCE` retention), zero
  dependencies. A consuming module adds this to its normal compile classpath.
- **`:codegen-processor`** — a KSP `SymbolProcessor` reading those annotations and generating a
  `<Reducer>.config: ScopeConfig<State, When>` extension property, `SubScope` wiring included. A
  consuming module adds this to its `ksp(...)` configuration, never `implementation`.

Applied in exactly one place in this repo as of 2026-10-02: the root module's **test** sources
only (`build.gradle.kts`'s `kspTest(project(":codegen-processor"))`), backing
**Advanced/02: Code Generation** (`docs/advanced/02-CodeGeneration.md`) — the same news-feed
domain as Tutorial 05, rebuilt with these annotations, side by side with the hand-written version
for direct comparison. The library's own **main** sources still have no KSP dependency at all;
`docs/05-Scopes.md` and its `SubScope(...)`-by-hand example stay as the primary reference/teaching
path, with Advanced/02 as the "here's the shorter version" follow-up. Not yet applied in
`statoscope-kotlin-examples` or any app module — a consumer there adds the same two lines
Advanced/02's own `build.gradle.kts` entry does.

**Found while building Advanced/02**: a `SubScope` that also needs a `superstateKey` can't be
expressed through the annotations at all today — `@SubState` never attaches one (see "Design
decisions" below), and there's no escape hatch to add one to a generated `SubScope` after the
fact either. Tutorial 05's own favorites-limit feature needed exactly this (a live ancestor read
before deciding), so Advanced/02's version restructures that one feature to avoid needing
`superstateKey` instead of demonstrating it — a real, current limitation of the codegen path, not
a doc gap.

## Shape

```kotlin
@Reducer
object NewsFeedArticleReducer {
    data class State(val id: String = "", val title: String = "")
    enum class When { UserTappedFavoriteButton }
    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State = state
}

@Reducer
object NewsFeedListReducer {
    data class State(
        val articles: List<NewsFeedArticleReducer.State> = emptyList(),
        @SubState val selectedArticle: NewsFeedArticleReducer.State? = null
    )
    sealed interface When {
        data class UserSelectedArticle(val id: String) : When
        data class ToggledFavorite(val id: String) : When
    }
    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State = state

    @OnChildAction
    fun onArticleFavorited(childWhen: NewsFeedArticleReducer.When, childState: NewsFeedArticleReducer.State, dependencies: Dependencies): When? =
        if (childWhen == NewsFeedArticleReducer.When.UserTappedFavoriteButton) When.ToggledFavorite(childState.id) else null
}
```

`NewsFeedListReducer.config` is entirely generated — no `ScopeConfig(...)`, no `SubScope(...)`,
no `get`/`set`, no `subScopes = listOf(...)` anywhere. See `:codegen`'s three annotation files for
the full contract (what's validated, what's inferred, what still has to be written by hand).

## Design decisions, and why

**`@Reducer` requires `State`/`When`/`update` on an `object`, checked by KSP — a real compile
error, not a silent no-op, for anything missing or shaped wrong.** Verified directly: deleting
`update()` from a reducer produces `e: [ksp] .../File.kt:23: @Reducer object 'X' needs fun
update(...)`, pointing at the exact declaration, and the build genuinely fails
(`SubScopeProcessorTest`'s sibling sabotage-test history — see the test file's own git history
for the exact commands used to prove this, same discipline as every other verified feature in
this project).

**`@OnChildAction` matches by parameter *type*, not by function name.** An earlier version of
this matched an `on<PropertyName>Action` naming convention — deliberately rejected: a typo in the
name found nothing, and the child's actions silently stopped reaching the parent, with zero
error. Type-based matching means the function can be named anything; a wrong signature (bad
parameter order, wrong parameter type, wrong return type) is a real, specific KSP error instead.

**`superstateKey` is not auto-wired.** Unlike `@SubState`, Kotlin's `State` has no live-injection
point a `@SuperState`-equivalent annotation could hook into without either going stale
(reintroducing Swift's own documented `@SuperState` staleness bug) or breaking `data class` value
semantics with a mutable-holder hack. It stays an explicit `SuperstateKey` +
`dependencies.resolve(key)` call inside `update()` — a structural difference from Swift, not an
oversight.

**Every generated `SubScope` is its own stable, eagerly-initialized top-level `val`, never built
inline inside `subScopes`'s `listOf(...)`.** `ScopeStore.childStore(subScope)` looks a child up by
reference identity — an extension `get()` property (or building `SubScope(...)` inline) would
silently rebuild a *different* instance on every access, breaking `childStore()` for any caller
outside the generated file. Found and fixed during development, not a guess.

**"Multi-level" responsiveness — a grandparent reacting to a grandchild's event — has two paths
now, not one.** `@OnChildAction` alone still needs an explicit relay, one level at a time: an
intermediate reducer's own `@OnChildAction` relays the child's event *unchanged*, wrapped as an
associated value of its own `When`, and the level above matches into that wrapped value. Proven
end to end — a real `ScopeStore`, a `send()` on the leaf article store, and the root's own state
reflecting it two levels up
(`SubScopeProcessorTest.testGeneratedConfigWiresThreeRealLevelsWithZeroHandWrittenSubScopes`).

For genuine, Swift-style broadcast reach — a grandparent seeing a grandchild's raw event directly,
with zero relay at any intermediate level — use **`@OnDescendantAction`** (added after
`PARITY_AUDIT.md`'s Issue 7/`TEST_PARITY_MAP.md` closed the hand-written `ScopeConfig
.onDescendantAction` field): mark one function per reducer with the type-erased shape
`(descendantWhen: Any, descendantState: Any, parentState: ThisReducer.State, dependencies:
Dependencies) -> ThisReducer.When?` and match the real descendant type inside the body with
`is`/`as`, exactly like Swift's `MiddlewareReducer.updateSubstate<Child: Reducer>` does with its
own `childWhen as? SomeReducer.When`. `:codegen-processor` validates the shape (parameter count,
the two `Any` slots, the `State`/`Dependencies`/`When` types) the same way it validates
`@OnChildAction`, and allows at most one such function per reducer (`ScopeConfig
.onDescendantAction` is a single nullable slot, not one per child). Proven end to end —
`SubScopeProcessorTest.testOnDescendantActionReachesAGrandchildWithNoRelayAtTheMiddleLevel` — a
three-level `Root -> Middle -> Grandchild` tree where the middle reducer declares no
`@OnChildAction` at all, and the root still sees the grandchild's tap directly.

Both mechanisms coexist deliberately: `@OnChildAction` keeps its compile-time, per-child-type
validation for the common direct-child case; `@OnDescendantAction` is the type-erased "see
anything below me" escape hatch, same division of labor as the hand-written `SubScope
.onChildAction`/`ScopeConfig.onDescendantAction` pair it generates.

## What's still real, hand-written work, and why

- Each reducer's own `State`/`When`/`update` body — real domain logic, same as Swift.
- `@OnChildAction` function bodies — real domain logic, Swift's `MiddlewareReducer
  .updateSubstate` equivalent.
- `SuperstateKey` declarations and `dependencies.resolve(...)` calls — see above.
- Every `@Reducer`'s `State` needs a no-arg-constructible default (`State()` must compile) — a
  real, new requirement this imposes: the generated `config`'s own `initial` needs *some* value,
  even though (same as `SubScope.config`'s own doc already says for the hand-written path) it's
  only ever a placeholder for anything actually reached as a child.

## `@ReducerInjected` (2026-10-02) — ambient `State`-level injection, string-named

The codegen stand-in for Swift's `@ReducerInjected(RealLogger.self)` — see that annotation's own
doc for the full picture. Ports the *feature* (`state.someProperty` instead of
`dependencies.resolve(key)`), not the exact syntax: Kotlin annotations can only carry
compile-time constants, never a reference to an arbitrary `val`, so `@ReducerInjected(LoggerKey)`
(mirroring Swift's type reference) isn't expressible — `@ReducerInjected("LoggerKey")`, the key's
*name* as a string, is the only way to point an annotation at one. `:codegen-processor` doesn't
validate that the name resolves to a real `InjectionKey<T>` with a matching `T` — unlike
`@SubState`/`@OnChildAction`'s full symbol-level checks, a wrong name surfaces as a real compile
error one level removed, in the generated file itself, not pointing at the annotation. Accepted
for now; recoverable later by having the processor resolve the name itself via KSP's `Resolver`
and report the error at the right place, same as everything else this codegen validates.

Mechanically: any `@ReducerInjected` property makes the generated `update` a wrapping lambda
(`{ state, action, effects, dependencies -> Reducer.update(...).copy(prop = dependencies.resolve(Key)) }`)
instead of the plain `Reducer::update` function reference used when there are none — re-stamping
every annotated property from `dependencies` after every single `update()` call, regardless of
which event fired. Because the property is still a real, ordinary `data class` field (with its
own real default, `= LoggerKey.defaultValue`), `equals()`/`copy()` need no lens-based workaround
the way `@SubState`'s live child slots would have. The trade-off, not a bug: this is "fresh as of
the last `update()`," not continuously live on every read the way Swift's computed `store.state`
getter re-injects on every access — an `inject()`/`provide` change between two events isn't
reflected in an already-materialized `State` until the next `update()` runs. Proven end to end in
`SubScopeProcessorTest` (`TestInjectedReducer`): the state holds the key's own default before any
event, and a real `inject()`'d override after sending one — even one that never touches the
annotated property itself.
