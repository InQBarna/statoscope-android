import kotlin.test.assertEquals
import kotlin.test.assertTrue
import statoscope.core.createScope
/**
 * Everything above tests one reducer at a time — deliberately: [statoscope.testing.TestPlan]
 * runs `update` directly, so it has no composed tree to navigate, only ever the one scope it was
 * given (see [statoscope.testing.TestPlan.withSuperstate]'s own doc). Proving the *composition*
 * actually works — that tapping favorite on a real, nested article scope really does reach the
 * list two levels up — needs a real [statoscope.core.ScopeStore], built with [createScope],
 * exactly what's done here by hand. [statoscope.testing.givenStore] (`StoreTestPlan.kt`) now
 * wraps this same shape in a fluent `with()`/`pop()` builder — prefer it for new composed-tree
 * tests; this one stays as Tutorial 05's own worked example of the lower-level primitives it's
 * built from.
 */
class ScopesIntegrationTest {

    @Test
    fun testFavoritingAnArticleReachesTheListWithNoRelayCodeInAnyReducer() {
        val store = createScope(newsFeedConfig)
        store.send(NewsFeedWhen.UserEnabledFeature)

        val listStore = store.childStore(newsFeedListSubScope)!!
        listStore.send(NewsFeedListWhen.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(newsFeedArticleSubScope)!!
        articleStore.send(NewsFeedArticleWhen.UserTappedFavoriteButton)

        // Reached through two levels of composition — nothing here ever called
        // store.send(NewsFeedWhen...) or listStore.send(NewsFeedListWhen.ToggledFavorite(...))
        // directly; onChildAction did that automatically, synchronously, on both hops.
        assertEquals(listOf(Favorite("1", 0)), store.state.list?.favorites)
        assertTrue(articleStore.state.favoritesLimitReached == false)
    }
}
