import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class MiddlewareTest {

    @Test
    fun testMiddlewareInterceptsEvents() {
        val interceptedEvents = mutableListOf<CounterWhen>()

        val counter = createScope(counterConfig)
            .addMiddleware { _, action, forward ->
                interceptedEvents += action
                forward(action)
            }

        counter.send(CounterWhen.UserTappedIncrementButton)
        counter.send(CounterWhen.UserTappedDecrementButton)

        assertEquals(2, interceptedEvents.size)
    }
}
