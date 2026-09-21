package statoscope.core

/**
 * Handle passed into `update` for enqueueing, querying, and cancelling [Effect]s. `update`
 * itself stays synchronous and pure — enqueuing/cancelling only records intent; the scope
 * reconciles what actually launches or gets cancelled after committing the new state (see
 * [ScopeStore]).
 *
 * Ports Swift Statoscope's `EffectsState` (`Sources/Statoscope/Effects/EffectsState.swift`)
 * directly, [effects]/[cancelEffect]/[cancelAllEffects] included — this isn't a one-shot,
 * write-only handle: a scope threads its currently in-flight effects into every `update` call, so
 * a reducer can see what's already ongoing (e.g. to avoid double-triggering the same fetch) and
 * cancel it by type, the same way Swift's own `effectsState.cancelEffect { $0 is NetworkEffect<DTO> }`
 * does.
 */
interface EffectsState<When> {
    /**
     * Effects currently ongoing — already launched, or enqueued earlier in this same `update`
     * call — as their [pristine] (pre-[map]) type, so querying by type still works after
     * mapping. An effect built with [effect] (a bare closure) can never match a type check here;
     * only a named `class ... : Effect<T>` can.
     */
    val effects: List<Effect<*>>

    fun enqueue(effect: Effect<When>)

    /** Cancels every ongoing effect (see [effects]) whose [pristine] type matches [predicate]. */
    fun cancelEffect(predicate: (Effect<*>) -> Boolean)

    /** Cancels every ongoing effect (see [effects]), regardless of type. */
    fun cancelAllEffects()
}

/**
 * Mutable implementation backing one `update` call. Public rather than `internal` only so the
 * separate `:testing` Gradle module can construct one directly (see [StatoscopeInternalApi]) —
 * app code should never touch this, [ScopeConfig.update]'s `effects: EffectsState<When>`
 * parameter is the only interface app code ever needs.
 *
 * [ongoing] seeds [effects] with whatever the scope already has in flight, keyed by the same
 * positive `id`s [ScopeStore] uses for its own running-effect bookkeeping. A newly [enqueue]d
 * effect is tracked under a synthetic *negative* id — visible via [effects] immediately, in the
 * same `update` call that enqueued it (matching Swift's `enqueue` appending straight into
 * `currentRequestedEffects`), but not a real id yet: [ScopeStore] only assigns one once the
 * effect is actually launched, after `update` returns. [cancelEffect]/[cancelAllEffects] handle
 * both kinds uniformly — an existing (positive-id) effect is recorded in [cancelledExistingIds]
 * for [ScopeStore] to cancel the real job of; one enqueued this same call (negative-id) is simply
 * dropped before it's ever launched at all.
 */
@StatoscopeInternalApi
class MutableEffectsState<When>(ongoing: List<Pair<Long, Effect<*>>> = emptyList()) : EffectsState<When> {

    private var nextSyntheticId = -1L
    private val current = LinkedHashMap<Long, Effect<*>>().apply {
        ongoing.forEach { (id, effect) -> put(id, effect) }
    }
    private val newlyEnqueued = LinkedHashMap<Long, Effect<When>>()
    private val cancelledExistingIds = mutableSetOf<Long>()

    override val effects: List<Effect<*>> get() = current.values.map { it.pristine() }

    override fun enqueue(effect: Effect<When>) {
        val id = nextSyntheticId--
        newlyEnqueued[id] = effect
        current[id] = effect
    }

    override fun cancelEffect(predicate: (Effect<*>) -> Boolean) {
        val toRemove = current.filterValues { predicate(it.pristine()) }.keys.toList()
        toRemove.forEach { id ->
            current.remove(id)
            if (id > 0) cancelledExistingIds += id else newlyEnqueued.remove(id)
        }
    }

    override fun cancelAllEffects() {
        cancelledExistingIds += current.keys.filter { it > 0 }
        newlyEnqueued.clear()
        current.clear()
    }

    /** Effects newly enqueued this call, still needing a real id and an actual launch. Drains. */
    fun flushNewlyEnqueued(): List<Effect<When>> {
        val result = newlyEnqueued.values.toList()
        newlyEnqueued.clear()
        return result
    }

    /** Ids from [ongoing] this call cancelled — the real jobs [ScopeStore] needs to cancel. Drains. */
    fun flushCancelledExistingIds(): Set<Long> {
        val result = cancelledExistingIds.toSet()
        cancelledExistingIds.clear()
        return result
    }
}
