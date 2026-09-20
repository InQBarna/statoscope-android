package statoscope.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * When `true`, enqueued effects are recorded but never actually launched — ports Swift
 * Statoscope's `scopeEffectsDisabledInUnitTests` (`EffectsHandlerImplementation.swift`).
 * Auto-detected once, at class-init time, by checking whether JUnit is on the classpath — true
 * under `./gradlew test`, false in a real running app (JUnit is a test-only dependency, absent
 * from the app's runtime classpath). [statoscope.testing.TestPlan] never launches effects
 * anyway (it runs `update` directly, bypassing [ScopeStore] entirely), so this only matters to
 * a test that calls [createScope] directly — set it to `false` locally (and back to `true`
 * afterward) to prove a specific test genuinely exercises real effect execution, exactly like
 * Swift's own effects tests do.
 */
var scopeEffectsDisabledInUnitTests: Boolean = try {
    Class.forName("org.junit.jupiter.api.Test")
    true
} catch (notFound: ClassNotFoundException) {
    false
}

/**
 * A running scope: holds the current state and accepts events via [send].
 */
interface ScopeStore<State, When> {
    /** The current state — a plain, one-off read. Equivalent to `stateFlow.value`. */
    val state: State

    /**
     * The same state, observable — collect this from a reactive UI (Jetpack Compose's
     * `collectAsState()`, a `ViewModel`'s own `StateFlow`, etc.) instead of polling [state].
     * Necessary, not optional, the moment any state change can originate from an effect
     * completion: that happens asynchronously, on [effectScope]'s own thread — nothing calls
     * back into UI code on its own, so without collecting this flow a UI has no way to learn
     * that `state` changed underneath it. Ports the observability Swift Statoscope gets for
     * free from `@Published`/`ObservableObject` and `statoscope-zustand` gets from `zustand`'s
     * own subscription model.
     */
    val stateFlow: StateFlow<State>

    /**
     * Dispatches [action] through `update`. Ports Swift Statoscope's app-facing
     * `_sendImplementation`: an exception `update` (or an uninstalled-middleware chain) throws
     * is logged under [LogLevel.Errors] and then **swallowed** — it never reaches this call
     * site. One throwing event can't crash whatever called `send`, matching Swift's real
     * contract exactly. Catch it yourself first (wrap `forward(action)` in `addMiddleware`) if
     * you need to actually react to it — logging alone doesn't recover anything.
     */
    fun send(action: When)

    /**
     * Installs [middleware], intercepting every event this store sends to itself. Fluent —
     * returns this same store, mirroring Swift Statoscope's chainable `Store(...).addMiddleWare { ... }`.
     * Only one middleware is supported; a second call replaces the first.
     */
    fun addMiddleware(middleware: Middleware<State, When>): ScopeStore<State, When>
}

/**
 * Shared, dedicated single thread every [ScopeStore] delivers effect completions through by
 * default — this project's stand-in for Swift's `@MainActor`-confined effect delivery
 * (`EffectsHandlerImplementation.swift`'s `safeMainActorSend`). Not literally "the main
 * thread" — there's no UI layer yet to define one — but a single, consistent, named thread
 * shared by every store, so effect-driven state updates are always delivered from the same
 * place rather than whichever thread a shared pool happened to hand out. Once this library
 * grows an Android target, pass `Dispatchers.Main` as a store's `effectScope` instead to get
 * literal main-thread delivery; this default keeps the same guarantee in the meantime.
 */
val defaultEffectDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Statoscope-EffectDispatcher").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Creates a [ScopeStore] from a [ScopeConfig].
 *
 * [effectScope] is where enqueued effects are launched — fire-and-forget, no cancellation
 * support yet. Defaults to [defaultEffectDispatcher] so callers that never enqueue effects
 * (e.g. Tutorial 01's `counterConfig`) don't need to provide one, and callers that do get a
 * consistent delivery thread without asking for it.
 */
fun <State, When> createScope(
    config: ScopeConfig<State, When>,
    effectScope: CoroutineScope = CoroutineScope(SupervisorJob() + defaultEffectDispatcher)
): ScopeStore<State, When> = ScopeStoreImpl(config, effectScope)

@OptIn(StatoscopeInternalApi::class)
private class ScopeStoreImpl<State, When>(
    private val config: ScopeConfig<State, When>,
    private val effectScope: CoroutineScope
) : ScopeStore<State, When> {

    private val dependencies: Dependencies = MapDependencies(config.provide)
    private val logPrefix: String = scopeLogPrefix(config.initial, this)
    private val effectCounter = AtomicInteger(0)

    private val mutableStateFlow = MutableStateFlow(config.initial)
    override val stateFlow: StateFlow<State> = mutableStateFlow.asStateFlow()
    override val state: State get() = mutableStateFlow.value

    private var middleware: Middleware<State, When>? = null

    override fun addMiddleware(middleware: Middleware<State, When>): ScopeStore<State, When> {
        this.middleware = middleware
        return this
    }

    // Effects complete on effectScope's own thread (defaultEffectDispatcher's dedicated thread
    // by default), not necessarily the thread that called the original send() — synchronized so
    // two effects completing concurrently, or an effect completing concurrently with a caller's
    // own send(), can't race on reading and writing `state`.
    @Synchronized
    override fun send(action: When) {
        try {
            val mw = middleware
            if (mw != null) {
                mw(this, action) { forwarded -> process(forwarded) }
            } else {
                process(action)
            }
        } catch (error: Throwable) {
            // Already logged inside process(). Swallowed here, at the outermost, app-facing
            // layer, so one throwing event can't crash whatever called send() — matching
            // Swift's real send() contract. A middleware's own try/catch around forward(action)
            // (see addMiddleware) still runs first and can intercept before it ever reaches here.
        }
    }

    // Exceptions thrown by config.update propagate out through the forward() call above,
    // reaching whichever addMiddleware closure called it — the try { forward(when) } catch
    // { ... } shape that error-handling middleware relies on — before send()'s own outer catch
    // (above) swallows anything a middleware didn't already handle itself.
    private fun process(action: When) {
        val effects = MutableEffectsState<When>()
        StatoscopeLogger.log(LogLevel.When, logPrefix) { describeObject(action) }

        val diffEnabled = StatoscopeLogger.isEnabled(LogLevel.StateDiff)
        val beforeDump = if (diffEnabled || StatoscopeLogger.isEnabled(LogLevel.State)) describeObject(state) else null
        beforeDump?.let { dump -> StatoscopeLogger.log(LogLevel.State, logPrefix) { dump } }

        val newState = try {
            config.update(state, action, effects, dependencies)
        } catch (error: Throwable) {
            StatoscopeLogger.log(LogLevel.Errors, logPrefix) { "‼️ Exception on send: $error" }
            throw error
        }
        mutableStateFlow.value = newState

        if (beforeDump != null) {
            val afterDump = describeObject(state)
            StatoscopeLogger.log(LogLevel.State, logPrefix) { afterDump }
            if (diffEnabled) {
                val diff = diffLines(beforeDump.lines(), afterDump.lines())
                if (diff.isNotEmpty()) {
                    StatoscopeLogger.log(LogLevel.StateDiff, logPrefix) { diff.joinToString("\n") }
                }
            }
        }

        effects.flush().forEach { effect -> launchEffect(effect) }
    }

    private fun launchEffect(effect: Effect<When>) {
        if (scopeEffectsDisabledInUnitTests) return
        val id = effectCounter.incrementAndGet()
        StatoscopeLogger.log(LogLevel.Effects, logPrefix) { "🪃 ↗ [$id] ${describeObject(effect)}" }
        effectScope.launch {
            val result = try {
                effect.run()
            } catch (error: Throwable) {
                StatoscopeLogger.log(LogLevel.Effects, logPrefix) {
                    "🪃 💥 [$id] Unhandled throw: ${describeObject(effect)}\nError: $error"
                }
                throw error
            }
            StatoscopeLogger.log(LogLevel.Effects, logPrefix) { "🪃 ↩ [$id] ${describeObject(effect)}" }
            send(result)
        }
    }
}
