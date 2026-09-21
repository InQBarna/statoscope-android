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
 * (`Sources/Statoscope/Effects/Effect.swift`) directly. Swift's `AnyEffect` type-erasure wrapper,
 * and the `pristineEquals`/`pristineIs` machinery built on top of it to see through a `map()`
 * back to an effect's original type, are not ported: Kotlin's `TestPlan.thenEffects` already
 * takes an arbitrary predicate over the whole enqueued list rather than comparing against one
 * expected value, so there's nothing here that needs recovering a pre-`map()` identity to work.
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
