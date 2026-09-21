import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.effect
import statoscope.core.map
/** A remote counter's payload — what actually gets persisted, over the network, on each change. */
data class Dto(val count: Int)

/** Tutorial 02: CloudCounter — a counter whose value is stored remotely via a network API. */
data class CloudCounterState(
    val viewDisplaysTotalCount: Int = 0,
    val viewShowsLoadingAndDisablesButtons: Boolean = false
)
