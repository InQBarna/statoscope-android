/**
 * The root. No `@OnChildAction` at all — composition doesn't require one; only declare it when
 * there's an actual reaction to make.
 */
@Reducer
object NewsFeedReducer {
    data class State(
        val featureEnabled: Boolean = false,
        @SubState val list: NewsFeedListReducer.State? = null
    )

    sealed interface When {
        data object UserEnabledFeature : When
        data object UserDisabledFeature : When
    }

    fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State =
        when (action) {
            is When.UserEnabledFeature -> state.copy(
                featureEnabled = true,
                list = NewsFeedListReducer.State(
                    articles = listOf(
                        Article(id = "1", title = "Kotlin Coroutines 101", content = "..."),
                        Article(id = "2", title = "Composing Statoscope Scopes", content = "...")
                    )
                )
            )

            is When.UserDisabledFeature -> state.copy(featureEnabled = false, list = null)
        }
}
