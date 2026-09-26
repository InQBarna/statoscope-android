import kotlin.test.Test
import statoscope.testing.given
class NewsFeedArticleTest {

    /**
     * Contrast this with the naive style ([statoscope.examples.tutorial02]'s `CloudCounterTest`
     * uses it): `.on(NewsFeedArticleWhen.DetailLoaded(ArticleDetailDto(body = "...")))` would
     * pass even if `newsFeedArticleUpdate`'s real `.map(NewsFeedArticleWhen::DetailLoaded)` call
     * were broken or removed — the test never touches it. `onEffectCompletes` finds the real
     * pending `FetchArticleDetailEffect` and replays its actual map, so breaking that wiring
     * breaks this test.
     */
    @Test
    fun testLoadingDetailReplaysTheRealNetworkMapping() {
        given(ScopeConfig(initial = NewsFeedArticleState(id = "1", title = "Kotlin Coroutines 101"), update = ::newsFeedArticleUpdate))
            .on(NewsFeedArticleWhen.LoadDetail)
            .onEffectCompletes(FetchArticleDetailEffect::class, ArticleDetailDto(body = "Full body of article 1"))
            .thenEquals(NewsFeedArticleState::body, "Full body of article 1")
            .thenEquals(NewsFeedArticleState::loading, false)
            .runTest()
    }
}
