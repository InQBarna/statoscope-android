import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.effect
import statoscope.core.map
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
