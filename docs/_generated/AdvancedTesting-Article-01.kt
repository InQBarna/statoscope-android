import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.map
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
