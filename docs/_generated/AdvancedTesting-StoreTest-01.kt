import kotlin.test.assertEquals
import statoscope.testing.givenStore
/**
 * Everything above tests one reducer at a time — [statoscope.testing.TestPlan] runs `update`
 * directly, so it never sees a composed tree, only the one scope it was given. Proving the
 * *composition* actually works — that completing the article's effect really does bubble a count
 * up to the list, two real objects apart — needs [statoscope.testing.StoreTestPlan], wrapping a
 * real [statoscope.core.ScopeStore].
 */
class NewsFeedListIntegrationTest {

    private val twoArticles = listOf(
        Article("1", "Kotlin Coroutines 101"),
        Article("2", "Composing Statoscope Scopes")
    )

    @Test
    fun testSelectingAnArticleLoadingItsDetailAndBubblingBackUpToTheList() {
        givenStore(
            ScopeConfig(
                initial = NewsFeedListState(articles = twoArticles),
                update = ::newsFeedListUpdate,
                subScopes = listOf(newsFeedArticleSubScope)
            )
        )
            .on(NewsFeedListWhen.SelectArticle("1"))
            .with(newsFeedArticleSubScope)
            .on(NewsFeedArticleWhen.LoadDetail)
            .completeEffect(FetchArticleDetailEffect::class, ArticleDetailDto(body = "Full body of article 1"))
            .thenEquals(NewsFeedArticleState::body, "Full body of article 1")
            .pop()
            .thenEquals(NewsFeedListState::detailsLoadedCount, 1)
            .runTest()
    }
}
