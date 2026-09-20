import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
/** Tutorial 01: Basic Counter with State and When. */
data class CounterState(
    val viewDisplaysTotalCount: Int = 0
)

sealed interface CounterWhen {
    data object UserTappedIncrementButton : CounterWhen
    data object UserTappedDecrementButton : CounterWhen
}

fun counterUpdate(
    state: CounterState,
    action: CounterWhen,
    effects: EffectsState<CounterWhen>,
    dependencies: Dependencies
): CounterState {
    return when (action) {
        is CounterWhen.UserTappedIncrementButton ->
            state.copy(viewDisplaysTotalCount = state.viewDisplaysTotalCount + 1)
        is CounterWhen.UserTappedDecrementButton ->
            state.copy(viewDisplaysTotalCount = maxOf(0, state.viewDisplaysTotalCount - 1))
    }
}

val counterConfig = ScopeConfig(
    initial = CounterState(),
    update = ::counterUpdate
)
