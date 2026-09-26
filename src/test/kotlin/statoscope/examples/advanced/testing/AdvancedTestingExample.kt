//
// AdvancedTestingExample.kt
// statoscope-kotlin
//
// Examples from Advanced / 01: Advanced Testing
//
package statoscope.examples.advanced.testing

// @extract:begin AdvancedTesting-Article-01
// @extract:begin AdvancedTesting-List-01
import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.map
// @extract:end AdvancedTesting-Article-01
// @extract:end AdvancedTesting-List-01

// @extract:begin AdvancedTesting-ArticleTest-01
import kotlin.test.Test
import statoscope.testing.given
// @extract:end AdvancedTesting-ArticleTest-01

// @extract:begin AdvancedTesting-StoreTest-01
import kotlin.test.assertEquals
import statoscope.testing.givenStore
// @extract:end AdvancedTesting-StoreTest-01

// @extract:begin AdvancedTesting-Article-01
/** The raw payload a real network call would return — nothing here knows it's used for an
 *  article's detail specifically; `newsFeedArticleUpdate` adapts it with `.map()` at the enqueue
 *  site, exactly like Tutorial 02's `postToNetwork`/`Dto`. */
data class ArticleDetailDto(val body: String)

data class NewsFeedArticleState(
    val id: String,
    val title: String,
    val loading: Boolean = false,
    val body: String? = null
)

sealed interface NewsFeedArticleWhen {
    data object LoadDetail : NewsFeedArticleWhen
    data class DetailLoaded(val dto: ArticleDetailDto) : NewsFeedArticleWhen
}

/**
 * A real, *named* effect class — deliberately not built with the `effect { ... }` closure
 * sugar. `completeEffect`/`onEffectCompletes` (see the tests below) need to look an effect up by
 * its own type, and a bare closure has no nameable type of its own to look up: reach for a named
 * `class : Effect<T>` whenever a test needs to target one specifically, the same way
 * `EffectsState.effects`/`cancelEffect` already required one.
 */
private class FetchArticleDetailEffect(private val id: String) : Effect<ArticleDetailDto> {
    override suspend fun run(): ArticleDetailDto {
        delay(10) // Simulate a network round-trip
        return ArticleDetailDto(body = "Full body of article $id")
    }
}

fun newsFeedArticleUpdate(
    state: NewsFeedArticleState,
    action: NewsFeedArticleWhen,
    effects: EffectsState<NewsFeedArticleWhen>,
    dependencies: Dependencies
): NewsFeedArticleState = when (action) {
    is NewsFeedArticleWhen.LoadDetail -> {
        effects.enqueue(FetchArticleDetailEffect(state.id).map(NewsFeedArticleWhen::DetailLoaded))
        state.copy(loading = true)
    }

    is NewsFeedArticleWhen.DetailLoaded ->
        state.copy(loading = false, body = action.dto.body)
}
// @extract:end AdvancedTesting-Article-01

// @extract:begin AdvancedTesting-List-01
data class Article(val id: String, val title: String)

data class NewsFeedListState(
    val articles: List<Article> = emptyList(),
    val selected: NewsFeedArticleState? = null,
    val detailsLoadedCount: Int = 0
)

sealed interface NewsFeedListWhen {
    data class SelectArticle(val id: String) : NewsFeedListWhen
    data object DismissArticle : NewsFeedListWhen
    data object ArticleDetailLoaded : NewsFeedListWhen
}

fun newsFeedListUpdate(
    state: NewsFeedListState,
    action: NewsFeedListWhen,
    effects: EffectsState<NewsFeedListWhen>,
    dependencies: Dependencies
): NewsFeedListState = when (action) {
    is NewsFeedListWhen.SelectArticle -> {
        val article = state.articles.first { it.id == action.id }
        state.copy(selected = NewsFeedArticleState(id = article.id, title = article.title))
    }

    is NewsFeedListWhen.DismissArticle -> state.copy(selected = null)

    is NewsFeedListWhen.ArticleDetailLoaded -> state.copy(detailsLoadedCount = state.detailsLoadedCount + 1)
}

/**
 * Bubbles the article's own `DetailLoaded` up as a running count — every other article `When`
 * (just `LoadDetail` here) reaches this closure too, it simply returns `null` for it.
 */
val newsFeedArticleSubScope = SubScope<NewsFeedListState, NewsFeedListWhen, NewsFeedArticleState, NewsFeedArticleWhen>(
    config = ScopeConfig(
        // Never actually used — activation always overrides it with the value SelectArticle just wrote.
        initial = NewsFeedArticleState(id = "", title = ""),
        update = ::newsFeedArticleUpdate
    ),
    get = { it.selected },
    set = { state, child -> state.copy(selected = child) },
    onChildAction = { childWhen, _, _ ->
        if (childWhen is NewsFeedArticleWhen.DetailLoaded) NewsFeedListWhen.ArticleDetailLoaded else null
    }
)

val newsFeedListConfig = ScopeConfig(
    initial = NewsFeedListState(),
    update = ::newsFeedListUpdate,
    subScopes = listOf(newsFeedArticleSubScope)
)
// @extract:end AdvancedTesting-List-01

// @extract:begin AdvancedTesting-ArticleTest-01
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
// @extract:end AdvancedTesting-ArticleTest-01

// @extract:begin AdvancedTesting-StoreTest-01
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
// @extract:end AdvancedTesting-StoreTest-01
