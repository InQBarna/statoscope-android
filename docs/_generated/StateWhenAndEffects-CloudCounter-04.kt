import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.effect
import statoscope.core.map
/** A remote counter's payload — what actually gets persisted, over the network, on each change. */
data class Dto(val count: Int)

/** Tutorial 02: CloudCounter — a counter whose value is stored remotely via a network API. */
data class CloudCounterState(
    val viewDisplaysTotalCount: Int = 0,
    val viewShowsLoadingAndDisablesButtons: Boolean = false
)

sealed interface CloudCounterWhen {
    data object UserTappedIncrementButton : CloudCounterWhen
    data object UserTappedDecrementButton : CloudCounterWhen
    data class NetworkPostCompleted(val dto: Dto) : CloudCounterWhen
}

/**
 * Posts the counter's new value to a remote API. Simulated with `delay(...)` rather than a real
 * network call — safe and deterministic to have running in the background, unlike an unmocked
 * call to a real host. Returns `Dto` — its own natural result — nothing here knows this is even
 * being used by a counter; `cloudCounterUpdate` adapts that with `.map(...)` at each call site.
 */
private fun postToNetwork(newValue: Int) = effect {
    delay(10) // Simulate a network round-trip
    Dto(count = newValue)
}

fun cloudCounterUpdate(
    state: CloudCounterState,
    action: CloudCounterWhen,
    effects: EffectsState<CloudCounterWhen>,
    dependencies: Dependencies
): CloudCounterState {
    return when (action) {
        is CloudCounterWhen.UserTappedIncrementButton -> {
            val newValue = state.viewDisplaysTotalCount + 1
            effects.enqueue(postToNetwork(newValue).map(CloudCounterWhen::NetworkPostCompleted))
            state.copy(viewDisplaysTotalCount = newValue, viewShowsLoadingAndDisablesButtons = true)
        }

        is CloudCounterWhen.UserTappedDecrementButton -> {
            if (state.viewDisplaysTotalCount <= 0) return state
            val newValue = state.viewDisplaysTotalCount - 1
            effects.enqueue(postToNetwork(newValue).map(CloudCounterWhen::NetworkPostCompleted))
            state.copy(viewDisplaysTotalCount = newValue, viewShowsLoadingAndDisablesButtons = true)
        }

        is CloudCounterWhen.NetworkPostCompleted ->
            state.copy(
                viewShowsLoadingAndDisablesButtons = false,
                viewDisplaysTotalCount = action.dto.count
            )
    }
}

val cloudCounterConfig = ScopeConfig(
    initial = CloudCounterState(),
    update = ::cloudCounterUpdate
)
