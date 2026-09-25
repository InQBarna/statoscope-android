//
// SubScopeProcessorTest.kt
// :codegen-processor
//
// Self-test: this module applies its OWN processor (see build.gradle.kts's `kspTest`) to the
// @Reducer-annotated objects below, then this test exercises the REAL generated config end to
// end through a real ScopeStore — not just that KSP ran without error. Three real levels (Root
// -> List -> Article) specifically to prove a grandparent can react to a grandchild's event
// through nothing but @OnChildAction — see each annotation's own doc for why no separate
// mechanism is needed for that.
//
package statoscope.codegen.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.createScope

@Reducer
object TestArticleReducer {
    data class State(val id: String = "", val title: String = "")

    enum class When {
        UserTappedFavoriteButton
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State = state
}

@Reducer
object TestListReducer {
    data class State(
        val articles: List<TestArticleReducer.State> = emptyList(),
        @SubState val selectedArticle: TestArticleReducer.State? = null
    )

    sealed interface When {
        data class UserSelectedArticle(val id: String) : When
        // Carries the leaf's own event unchanged — pure relay, no decision made at this level.
        data class ArticleRelay(val articleWhen: TestArticleReducer.When, val articleId: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserSelectedArticle -> state.copy(selectedArticle = state.articles.first { it.id == action.id })
            is When.ArticleRelay -> state
        }

    @OnChildAction
    fun relayArticleEvents(
        childWhen: TestArticleReducer.When,
        childState: TestArticleReducer.State,
        dependencies: Dependencies
    ): When = When.ArticleRelay(childWhen, childState.id)
}

@Reducer
object TestFeedReducer {
    data class State(
        val favorites: List<String> = emptyList(),
        @SubState val list: TestListReducer.State? = null
    )

    sealed interface When {
        data object SystemStarted : When
        data class ToggledFavorite(val articleId: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.SystemStarted -> state.copy(
                list = TestListReducer.State(articles = listOf(TestArticleReducer.State(id = "1", title = "Article One")))
            )
            is When.ToggledFavorite -> state.copy(favorites = state.favorites + action.articleId)
        }

    // Matched to the `list` @SubState property by type — but reaches straight past it into
    // what the article, two levels down, actually did.
    @OnChildAction
    fun onListAction(
        childWhen: TestListReducer.When,
        childState: TestListReducer.State,
        dependencies: Dependencies
    ): When? =
        if (childWhen is TestListReducer.When.ArticleRelay &&
            childWhen.articleWhen == TestArticleReducer.When.UserTappedFavoriteButton
        ) {
            When.ToggledFavorite(childWhen.articleId)
        } else {
            null
        }
}

class SubScopeProcessorTest {

    @Test
    fun testGeneratedConfigWiresThreeRealLevelsWithZeroHandWrittenSubScopes() {
        val store = createScope(TestFeedReducer.config)
        store.send(TestFeedReducer.When.SystemStarted)

        val listStore = store.childStore(testFeedReducerListSubScope)!!
        listStore.send(TestListReducer.When.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(testListReducerSelectedArticleSubScope)!!
        articleStore.send(TestArticleReducer.When.UserTappedFavoriteButton)

        assertEquals(
            listOf("1"), store.state.favorites,
            "a leaf-level tap should have reached the root's favorites two levels up, through generated @OnChildAction wiring alone"
        )
    }

    @Test
    fun testNoMatchingArticleEventLeavesFavoritesAlone() {
        val store = createScope(TestFeedReducer.config)
        store.send(TestFeedReducer.When.SystemStarted)

        val listStore = store.childStore(testFeedReducerListSubScope)!!
        listStore.send(TestListReducer.When.UserSelectedArticle("1"))

        // No favorite tap sent — favorites must stay empty, proving the previous test's green
        // result isn't a false positive from some other path populating it.
        assertEquals(emptyList(), store.state.favorites)
    }
}
