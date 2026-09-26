//
// StoreTestPlanCompositionTest.kt
// statoscope-kotlin
//
// Stress-tests StoreTestPlan.kt's actual composition machinery, beyond StoreTestPlanTest.kt's
// single with()/pop() hop: a real 3-level tree (Root -> List -> Article, the same shape as
// statoscope-kotlin-examples' newsfeed app), effects enqueued and completed at two different
// levels bubbling through two separate onChildAction hops, withProvide proven to actually reach
// a real reducer's dependencies.resolve() call, and with() failing at the correct depth when an
// intermediate level is active but a deeper one isn't — the case a single-hop test can't tell
// apart from "nothing at all is active."
//
package statoscope.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import statoscope.core.Effect
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.map

// --- Root -> List -> Article, three real levels ---

private data class ArticleState(val loading: Boolean = false, val detail: String? = null)

private sealed interface ArticleWhen {
    data object LoadDetail : ArticleWhen
    data class DetailLoaded(val detail: String) : ArticleWhen
}

/** Never actually run — every completion here is simulated via onOldestEffectCompletes. */
private class FixedDetailEffect(val detail: String) : Effect<ArticleWhen> {
    override suspend fun run(): ArticleWhen = error("must never actually run — completion is simulated")
}

private val articleConfig = ScopeConfig<ArticleState, ArticleWhen>(
    initial = ArticleState(),
    update = { state, action, effects, _ ->
        when (action) {
            is ArticleWhen.LoadDetail -> {
                effects.enqueue(FixedDetailEffect("real-detail"))
                state.copy(loading = true)
            }
            is ArticleWhen.DetailLoaded -> state.copy(loading = false, detail = action.detail)
        }
    }
)

private data class ListState(val selected: ArticleState? = null, val detailLoadedCount: Int = 0)

private sealed interface ListWhen {
    data object SelectArticle : ListWhen
    data object ArticleDetailLoaded : ListWhen
}

private val articleSubScope = SubScope<ListState, ListWhen, ArticleState, ArticleWhen>(
    config = articleConfig,
    get = { it.selected },
    set = { state, child -> state.copy(selected = child) },
    onChildAction = { childWhen, _, _ ->
        if (childWhen is ArticleWhen.DetailLoaded) ListWhen.ArticleDetailLoaded else null
    }
)

private val listConfig = ScopeConfig<ListState, ListWhen>(
    initial = ListState(),
    update = { state, action, _, _ ->
        when (action) {
            is ListWhen.SelectArticle -> state.copy(selected = ArticleState())
            is ListWhen.ArticleDetailLoaded -> state.copy(detailLoadedCount = state.detailLoadedCount + 1)
        }
    },
    subScopes = listOf(articleSubScope)
)

private data class RootState(val list: ListState? = null)

private sealed interface RootWhen {
    data object ShowList : RootWhen
}

private val listSubScope = SubScope<RootState, RootWhen, ListState, ListWhen>(
    config = listConfig,
    get = { it.list },
    set = { state, child -> state.copy(list = child) }
)

private val rootConfig = ScopeConfig<RootState, RootWhen>(
    initial = RootState(),
    update = { state, action, _, _ ->
        when (action) {
            is RootWhen.ShowList -> state.copy(list = ListState())
        }
    },
    subScopes = listOf(listSubScope)
)

// --- A separate, minimal fixture just for withProvide — kept apart from the 3-level tree above
// because InjectionKey resolution is deliberately NOT inherited down a composed tree (see
// InjectionKey's own doc): a root's withProvide only ever reaches its own reducer's
// dependencies.resolve(), never a descendant's, so testing it against a real child would test
// the wrong thing. ---

private val greetingKey = InjectionKey(defaultValue = "Hello")

private data class GreeterState(val greeted: String = "")
private sealed interface GreeterWhen { data object Greet : GreeterWhen }

private val greeterConfig = ScopeConfig<GreeterState, GreeterWhen>(
    initial = GreeterState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is GreeterWhen.Greet -> state.copy(greeted = dependencies.resolve(greetingKey))
        }
    }
)

// --- A separate fixture just for completeEffect() — FixedDetailEffect above returns ArticleWhen
// directly (no .map()), so it can't demonstrate replaying a real mapping chain. ---

private data class NetworkArticleState(val loading: Boolean = false, val title: String? = null)

private sealed interface NetworkArticleWhen {
    data object Load : NetworkArticleWhen
    data class TitleLoaded(val title: String) : NetworkArticleWhen
}

private class RawTitleFetch : Effect<String> {
    override suspend fun run(): String = error("must never actually run — completion is simulated")
}

private val networkArticleConfig = ScopeConfig<NetworkArticleState, NetworkArticleWhen>(
    initial = NetworkArticleState(),
    update = { state, action, effects, _ ->
        when (action) {
            is NetworkArticleWhen.Load -> {
                effects.enqueue(RawTitleFetch().map(NetworkArticleWhen::TitleLoaded))
                state.copy(loading = true)
            }
            is NetworkArticleWhen.TitleLoaded -> state.copy(loading = false, title = action.title)
        }
    }
)

class StoreTestPlanCompositionTest {

    @Test
    fun testThreeLevelNavigationWithEffectsAtTwoLevelsBubblingThroughTwoHops() {
        givenStore(rootConfig)
            .on(RootWhen.ShowList)
            .with(listSubScope)
            .on(ListWhen.SelectArticle)
            .with(articleSubScope)
            .on(ArticleWhen.LoadDetail)
            .thenEffects { effects ->
                val effect = effects.filterIsInstance<FixedDetailEffect>().singleOrNull()
                assertTrue(effect != null, "expected FixedDetailEffect to be pending")
            }
            .onOldestEffectCompletes(ArticleWhen.DetailLoaded("real-detail"))
            .then { state -> assertEquals("real-detail", state.detail) }
            .thenNoEffects()
            .pop()
            .thenEquals(ListState::detailLoadedCount, 1)
            .pop()
            .then { state -> assertEquals(1, state.list?.detailLoadedCount) }
            .runTest()
    }

    @Test
    fun testWithProvideReachesARealReducersDependenciesResolve() {
        givenStore(greeterConfig)
            .withProvide(listOf(greetingKey.pair("Bonjour")))
            .on(GreeterWhen.Greet)
            .thenEquals(GreeterState::greeted, "Bonjour")
            .runTest()
    }

    @Test
    fun testWithProvideDefaultsWhenNeverCalled() {
        givenStore(greeterConfig)
            .on(GreeterWhen.Greet)
            .thenEquals(GreeterState::greeted, "Hello")
            .runTest()
    }

    @Test
    fun testCompleteEffectReplaysTheRealMapFromARawPristineValue() {
        givenStore(networkArticleConfig)
            .on(NetworkArticleWhen.Load)
            .completeEffect(RawTitleFetch::class, "Kotlin Coroutines 101")
            .thenEquals(NetworkArticleState::title, "Kotlin Coroutines 101")
            .thenEquals(NetworkArticleState::loading, false)
            .runTest()
    }

    @Test
    fun testCompleteEffectFailsClearlyWhenNoMatchingEffectIsPending() {
        val plan = givenStore(networkArticleConfig)
            .completeEffect(RawTitleFetch::class, "unused")

        val error = assertFailsWith<IllegalStateException> { plan.runTest() }
        assertTrue(error.message!!.contains("no pending effect"))
    }

    @Test
    fun testWithFailsAtTheDeeperLevelEvenWhenTheShallowerParentIsActive() {
        // ShowList activates the list — but SelectArticle is deliberately never sent, so the
        // article level stays inactive. A bug that only checked activation at the outermost
        // with() would let this slip through; this proves the check happens at every depth.
        val plan = givenStore(rootConfig)
            .on(RootWhen.ShowList)
            .with(listSubScope)
            .with(articleSubScope)
            .on(ArticleWhen.LoadDetail)

        val error = assertFailsWith<IllegalStateException> { plan.runTest() }
        assertTrue(error.message!!.contains("no active child"))
    }
}
