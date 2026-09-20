package statoscope.core

/**
 * A unit of asynchronous work enqueued during `update`. Its result re-enters the scope as an
 * ordinary [When] event via [EffectsState.enqueue] — effects never bypass the update contract,
 * they can only ever report back as another event.
 */
interface Effect<When> {
    suspend fun run(): When
}
