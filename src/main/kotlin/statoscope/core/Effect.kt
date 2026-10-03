package statoscope.core

import kotlinx.coroutines.CancellationException

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

    /**
     * Replays every chained [map] transform starting from [rawResult] — a value shaped like the
     * PRISTINE effect's own natural result, not this (possibly mapped) effect's. Lets a test
     * simulate a specific effect completing with its own real result and get back what [run]
     * would actually have produced, without ever calling [run]. Mirrors Swift's
     * `AnyEffect.transformPristineResult`.
     */
    fun applyFromPristine(rawResult: Any?): Any?

    /**
     * Same as [applyFromPristine], but simulates the PRISTINE effect throwing [error] instead of
     * succeeding — replayed through every [map] call chained onto it exactly like a real failure
     * would propagate: a plain [map] never catches anything, so it just rethrows (or, more
     * precisely, re-asks whatever it wraps, which eventually either rethrows too or — if
     * something between here and the pristine effect is a [mapToResult]-style catcher — returns
     * an ordinary value instead, which this and every [map] above it then treats as a normal
     * [applyFromPristine]-style success to transform). Mirrors Swift's `_pristineCompletes`
     * failure path (`WHEN_EffectFails`).
     */
    fun applyFromPristineFailure(error: Throwable): Any?
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

        @Suppress("UNCHECKED_CAST")
        override fun applyFromPristine(rawResult: Any?): When {
            val throughOriginal = (original as? PristineHolder)?.applyFromPristine(rawResult) ?: rawResult
            return transform(throughOriginal as T)
        }

        @Suppress("UNCHECKED_CAST")
        override fun applyFromPristineFailure(error: Throwable): When {
            val throughOriginal = (original as? PristineHolder)?.applyFromPristineFailure(error) ?: throw error
            return transform(throughOriginal as T)
        }
    }
}

/**
 * Simulates this effect completing with [pristineResult] — a value shaped like the PRISTINE
 * (never-mapped) effect's own natural result, see [pristine] — by replaying every [map] call
 * chained onto it, never actually calling [Effect.run]. Ports Swift's `_pristineCompletes`. For
 * an effect that was never mapped, [pristineResult] already IS the final result.
 */
@Suppress("UNCHECKED_CAST")
@StatoscopeInternalApi
fun <T> Effect<T>.completePristine(pristineResult: Any?): T =
    (this as? PristineHolder)?.applyFromPristine(pristineResult) as? T ?: pristineResult as T

/**
 * Simulates this effect's PRISTINE (never-mapped) effect throwing [error] instead of completing
 * normally — replayed through every [map]/[mapToResult] call chained onto it, never actually
 * calling [Effect.run]. Rethrows [error] itself, to the caller, when nothing in the chain catches
 * it (no [mapToResult] anywhere between here and the pristine effect) — matching exactly what a
 * REAL failure would do in that shape: propagate all the way out, uncaught. Ports Swift's
 * `_pristineCompletes` failure path (`WHEN_EffectFails`).
 */
@Suppress("UNCHECKED_CAST")
@StatoscopeInternalApi
fun <T> Effect<T>.completePristineFailure(error: Throwable): T =
    (this as? PristineHolder)?.applyFromPristineFailure(error) as? T ?: throw error

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

/**
 * A typed-error counterpart to [kotlin.Result] — needed only because [kotlin.Result]'s failure
 * side is fixed to [Throwable]. Returned by the [Effect.mapToResult] overload that takes an
 * [errorMapper][T]; ports the typed half of Swift's `Result<Success, Failure>`
 * (`Effect.mapToResultWithError(_:)`/`mapToResultWithErrorType(_:)`,
 * `Sources/Statoscope/Effects/Effect.swift`).
 */
sealed interface EffectResult<out T, out E> {
    data class Success<out T>(val value: T) : EffectResult<T, Nothing>
    data class Failure<out E>(val error: E) : EffectResult<Nothing, E>
}

/**
 * Adapts this effect so a failure thrown from [run] becomes a [Result.failure] instead of
 * propagating unhandled to [ScopeStore]'s own logging-and-drop catch. Ports Swift's untyped
 * `Effect.mapToResult()` directly — Kotlin's own [kotlin.Result] already fixes its failure side
 * to [Throwable], exactly matching Swift's `Result<Success, Error>` (untyped-`Error`) case, so no
 * separate wrapper type is needed for this overload (see [EffectResult] for the typed one).
 *
 * `CancellationException` is deliberately never wrapped, only rethrown — wrapping it would let
 * cancelling this effect's job complete it with a failure result instead of actually cancelling
 * it, breaking the cooperative-cancellation contract every other suspend function in this
 * codebase relies on.
 */
fun <T> Effect<T>.mapToResult(): Effect<Result<T>> {
    val original = this
    return object : Effect<Result<T>>, PristineHolder {
        override val pristine: Effect<*> = original.pristine()

        override suspend fun run(): Result<T> = runCatchingEffect { original.run() }

        override fun applyFromPristine(rawResult: Any?): Result<T> = runCatchingEffect {
            @Suppress("UNCHECKED_CAST")
            ((original as? PristineHolder)?.applyFromPristine(rawResult) ?: rawResult) as T
        }

        // The one place a simulated failure actually gets caught instead of just rethrown —
        // mirrors run()'s own try/catch exactly, just fed a pretend error instead of a real
        // thrown one. If `original` itself already catches (e.g. a nested mapToResult()),
        // its own applyFromPristineFailure call below returns a plain value instead of
        // throwing, and runCatchingEffect wraps that in Result.success — not Result.failure —
        // same as a real run() that happened not to throw after all.
        override fun applyFromPristineFailure(error: Throwable): Result<T> = runCatchingEffect {
            @Suppress("UNCHECKED_CAST")
            ((original as? PristineHolder)?.applyFromPristineFailure(error) ?: throw error) as T
        }
    }
}

/**
 * Same as [mapToResult] but with a typed error produced by [errorMapper] — ports Swift's
 * `Effect.mapToResultWithError(_:)`. Reach for the no-argument [mapToResult] instead when the
 * error doesn't need a narrower type than [Throwable].
 */
fun <T, E> Effect<T>.mapToResult(errorMapper: (Throwable) -> E): Effect<EffectResult<T, E>> {
    val original = this
    return object : Effect<EffectResult<T, E>>, PristineHolder {
        override val pristine: Effect<*> = original.pristine()

        override suspend fun run(): EffectResult<T, E> =
            runCatchingEffect { original.run() }.toEffectResult(errorMapper)

        override fun applyFromPristine(rawResult: Any?): EffectResult<T, E> = runCatchingEffect {
            @Suppress("UNCHECKED_CAST")
            ((original as? PristineHolder)?.applyFromPristine(rawResult) ?: rawResult) as T
        }.toEffectResult(errorMapper)

        override fun applyFromPristineFailure(error: Throwable): EffectResult<T, E> = runCatchingEffect {
            @Suppress("UNCHECKED_CAST")
            ((original as? PristineHolder)?.applyFromPristineFailure(error) ?: throw error) as T
        }.toEffectResult(errorMapper)
    }
}

private inline fun <T> runCatchingEffect(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

private fun <T, E> Result<T>.toEffectResult(errorMapper: (Throwable) -> E): EffectResult<T, E> =
    fold(
        onSuccess = { EffectResult.Success(it) },
        onFailure = { EffectResult.Failure(errorMapper(it)) }
    )
