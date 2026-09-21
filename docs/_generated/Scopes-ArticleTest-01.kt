import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import statoscope.testing.given
class NewsFeedArticleTest {

    @Test
    fun testTogglingExpandedIsPurelyLocal() {
        given(ScopeConfig(initial = NewsFeedArticleState(id = "1", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .on(NewsFeedArticleWhen.UserToggledExpanded)
            .thenEquals(NewsFeedArticleState::isExpanded, true)
            .on(NewsFeedArticleWhen.UserToggledExpanded)
            .thenEquals(NewsFeedArticleState::isExpanded, false)
            .runTest()
    }

    @Test
    fun testFavoritingIsBlockedOnlyAtTheLimit() {
        val nearLimit = NewsFeedListState(favorites = listOf(Favorite("a", 0), Favorite("b", 1), Favorite("c", 2)))

        given(ScopeConfig(initial = NewsFeedArticleState(id = "d", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .withSuperstate(NewsFeedListSuperstateKey, nearLimit)
            .on(NewsFeedArticleWhen.UserTappedFavoriteButton)
            .thenEquals(NewsFeedArticleState::favoritesLimitReached, true)
            .runTest()
    }

    @Test
    fun testAnAlreadyFavoritedArticleCanAlwaysBeUnfavorited() {
        val atLimitIncludingThisOne = NewsFeedListState(favorites = listOf(Favorite("a", 0), Favorite("b", 1), Favorite("d", 2)))

        given(ScopeConfig(initial = NewsFeedArticleState(id = "d", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .withSuperstate(NewsFeedListSuperstateKey, atLimitIncludingThisOne)
            .on(NewsFeedArticleWhen.UserTappedFavoriteButton)
            .thenEquals(NewsFeedArticleState::favoritesLimitReached, false)
            .runTest()
    }
}
