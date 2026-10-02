/**
 * The middle level — both a child (of the root, below) and a parent (of the article, above) at
 * once. `@SubState val selectedArticle` declares that parent relationship directly as a
 * property: `:codegen-processor` finds `NewsFeedArticleReducer` as the matching child type and
 * generates the create/mirror/tear-down wiring for it.
 *
 * Favoriting is enforced here, not in the article, because this is the level that actually owns
 * `favorites` — deciding whether there's room for one more needs nothing beyond this reducer's
 * own state. See the note on `SuperstateKey` below `onArticleFavorited` for the general case this
 * example doesn't need.
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
     *
     * A reducer that needs to read an ancestor's state *live*, rather than just react to a
     * child's events, reaches for `SuperstateKey` + `dependencies.resolve(key)` instead (see
     * Tutorial 05): a descendant declares a key, an ancestor's `SubScope` wires itself up to
     * answer it, and anything below resolves it fresh on every call. `@SubState` doesn't yet
     * generate that wiring on its own — there's no annotation-only way to attach a
     * `superstateKey` to a generated child relationship today, so a composition that genuinely
     * needs one still uses the hand-written `SubScope(...)` form for that relationship.
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
