import kotlin.test.Test
import statoscope.testing.given
class CloudCounterTest {

    @Test
    fun testBasicFlow() {
        given(cloudCounterConfig)
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 0)
            .thenEquals(CloudCounterState::viewShowsLoadingAndDisablesButtons, false)
            // Increment
            .on(CloudCounterWhen.UserTappedIncrementButton)
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 1)
            .thenEquals(CloudCounterState::viewShowsLoadingAndDisablesButtons, true)
            .on(CloudCounterWhen.NetworkPostCompleted(Dto(count = 1)))
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 1)
            .thenEquals(CloudCounterState::viewShowsLoadingAndDisablesButtons, false)
            // Decrement
            .on(CloudCounterWhen.UserTappedDecrementButton)
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 0)
            .thenEquals(CloudCounterState::viewShowsLoadingAndDisablesButtons, true)
            .on(CloudCounterWhen.NetworkPostCompleted(Dto(count = 0)))
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 0)
            .thenEquals(CloudCounterState::viewShowsLoadingAndDisablesButtons, false)
            // Invalid decrement (already at 0, no effect triggered)
            .on(CloudCounterWhen.UserTappedDecrementButton)
            .thenEquals(CloudCounterState::viewDisplaysTotalCount, 0)
            .runTest()
    }
}
