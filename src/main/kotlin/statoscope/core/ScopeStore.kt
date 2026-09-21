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
     * Only one middleware is supported; a second call replaces the first — including the
     * middleware [ScopeStore] installs itself to wire up a [SubScope] (see [childStore]), so a
     * child created via [ScopeConfig.subScopes] and given its own `addMiddleware` call loses its
     * composition wiring. Not a concern for any tutorial built so far; a real limitation to know
     * about.
     */
    fun addMiddleware(middleware: Middleware<State, When>): ScopeStore<State, When>

    /**
     * The currently active child scope created for [subScope] (see [ScopeConfig.subScopes]), or
     * `null` when that field of [state] is currently absent. Ports `statoscope-zustand`'s
     * `_getChildStore` — the primitive that lets calling code (a test, a UI layer) send actions
     * directly into a specific nested scope, using the exact same [subScope] reference the
     * parent's own [ScopeConfig] was declared with.
     */
    fun <ChildState, ChildWhen> childStore(
        subScope: SubScope<State, When, ChildState, ChildWhen>
    ): ScopeStore<ChildState, ChildWhen>?
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
): ScopeStore<State, When> = ScopeStoreImpl(config, effectScope, inheritedDependencies = null)

/**
 * Resolves [SuperstateKey]s for a store with no ancestor — the root of a composed tree, or any
 * plain, uncomposed [createScope] call. [InjectionKey] resolution never reaches here (see
 * [ScopeStoreImpl.dependencies]), so only the [SuperstateKey] overload can ever actually run.
 */
private object NoAncestorDependencies : Dependencies {
    override fun <T> resolve(key: InjectionKey<T>): T = key.defaultValue
    override fun <T> resolve(key: SuperstateKey<T>): T =
        error("No ancestor provides $key — declare it via a SubScope's superstateKey.")
}

/**
 * A store's real [Dependencies]: [InjectionKey]s always resolve from this scope's own
 * `config.provide` ([ownInjection]) — no ancestor-chain resolution for those, by design (see
 * [InjectionKey]'s doc) — while [SuperstateKey]s fall through to whatever was inherited from an
 * ancestor ([superstateFallback]), or to [NoAncestorDependencies] at the root.
 */
private class CombinedDependencies(
    private val ownInjection: Dependencies,
    private val superstateFallback: Dependencies
) : Dependencies {
    override fun <T> resolve(key: InjectionKey<T>): T = ownInjection.resolve(key)
    override fun <T> resolve(key: SuperstateKey<T>): T = superstateFallback.resolve(key)
}

/**
 * Wraps [base], intercepting reads of exactly [key] with a fresh call to [liveGetter] — never a
 * cached value — and falling through to [base] for everything else, [InjectionKey]s included.
 * Built fresh, one layer per [SubScope.superstateKey], each time a child scope with one is
 * activated (see [ScopeStoreImpl.activateChild]) — since [base] is itself whatever the parent
 * inherited, a chain of these is how a [SuperstateKey] declared several levels up stays
 * resolvable by a descendant several levels further down than the scope that declared it.
 */
private class SuperstateDependencies<T>(
    private val base: Dependencies,
    private val key: SuperstateKey<T>,
    private val liveGetter: () -> T
) : Dependencies {
    override fun <U> resolve(key: InjectionKey<U>): U = base.resolve(key)

    @Suppress("UNCHECKED_CAST")
    override fun <U> resolve(key: SuperstateKey<U>): U =
        if (key === this.key) liveGetter() as U else base.resolve(key)
}

@OptIn(StatoscopeInternalApi::class)
private class ScopeStoreImpl<State, When>(
    private val config: ScopeConfig<State, When>,
    private val effectScope: CoroutineScope,
    inheritedDependencies: Dependencies?
) : ScopeStore<State, When> {

    private val dependencies: Dependencies = CombinedDependencies(
        ownInjection = MapDependencies(config.provide),
        superstateFallback = inheritedDependencies ?: NoAncestorDependencies
    )
    private val logPrefix: String = scopeLogPrefix(config.initial, this)
    private val effectCounter = AtomicInteger(0)

    private val mutableStateFlow = MutableStateFlow(config.initial)
    override val stateFlow: StateFlow<State> = mutableStateFlow.asStateFlow()
    override val state: State get() = mutableStateFlow.value

    private var middleware: Middleware<State, When>? = null

    // Keyed by the exact SubScope reference config.subScopes was declared with — its identity
    // is stable for the store's whole lifetime, so this is a legitimate map key without
    // overriding equals/hashCode. Erased to Any at this level (Kotlin can't express "one map
    // entry per distinct ChildState/ChildWhen pair"); every read/write goes through activateChild
    // /deactivateChild/childStore, each generic over the one SubScope it was handed, so the cast
    // back is always safe — the same pattern MapDependencies uses for InjectionKey.
    private val activeChildren = HashMap<SubScope<*, *, *, *>, ScopeStore<*, *>>()

    override fun addMiddleware(middleware: Middleware<State, When>): ScopeStore<State, When> {
        this.middleware = middleware
        return this
    }

    @Suppress("UNCHECKED_CAST")
    override fun <ChildState, ChildWhen> childStore(
        subScope: SubScope<State, When, ChildState, ChildWhen>
    ): ScopeStore<ChildState, ChildWhen>? =
        activeChildren[subScope] as? ScopeStore<ChildState, ChildWhen>

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

        val previousState = state
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
        syncSubScopes(previousState, newState)

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

    // Runs after every state update, once per declared SubScope — a null→non-null transition on
    // its field activates a new child, non-null→null tears the current one down. A field that
    // stays non-null across the transition is left alone: the child's own store is the one
    // source of truth for its state while active (see activateChild's mirroring), so there's
    // nothing to reconcile. A parent directly overwriting an already-active child's field with a
    // different value (rather than through the child's own actions) isn't specially detected —
    // a known, documented limitation, not a silently broken guarantee: nothing in any tutorial
    // built so far needs it.
    private fun syncSubScopes(previousState: State, newState: State) {
        config.subScopes.forEach { subScope -> syncOne(subScope, previousState, newState) }
    }

    private fun <ChildState, ChildWhen> syncOne(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        previousState: State,
        newState: State
    ) {
        val previousChild = subScope.get(previousState)
        val currentChild = subScope.get(newState)
        if (previousChild == null && currentChild != null) {
            activateChild(subScope, currentChild)
        } else if (previousChild != null && currentChild == null) {
            deactivateChild(subScope)
        }
    }

    private fun <ChildState, ChildWhen> activateChild(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        initial: ChildState
    ) {
        val superstateChain: Dependencies = subScope.superstateKey?.let { key ->
            SuperstateDependencies(dependencies, key) { state }
        } ?: dependencies

        val childStore = ScopeStoreImpl(
            subScope.config.copy(initial = initial),
            effectScope,
            inheritedDependencies = superstateChain
        )

        // The one hook a child needs: forward first (child-first, no interception — see
        // SubScope's doc), then mirror its now-updated state back into this field of our own
        // state, then ask onChildAction whether this particular action should also become one of
        // our own. All three happen synchronously, inline with whatever called childStore.send()
        // — no separate async observation of the child's stateFlow, so a caller that sends to a
        // child and immediately reads the parent's state back sees it already reconciled.
        childStore.addMiddleware { store, action, forward ->
            forward(action)
            synchronized(this) {
                mutableStateFlow.value = subScope.set(mutableStateFlow.value, store.state)
            }
            val delegated = subScope.onChildAction?.invoke(action, store.state, dependencies)
            if (delegated != null) send(delegated)
        }

        activeChildren[subScope] = childStore
    }

    private fun <ChildState, ChildWhen> deactivateChild(subScope: SubScope<State, When, ChildState, ChildWhen>) {
        activeChildren.remove(subScope)
    }
}
