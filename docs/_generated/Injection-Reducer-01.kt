import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.map
/** Tutorial 04: a news feed reducer resolving all three dependencies declared above. */
data class NewsFeedListState(
    val loading: Boolean = false,
    val loadedArticles: List<ArticleDto>? = null,
    val favorites: List<Favorite> = emptyList()
)

sealed interface NewsFeedListWhen {
    data object SystemLoadedScope : NewsFeedListWhen
    data class NetworkFetchCompleted(val articles: List<ArticleDto>) : NewsFeedListWhen
    data class UserTappedFavoriteButton(val id: String) : NewsFeedListWhen
}

/**
 * Returns its own natural result, `List<ArticleDto>` — not `NewsFeedListWhen` — same reasoning
 * as Tutorial 02's `NetworkPostEffect`: `update` adapts it with `.map(...)` below.
 */
class NetworkFetchEffect(private val network: NetworkProvider) : Effect<List<ArticleDto>> {
    override suspend fun run(): List<ArticleDto> = network.fetchArticles()
}

fun newsFeedListUpdate(
    state: NewsFeedListState,
    action: NewsFeedListWhen,
    effects: EffectsState<NewsFeedListWhen>,
    dependencies: Dependencies
): NewsFeedListState {
    return when (action) {
        is NewsFeedListWhen.SystemLoadedScope -> {
            val persistence = dependencies.resolve(PersistenceProviderKey)
            val network = dependencies.resolve(NetworkProviderKey)

            effects.enqueue(NetworkFetchEffect(network).map(NewsFeedListWhen::NetworkFetchCompleted))

            state.copy(
                loading = true,
                loadedArticles = null,
                favorites = persistence.get()
            )
        }

        is NewsFeedListWhen.NetworkFetchCompleted ->
            state.copy(loading = false, loadedArticles = action.articles)

        is NewsFeedListWhen.UserTappedFavoriteButton -> {
            val date = dependencies.resolve(DateProviderKey)
            val persistence = dependencies.resolve(PersistenceProviderKey)

            val newFavorites = if (state.favorites.any { it.id == action.id }) {
                state.favorites.filterNot { it.id == action.id }
            } else {
                state.favorites + Favorite(id = action.id, dateAdded = date.currentDate())
            }
            persistence.set(newFavorites)
            state.copy(favorites = newFavorites)
        }
    }
}

val newsFeedListConfig = ScopeConfig(
    initial = NewsFeedListState(),
    update = ::newsFeedListUpdate
)
