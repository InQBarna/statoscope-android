import statoscope.codegen.OnChildAction
import statoscope.codegen.Reducer
import statoscope.codegen.SubState
import statoscope.core.Dependencies
import statoscope.core.EffectsState
/**
 * The leaf, as a `@Reducer` object. Two kinds of event live here, handled completely
 * differently: toggling expanded is purely local — nothing outside this one article cares
 * whether its body is shown. Tapping favorite only *reports* the tap; whether it's actually
 * allowed is a decision that belongs to whichever level owns the data needed to make it — see
 * `NewsFeedListReducer` below.
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
