import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import statoscope.testing.given
class NewsFeedTest {

    @Test
    fun testEnablingTheFeatureCreatesTheList() {
        given(ScopeConfig(initial = NewsFeedState(), update = ::newsFeedUpdate))
            .on(NewsFeedWhen.UserEnabledFeature)
            .then { state -> assertEquals(2, state.list?.articles?.size) }
            .on(NewsFeedWhen.UserDisabledFeature)
            .then { state -> assertNull(state.list) }
            .runTest()
    }
}
