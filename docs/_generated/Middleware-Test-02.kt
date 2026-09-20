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

    @Test
    fun testMiddlewareCanHandleErrors() {
        val errorsCaught = mutableListOf<Throwable>()

        val counter = createScope(counterConfig)
            .addMiddleware { _, action, forward ->
                try {
                    forward(action)
                } catch (error: Throwable) {
                    errorsCaught += error
                }
            }

        counter.send(CounterWhen.ErrorCase)

        assertEquals(1, errorsCaught.size)
        assertTrue(errorsCaught.first() is IllegalStateException)
    }
}
