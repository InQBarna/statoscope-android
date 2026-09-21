import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.SuperstateKey
/**
 * `NewsFeedListSuperstateKey` lives here, ahead of [NewsFeedListState] itself (declared below),
 * because [newsFeedArticleUpdate] needs to name it — Kotlin doesn't require top-level
 * declarations in one file to appear in dependency order, only *this page* reads top to bottom.
 */
val NewsFeedListSuperstateKey = SuperstateKey<NewsFeedListState>("NewsFeedList")

/** How many articles can be favorited at once — enforced where the button lives, see below. */
const val MAX_FAVORITES = 3

/**
 * The leaf. Two kinds of `When` live here, and they're handled completely differently:
 * [NewsFeedArticleWhen.UserToggledExpanded] is genuinely local — nothing outside this one
 * article cares whether its body is expanded — while
 * [NewsFeedArticleWhen.UserTappedFavoriteButton] only *reports* a tap; favoriting itself is
 * [NewsFeedListState]'s concern (see its `onChildAction` below), since the favorited set has to
 * be shared across every article, not duplicated into each one's own state.
 */
data class NewsFeedArticleState(
    val id: String,
    val title: String,
    val content: String,
    val isExpanded: Boolean = false,
    val favoritesLimitReached: Boolean = false
)

sealed interface NewsFeedArticleWhen {
    data object UserToggledExpanded : NewsFeedArticleWhen
    data object UserTappedFavoriteButton : NewsFeedArticleWhen
}

fun newsFeedArticleUpdate(
    state: NewsFeedArticleState,
    action: NewsFeedArticleWhen,
    effects: EffectsState<NewsFeedArticleWhen>,
    dependencies: Dependencies
): NewsFeedArticleState = when (action) {
    is NewsFeedArticleWhen.UserToggledExpanded ->
        state.copy(isExpanded = !state.isExpanded)

    is NewsFeedArticleWhen.UserTappedFavoriteButton -> {
        // A live read of the list's own state — resolved fresh on every call, never a value
        // copied in ahead of time. Only used to decide whether tapping is even allowed; the
        // actual favoriting happens one level up (NewsFeedListState.onChildAction, below).
        val list = dependencies.resolve(NewsFeedListSuperstateKey)
        val alreadyFavorited = list.favorites.any { it.id == state.id }
        state.copy(favoritesLimitReached = !alreadyFavorited && list.favorites.size >= MAX_FAVORITES)
    }
}
