import kotlinx.coroutines.delay
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.effect
import statoscope.core.map
/** Tutorial 04: the current time, injectable so tests can fix it. */
class DateProvider(val currentDate: () -> Long)

val DateProviderKey = InjectionKey(DateProvider { System.currentTimeMillis() }, "DateProvider")
