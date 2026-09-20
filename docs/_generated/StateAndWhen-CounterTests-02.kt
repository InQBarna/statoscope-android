import kotlin.test.Test
import statoscope.testing.given
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

    @Test
    fun testMultipleIncrements() {
        given(counterConfig)
            .on(CounterWhen.UserTappedIncrementButton)
            .on(CounterWhen.UserTappedIncrementButton)
            .on(CounterWhen.UserTappedIncrementButton)
            .thenEquals(CounterState::viewDisplaysTotalCount, 3)
            .runTest()
    }
}
