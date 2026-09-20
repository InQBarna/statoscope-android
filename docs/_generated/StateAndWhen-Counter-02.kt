import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
/** Tutorial 01: Basic Counter with State and When. */
data class CounterState(
    val viewDisplaysTotalCount: Int = 0
)
