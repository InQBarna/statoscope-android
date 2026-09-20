import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.createScope
/** Tutorial 03: same counter shape as Tutorial 01, plus an event that always throws. */
data class CounterState(val viewDisplaysTotalCount: Int = 0)

sealed interface CounterWhen {
    data object UserTappedIncrementButton : CounterWhen
    data object UserTappedDecrementButton : CounterWhen
    data object ErrorCase : CounterWhen // Will throw an error
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
        is CounterWhen.ErrorCase ->
            throw IllegalStateException("Invalid state")
    }
}

val counterConfig = ScopeConfig(initial = CounterState(), update = ::counterUpdate)
