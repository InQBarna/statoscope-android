package statoscope.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import statoscope.core.ScopeConfig
import statoscope.core.ScopeStore
import statoscope.core.createScope

/**
 * Creates a [ScopeStore] whose effect completions are guaranteed to land on Android's main
 * thread — the Kotlin counterpart to what Swift Statoscope's `@Reducer` macro gives for free by
 * generating a `@MainActor`-bound `Store`. [createScope]'s own default effectScope
 * (`defaultEffectDispatcher`, a single dedicated background thread — see its own doc comment)
 * is a real, tested guarantee too, just not *this* one: nothing about plain `createScope`
 * enforces main-thread delivery, so a screen that needs it has to remember to pass
 * `effectScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` explicitly —
 * easy to get right once and forget on the next screen.
 *
 * Use this instead of [createScope] directly in any real Android app. It has no `effectScope`
 * parameter to override: the whole point is that there's nothing left to get wrong.
 *
 * ```kotlin
 * private val store = createAndroidScope(todoListConfig)
 * ```
 */
fun <State, When> createAndroidScope(
    config: ScopeConfig<State, When>
): ScopeStore<State, When> = createScope(
    config,
    effectScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
)
