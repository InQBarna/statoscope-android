/**
 * The middle level. `@SubState val selectedArticle` replaces `newsFeedArticleSubScope`'s entire
 * hand-written `get`/`set`/`config` lens — `:codegen-processor` resolves `NewsFeedArticleReducer`
 * by matching this property's type, and generates the lens itself.
 *
 * `MAX_FAVORITES` is enforced here now (see `NewsFeedArticleReducer`'s doc above for why) — this
 * level already owns `favorites`, so checking its own size needs no live ancestor read at all.
 */
@Reducer
object NewsFeedListReducer {
    const val MAX_FAVORITES = 3

    data class State(
        val articles: List<Article> = emptyList(),
        val favorites: List<Favorite> = emptyList(),
        @SubState val selectedArticle: NewsFeedArticleReducer.State? = null
    )

    sealed interface When {
        data class UserSelectedArticle(val id: String) : When
        data object UserDismissedArticle : When
        data class ToggledFavorite(val id: String) : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserSelectedArticle -> {
                val article = state.articles.first { it.id == action.id }
                state.copy(
                    selectedArticle = NewsFeedArticleReducer.State(id = article.id, title = article.title, content = article.content)
                )
            }

            is When.UserDismissedArticle -> state.copy(selectedArticle = null)

            is When.ToggledFavorite -> {
                val alreadyFavorited = state.favorites.any { it.id == action.id }
                val newFavorites = when {
                    alreadyFavorited -> state.favorites.filterNot { it.id == action.id }
                    state.favorites.size >= MAX_FAVORITES -> state.favorites
                    else -> state.favorites + Favorite(id = action.id, order = state.favorites.size)
                }
                state.copy(favorites = newFavorites)
            }
        }

    /**
     * Matched to `selectedArticle` by type, not by name — this function could be called anything.
     * Compare against `newsFeedArticleSubScope`'s `onChildAction` lambda in Tutorial 05: same
     * decision, now a real, independently-named, independently-testable function instead of a
     * closure embedded in a `SubScope(...)` call.
     */
    @OnChildAction
    fun onArticleFavorited(
        childWhen: NewsFeedArticleReducer.When,
        childState: NewsFeedArticleReducer.State,
        dependencies: Dependencies
    ): When? =
        if (childWhen is NewsFeedArticleReducer.When.UserTappedFavoriteButton) {
            When.ToggledFavorite(childState.id)
        } else {
            null
        }
}
