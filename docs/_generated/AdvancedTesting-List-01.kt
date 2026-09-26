import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.map
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
