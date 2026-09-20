//
// CounterExample.kt
// statoscope-kotlin
//
// Examples from Tutorial 01: State and When
//
package statoscope.examples.tutorial01

// @extract:begin StateAndWhen-Counter-01
// @extract:begin StateAndWhen-Counter-02
// @extract:begin StateAndWhen-Counter-03
// @extract:begin StateAndWhen-Counter-04
// @extract:begin StateAndWhen-Counter-05
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
// @extract:end StateAndWhen-Counter-01
// @extract:end StateAndWhen-Counter-02
// @extract:end StateAndWhen-Counter-03
// @extract:end StateAndWhen-Counter-04
// @extract:end StateAndWhen-Counter-05

// @extract:begin StateAndWhen-CounterTests-01
// @extract:begin StateAndWhen-CounterTests-02
import kotlin.test.Test
import statoscope.testing.given
// @extract:end StateAndWhen-CounterTests-01
// @extract:end StateAndWhen-CounterTests-02

// @extract:begin StateAndWhen-Counter-02
// @extract:begin StateAndWhen-Counter-03
// @extract:begin StateAndWhen-Counter-04
// @extract:begin StateAndWhen-Counter-05
/** Tutorial 01: Basic Counter with State and When. */
data class CounterState(
    val viewDisplaysTotalCount: Int = 0
)
// @extract:end StateAndWhen-Counter-02

sealed interface CounterWhen {
    data object UserTappedIncrementButton : CounterWhen
    data object UserTappedDecrementButton : CounterWhen
}
// @extract:end StateAndWhen-Counter-03

fun counterUpdate(
    state: CounterState,
    action: CounterWhen,
    effects: EffectsState<CounterWhen>,
    dependencies: Dependencies
): CounterState {
    // @extract:end StateAndWhen-Counter-04
    return when (action) {
        is CounterWhen.UserTappedIncrementButton ->
            state.copy(viewDisplaysTotalCount = state.viewDisplaysTotalCount + 1)
        is CounterWhen.UserTappedDecrementButton ->
            state.copy(viewDisplaysTotalCount = maxOf(0, state.viewDisplaysTotalCount - 1))
    }
    // @extract:begin StateAndWhen-Counter-04
}
// @extract:end StateAndWhen-Counter-04

val counterConfig = ScopeConfig(
    initial = CounterState(),
    update = ::counterUpdate
)
// @extract:end StateAndWhen-Counter-05

// @extract:begin StateAndWhen-CounterTests-01
// @extract:begin StateAndWhen-CounterTests-02
class CounterTest {

    @Test
    fun testBasicCounterFlow() {
        given(counterConfig)
            .thenEquals(CounterState::viewDisplaysTotalCount, 0)
            .on(CounterWhen.UserTappedIncrementButton)
            .thenEquals(CounterState::viewDisplaysTotalCount, 1)
            .on(CounterWhen.UserTappedDecrementButton)
            .thenEquals(CounterState::viewDisplaysTotalCount, 0)
            .on(CounterWhen.UserTappedDecrementButton)
            .thenEquals(CounterState::viewDisplaysTotalCount, 0) // Can't go below 0
            .runTest()
    }
    // @extract:end StateAndWhen-CounterTests-01

    @Test
    fun testMultipleIncrements() {
        given(counterConfig)
            .on(CounterWhen.UserTappedIncrementButton)
            .on(CounterWhen.UserTappedIncrementButton)
            .on(CounterWhen.UserTappedIncrementButton)
            .thenEquals(CounterState::viewDisplaysTotalCount, 3)
            .runTest()
    }
    // @extract:begin StateAndWhen-CounterTests-01
}
// @extract:end StateAndWhen-CounterTests-01
// @extract:end StateAndWhen-CounterTests-02
