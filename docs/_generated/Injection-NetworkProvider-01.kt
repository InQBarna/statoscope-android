import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.effect
import statoscope.core.map
data class ArticleDto(val id: String, val title: String, val content: String)

/**
 * Fetches the article list. The default simulates a network round-trip with `delay(...)`
 * rather than calling a real host — same reasoning as Tutorial 02's `postToNetwork`: safe to
 * have genuinely running in the background, unlike an unmocked call to a real host.
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
