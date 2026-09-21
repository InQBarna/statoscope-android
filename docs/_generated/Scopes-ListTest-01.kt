import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import statoscope.testing.given
class NewsFeedListTest {

    private val twoArticles = listOf(Article("1", "One", "..."), Article("2", "Two", "..."))

    @Test
    fun testSelectingAndDismissingAnArticle() {
        given(ScopeConfig(initial = NewsFeedListState(articles = twoArticles), update = ::newsFeedListUpdate))
            .on(NewsFeedListWhen.UserSelectedArticle("1"))
            .then { state -> assertEquals("One", state.selectedArticle?.title) }
            .on(NewsFeedListWhen.UserDismissedArticle)
            .then { state -> assertNull(state.selectedArticle) }
            .runTest()
    }

    @Test
    fun testTogglingAFavorite() {
        given(ScopeConfig(initial = NewsFeedListState(articles = twoArticles), update = ::newsFeedListUpdate))
            .on(NewsFeedListWhen.ToggledFavorite("1"))
            .thenEquals(NewsFeedListState::favorites, listOf(Favorite("1", 0)))
            .on(NewsFeedListWhen.ToggledFavorite("1"))
            .thenEquals(NewsFeedListState::favorites, emptyList())
            .runTest()
    }
}
