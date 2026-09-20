package statoscope.core

/**
 * Handle passed into `update` for enqueueing [Effect]s. `update` itself stays synchronous and
 * pure — enqueuing only records intent; the scope launches enqueued effects after committing
 * the new state.
 */
interface EffectsState<When> {
    fun enqueue(effect: Effect<When>)
}

/**
 * Mutable implementation collecting effects enqueued during one `update` call. Public rather
 * than `internal` only so the separate `:testing` Gradle module can construct one directly (see
 * [StatoscopeInternalApi]) — app code should never touch this, [ScopeConfig.update]'s
 * `effects: EffectsState<When>` parameter is the only interface app code ever needs.
 */
@StatoscopeInternalApi
class MutableEffectsState<When> : EffectsState<When> {
    private val enqueued = mutableListOf<Effect<When>>()

    override fun enqueue(effect: Effect<When>) {
        enqueued += effect
    }

    fun flush(): List<Effect<When>> {
        val result = enqueued.toList()
        enqueued.clear()
        return result
    }
}
