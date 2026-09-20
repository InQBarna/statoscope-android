import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.testing.given
class InjectionTest {

    @Test
    fun testDependencyInjection() {
        val fixedDate = 1000L
        val savedFavorites = mutableListOf<Favorite>()

        val persistenceFake = PersistenceProvider(
            get = { savedFavorites.toList() },
            set = { favorites -> savedFavorites.clear(); savedFavorites.addAll(favorites) }
        )
        val networkFake = NetworkProvider(
            fetchArticles = {
                listOf(
                    ArticleDto(id = "1", title = "Article 1", content = "Content 1"),
                    ArticleDto(id = "2", title = "Article 2", content = "Content 2")
                )
            }
        )

        given(newsFeedListConfig)
            .withProvide(
                listOf(
                    DateProviderKey.pair(DateProvider { fixedDate }),
                    PersistenceProviderKey.pair(persistenceFake),
                    NetworkProviderKey.pair(networkFake)
                )
            )
            .on(NewsFeedListWhen.SystemLoadedScope)
            .thenEquals(NewsFeedListState::loading, true)
            .thenEquals(NewsFeedListState::favorites, emptyList())
            .on(
                NewsFeedListWhen.NetworkFetchCompleted(
                    listOf(
                        ArticleDto(id = "1", title = "Article 1", content = "Content 1"),
                        ArticleDto(id = "2", title = "Article 2", content = "Content 2")
                    )
                )
            )
            .thenEquals(NewsFeedListState::loading, false)
            .then { state -> assertEquals(2, state.loadedArticles?.size) }
            .on(NewsFeedListWhen.UserTappedFavoriteButton("1"))
            .thenEquals(NewsFeedListState::favorites, listOf(Favorite(id = "1", dateAdded = fixedDate)))
            .then { assertEquals(listOf(Favorite(id = "1", dateAdded = fixedDate)), savedFavorites) }
            .runTest()
    }
}
