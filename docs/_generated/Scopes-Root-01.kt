import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.SuperstateKey
/**
 * The root — owns nothing but the feature toggle itself. Nothing about favorites, articles, or
 * the currently selected one belongs here; this level's only job is deciding whether
 * [NewsFeedListState] exists at all.
 */
data class NewsFeedState(
    val featureEnabled: Boolean = false,
    val list: NewsFeedListState? = null
)

sealed interface NewsFeedWhen {
    data object UserEnabledFeature : NewsFeedWhen
    data object UserDisabledFeature : NewsFeedWhen
}

fun newsFeedUpdate(
    state: NewsFeedState,
    action: NewsFeedWhen,
    effects: EffectsState<NewsFeedWhen>,
    dependencies: Dependencies
): NewsFeedState = when (action) {
    is NewsFeedWhen.UserEnabledFeature -> state.copy(
        featureEnabled = true,
        list = NewsFeedListState(
            articles = listOf(
                Article(id = "1", title = "Kotlin Coroutines 101", content = "..."),
                Article(id = "2", title = "Composing Statoscope Scopes", content = "...")
            )
        )
    )

    is NewsFeedWhen.UserDisabledFeature ->
        state.copy(featureEnabled = false, list = null)
}

/**
 * No `onChildAction`, no `superstateKey` — composition doesn't require either; only declare them
 * when there's an actual need. The root has none: it never reacts to anything the list does, and
 * the list never needs to read anything of the root's.
 */
val newsFeedListSubScope = SubScope<NewsFeedState, NewsFeedWhen, NewsFeedListState, NewsFeedListWhen>(
    config = newsFeedListConfig,
    get = { it.list },
    set = { state, child -> state.copy(list = child) }
)

val newsFeedConfig = ScopeConfig(
    initial = NewsFeedState(),
    update = ::newsFeedUpdate,
    subScopes = listOf(newsFeedListSubScope)
)
