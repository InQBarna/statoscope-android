# `@Reducer` code generation

An optional layer on top of Tutorial 05's hand-written `ScopeConfig`/`SubScope` — the Kotlin
answer to "how do we get iOS's `@Reducer` macro ergonomics without a macro system." Two modules:

- **`:codegen`** — `@Reducer`, `@SubState`, `@OnChildAction`. Pure marker annotations
  (`SOURCE` retention), zero dependencies. A consuming module adds this to its normal compile
  classpath.
- **`:codegen-processor`** — a KSP `SymbolProcessor` reading those annotations and generating a
  `<Reducer>.config: ScopeConfig<State, When>` extension property, `SubScope` wiring included. A
  consuming module adds this to its `ksp(...)` configuration, never `implementation`.

Neither is applied to any module in this repo — like `:testing`/`:android`, this is a library
capability for consumers to opt into (an app module, `statoscope-kotlin-examples`), not something
the tutorials themselves use. `docs/05-Scopes.md` and its `SubScope(...)`-by-hand example stay as
the reference/teaching path.

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

**"Multi-level" responsiveness — a grandparent reacting to a grandchild's event — today needs an
explicit relay, one level at a time.** An earlier version of this doc claimed this was "checked
against the real Swift source" and that Swift's own `Store.swift` only ever delivers one
direct-child event at a time too — **that claim was false**, and has since been corrected in two
other places in this repo (`codegen/.../OnChildAction.kt`'s own doc comment, and
`PARITY_AUDIT.md`'s Issue 7) but was never fixed here until now. Swift's
`callParentEnclosedHierarchialUpdate`/`allHierarchialScopeMiddlewareParents`
(`ScopeImplementation.swift`) actually **broadcast** the original, unmodified event to every
`MiddlewareReducer`-conforming ancestor at any depth — a grandparent sees a grandchild's raw
`When` type directly, no relay needed. Kotlin's `@OnChildAction` has no equivalent single generic
dispatch point a broadcast could invoke uniformly; reaching a grandparent here means an
intermediate reducer's own `@OnChildAction` relays the child's event *unchanged*, wrapped as an
associated value of its own `When`, and the level above matches into that wrapped value. This
one-hop-plus-relay mechanism is real and proven end to end — a real `ScopeStore`, a `send()` on
the leaf article store, and the root's own state reflecting it two levels up
(`SubScopeProcessorTest.testGeneratedConfigWiresThreeRealLevelsWithZeroHandWrittenSubScopes`) —
it just isn't Swift's broadcast. See `PARITY_AUDIT.md`'s Issue 7 for the full reasoning and the
target of eventual broadcast reach on both platforms, not yet designed for Kotlin.

## What's still real, hand-written work, and why

- Each reducer's own `State`/`When`/`update` body — real domain logic, same as Swift.
- `@OnChildAction` function bodies — real domain logic, Swift's `MiddlewareReducer
  .updateSubstate` equivalent.
- `SuperstateKey` declarations and `dependencies.resolve(...)` calls — see above.
- Every `@Reducer`'s `State` needs a no-arg-constructible default (`State()` must compile) — a
  real, new requirement this imposes: the generated `config`'s own `initial` needs *some* value,
  even though (same as `SubScope.config`'s own doc already says for the hand-written path) it's
  only ever a placeholder for anything actually reached as a child.
