import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.core.createScope
import statoscope.examples.tutorial05.Article
import statoscope.examples.tutorial05.Favorite
/**
 * Favoriting an article reaches the list two levels up with no relay code written in any
 * reducer — proven here against the real, generated `.config`, not a hand-built one.
 */
class CodeGenerationIntegrationTest {

    @Test
    fun testFavoritingAnArticleReachesTheListWithZeroHandWrittenSubScopes() {
        val store = createScope(NewsFeedReducer.config)
        store.send(NewsFeedReducer.When.UserEnabledFeature)

        val listStore = store.childStore(newsFeedReducerListSubScope)!!
        listStore.send(NewsFeedListReducer.When.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(newsFeedListReducerSelectedArticleSubScope)!!
        articleStore.send(NewsFeedArticleReducer.When.UserTappedFavoriteButton)

        assertEquals(listOf(Favorite("1", 0)), store.state.list?.favorites)
    }

    @Test
    fun testFavoritingStopsAtTheLimit() {
        val store = createScope(NewsFeedReducer.config)
        store.send(NewsFeedReducer.When.UserEnabledFeature)
        val listStore = store.childStore(newsFeedReducerListSubScope)!!

        listStore.send(NewsFeedListReducer.When.ToggledFavorite("a"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("b"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("c"))
        listStore.send(NewsFeedListReducer.When.ToggledFavorite("d"))

        assertEquals(3, listStore.state.favorites.size, "a fourth favorite must be silently dropped at the limit")
    }
}
