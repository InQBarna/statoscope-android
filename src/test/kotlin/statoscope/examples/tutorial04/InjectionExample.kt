//
// InjectionExample.kt
// statoscope-kotlin
//
// Examples from Tutorial 04: Dependency Injection
//
package statoscope.examples.tutorial04

// @extract:begin Injection-DateProvider-01
// @extract:begin Injection-PersistenceProvider-01
// @extract:begin Injection-NetworkProvider-01
// @extract:begin Injection-Reducer-01
import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.map
// @extract:end Injection-DateProvider-01
// @extract:end Injection-PersistenceProvider-01
// @extract:end Injection-NetworkProvider-01
// @extract:end Injection-Reducer-01

// @extract:begin Injection-Test-01
import kotlin.test.Test
import kotlin.test.assertEquals
import statoscope.testing.given
// @extract:end Injection-Test-01

// @extract:begin Injection-DateProvider-01
/** Tutorial 04: the current time, injectable so tests can fix it. */
class DateProvider(val currentDate: () -> Long)

val DateProviderKey = InjectionKey(DateProvider { System.currentTimeMillis() }, "DateProvider")
// @extract:end Injection-DateProvider-01

// @extract:begin Injection-PersistenceProvider-01
data class Favorite(val id: String, val dateAdded: Long)

/**
 * Reads and writes the favorited article ids. The default keeps them only in memory — there's
 * no platform storage API to lean on in plain Kotlin/JVM the way Swift's default leans on
 * `UserDefaults`, and that implementation detail isn't the point being taught here.
 */
class PersistenceProvider(val get: () -> List<Favorite>, val set: (List<Favorite>) -> Unit)

private val inMemoryFavorites = mutableListOf<Favorite>()

val PersistenceProviderKey = InjectionKey(
    PersistenceProvider(
        get = { inMemoryFavorites.toList() },
        set = { favorites -> inMemoryFavorites.clear(); inMemoryFavorites.addAll(favorites) }
    ),
    "PersistenceProvider"
)
// @extract:end Injection-PersistenceProvider-01

// @extract:begin Injection-NetworkProvider-01
data class ArticleDto(val id: String, val title: String, val content: String)

/**
 * Fetches the article list. The default simulates a network round-trip with `delay(...)`
 * rather than calling a real host — same reasoning as Tutorial 02's `NetworkPostEffect`: safe
 * to have genuinely running in the background, unlike an unmocked call to a real host.
 */
class NetworkProvider(val fetchArticles: suspend () -> List<ArticleDto>)

val NetworkProviderKey = InjectionKey(
    NetworkProvider(
        fetchArticles = {
            delay(10) // Simulate a network round-trip
            emptyList()
        }
    ),
    "NetworkProvider"
)
// @extract:end Injection-NetworkProvider-01

// @extract:begin Injection-Reducer-01
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
// @extract:end Injection-Reducer-01

// @extract:begin Injection-Test-01
class InjectionTest {

    @Test
    fun testDependencyInjection() {
        val fixedDate = 1000L
        val savedFavorites = mutableListOf<Favorite>()

        val persistenceFake = PersistenceProvider(
            get = { savedFavorites.toList() },
            set = { favorites -> savedFavorites.clear(); savedFavorites.addAll(favorites) }
        )
        val networkFake = NetworkProvider(
            fetchArticles = {
                listOf(
                    ArticleDto(id = "1", title = "Article 1", content = "Content 1"),
                    ArticleDto(id = "2", title = "Article 2", content = "Content 2")
                )
            }
        )

        given(newsFeedListConfig)
            .withProvide(
                listOf(
                    DateProviderKey.pair(DateProvider { fixedDate }),
                    PersistenceProviderKey.pair(persistenceFake),
                    NetworkProviderKey.pair(networkFake)
                )
            )
            .on(NewsFeedListWhen.SystemLoadedScope)
            .thenEquals(NewsFeedListState::loading, true)
            .thenEquals(NewsFeedListState::favorites, emptyList())
            .on(
                NewsFeedListWhen.NetworkFetchCompleted(
                    listOf(
                        ArticleDto(id = "1", title = "Article 1", content = "Content 1"),
                        ArticleDto(id = "2", title = "Article 2", content = "Content 2")
                    )
                )
            )
            .thenEquals(NewsFeedListState::loading, false)
            .then { state -> assertEquals(2, state.loadedArticles?.size) }
            .on(NewsFeedListWhen.UserTappedFavoriteButton("1"))
            .thenEquals(NewsFeedListState::favorites, listOf(Favorite(id = "1", dateAdded = fixedDate)))
            .then { assertEquals(listOf(Favorite(id = "1", dateAdded = fixedDate)), savedFavorites) }
            .runTest()
    }
}
// @extract:end Injection-Test-01
