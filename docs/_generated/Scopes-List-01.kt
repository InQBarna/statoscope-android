import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.SuperstateKey
data class Article(val id: String, val title: String, val content: String)
data class Favorite(val id: String, val order: Int)

/**
 * The middle level — both a child (of [NewsFeedState]) and a parent (of [NewsFeedArticleState])
 * at once, exactly like a real screen sitting between a feature root and a detail view. Owns
 * `favorites` as the one place it's ever stored — nothing below duplicates it, which is the
 * whole reason [NewsFeedArticleState] has to read it live instead of keeping its own copy.
 */
data class NewsFeedListState(
    val articles: List<Article> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val selectedArticle: NewsFeedArticleState? = null
)

sealed interface NewsFeedListWhen {
    data class UserSelectedArticle(val id: String) : NewsFeedListWhen
    data object UserDismissedArticle : NewsFeedListWhen
    data class ToggledFavorite(val id: String) : NewsFeedListWhen
}

fun newsFeedListUpdate(
    state: NewsFeedListState,
    action: NewsFeedListWhen,
    effects: EffectsState<NewsFeedListWhen>,
    dependencies: Dependencies
): NewsFeedListState = when (action) {
    is NewsFeedListWhen.UserSelectedArticle -> {
        val article = state.articles.first { it.id == action.id }
        state.copy(
            selectedArticle = NewsFeedArticleState(id = article.id, title = article.title, content = article.content)
        )
    }

    is NewsFeedListWhen.UserDismissedArticle ->
        state.copy(selectedArticle = null)

    is NewsFeedListWhen.ToggledFavorite -> {
        val alreadyFavorited = state.favorites.any { it.id == action.id }
        val newFavorites = if (alreadyFavorited) {
            state.favorites.filterNot { it.id == action.id }
        } else {
            state.favorites + Favorite(id = action.id, order = state.favorites.size)
        }
        state.copy(favorites = newFavorites)
    }
}

/**
 * Composes [NewsFeedArticleState] as this scope's own child. `onChildAction` only reacts to
 * [NewsFeedArticleWhen.UserTappedFavoriteButton] — every other article `When` (including the
 * purely local `UserToggledExpanded`) reaches this closure too (nothing is filtered out before
 * it runs — see [SubScope]'s doc), it just returns `null` for them. `childState` here is already
 * the article's *new* state, computed by [newsFeedArticleUpdate] above — that's what makes
 * reading `childState.favoritesLimitReached` here safe rather than one step stale.
 */
val newsFeedArticleSubScope = SubScope<NewsFeedListState, NewsFeedListWhen, NewsFeedArticleState, NewsFeedArticleWhen>(
    config = ScopeConfig(
        // Never actually used — activation always overrides it with the value this scope's own
        // update() just wrote to `selectedArticle`. See SubScope.config's doc.
        initial = NewsFeedArticleState(id = "", title = "", content = ""),
        update = ::newsFeedArticleUpdate
    ),
    get = { it.selectedArticle },
    set = { state, child -> state.copy(selectedArticle = child) },
    onChildAction = { childWhen, childState, _ ->
        if (childWhen is NewsFeedArticleWhen.UserTappedFavoriteButton && !childState.favoritesLimitReached) {
            NewsFeedListWhen.ToggledFavorite(childState.id)
        } else {
            null
        }
    },
    superstateKey = NewsFeedListSuperstateKey
)

val newsFeedListConfig = ScopeConfig(
    initial = NewsFeedListState(),
    update = ::newsFeedListUpdate,
    subScopes = listOf(newsFeedArticleSubScope)
)
