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
 * (`Sources/Statoscope/Effects/Effect.swift`) directly, [effect]'s closure-based construction
 * included (Swift's `AnyEffect { ... }`).
 */
interface Effect<T> {
    suspend fun run(): T
}

/**
 * Exposes the effect [map] wrapped, so [pristine] can see through any number of chained `.map()`
 * calls back to the original — what makes [EffectsState.effects]/[EffectsState.cancelEffect]
 * still able to query/cancel by an effect's real, original type after it's been mapped for
 * enqueueing. Internal: [pristine] is the public surface.
 */
internal interface PristineHolder {
    val pristine: Effect<*>
}

/**
 * The original effect, walking back through any number of chained [map] calls — `this` if it was
 * never mapped at all. Ports Swift's `AnyEffect.pristine`; unlike Swift's `pristineEquals`/
 * `pristineIs`, this returns the effect itself rather than a Bool, since Kotlin's own `is`/`as`
 * already work naturally against it: `effect.pristine() is NetworkEffect`. An effect built with
 * [effect] (a bare closure) has no meaningful type of its own beyond "some closure" — querying by
 * type only ever finds a named `class ... : Effect<T>`, mapped or not.
 */
fun Effect<*>.pristine(): Effect<*> = (this as? PristineHolder)?.pristine ?: this

/**
 * Adapts this effect's own result to [When] — the seam that lets an effect declared once (see
 * [Effect]'s own doc) be enqueued by any reducer whose `When` can be built from [T].
 */
fun <T, When> Effect<T>.map(transform: (T) -> When): Effect<When> {
    val original = this
    return object : Effect<When>, PristineHolder {
        override val pristine: Effect<*> = original.pristine()
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
