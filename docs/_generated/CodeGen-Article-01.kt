import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
/**
 * The leaf, as a `@Reducer` object. Compare against `NewsFeedArticleState`/`NewsFeedArticleWhen`/
 * `newsFeedArticleUpdate` in Tutorial 05 — same shape, no `ScopeConfig(...)` to build by hand.
 *
 * One real difference from Tutorial 05's version, not an oversight: that version's
 * `UserTappedFavoriteButton` branch reads `NewsFeedListSuperstateKey` live to decide
 * `favoritesLimitReached` *before* reporting the tap upward. `superstateKey` isn't auto-wired by
 * `@SubState` — deliberately, see `@Reducer`'s own doc — so there's no annotation-only way to
 * attach one to a generated `SubScope` today. This version moves the limit check to
 * `NewsFeedListReducer.onArticleFavorited` below instead, which already has `parentState.favorites`
 * in scope without needing to read anything live at all.
 */
@Reducer
object NewsFeedArticleReducer {
    data class State(
        val id: String = "",
        val title: String = "",
        val content: String = "",
        val isExpanded: Boolean = false
    )

    sealed interface When {
        data object UserToggledExpanded : When
        data object UserTappedFavoriteButton : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserToggledExpanded -> state.copy(isExpanded = !state.isExpanded)
            // Purely a report upward — see NewsFeedListReducer.onArticleFavorited for the decision.
            is When.UserTappedFavoriteButton -> state
        }
}
