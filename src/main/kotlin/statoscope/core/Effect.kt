package statoscope.core

/**
 * A unit of asynchronous work enqueued during `update`. [T] is this effect's own natural result
 * — whatever it genuinely produces (a DTO, a list, a plain value) — not necessarily a `When`
 * case; [EffectsState.enqueue] only ever accepts an `Effect<When>`, so an effect whose own [T]
 * differs gets adapted with [map] at the enqueue site: `someEffect.map(When::SomeCase)`. That
 * adaptation happening at the call site, not inside the effect itself, is what makes an effect
 * genuinely reusable across more than one reducer — a `FetchArticlesEffect` returning
 * `List<ArticleDto>` knows nothing about whichever `When` eventually wraps its result, so any
 * reducer whose `When` can be built from a `List<ArticleDto>` can enqueue the exact same effect.
 * Either way, its result still only ever re-enters the scope as an ordinary event — effects never
 * bypass the update contract.
 *
 * Ports Swift Statoscope's `Effect.ResultType` + `.map(_:)`
 * (`Sources/Statoscope/Effects/Effect.swift`) directly. Swift's `AnyEffect`'s closure-based
 * construction is ported too, as [effect] — but the type-erasure identity it also carries (so
 * `pristineEquals`/`pristineIs` can see through a `map()` back to an effect's original type) is
 * not: Kotlin's `TestPlan.thenEffects` already takes an arbitrary predicate over the whole
 * enqueued list rather than comparing against one expected value, so there's nothing here that
 * needs recovering a pre-`map()` identity to work.
 */
interface Effect<T> {
    suspend fun run(): T
}

/**
 * Adapts this effect's own result to [When] — the seam that lets an effect declared once (see
 * [Effect]'s own doc) be enqueued by any reducer whose `When` can be built from [T].
 */
fun <T, When> Effect<T>.map(transform: (T) -> When): Effect<When> {
    val original = this
    return object : Effect<When> {
        override suspend fun run(): When = transform(original.run())
    }
}

/**
 * Builds an [Effect] straight from a suspend function — for the common case where whatever it's
 * wrapping (an injected dependency's own `suspend` call, most often) is already the entire
 * implementation, and a dedicated named type would add nothing but ceremony. Ports Swift's
 * `AnyEffect { ... }` closure constructor:
 * ```kotlin
 * effects.enqueue(effect { network.fetchArticles() }.map(NewsFeedListWhen::NetworkFetchCompleted))
 * ```
 * Reach for a named `class ... : Effect<T>` instead only when there's real logic worth naming
 * and testing on its own — see [Effect]'s own doc for why that's still fully reusable too.
 */
fun <T> effect(block: suspend () -> T): Effect<T> = object : Effect<T> {
    override suspend fun run(): T = block()
}
