package statoscope.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

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
     * Overrides [key] with [value] on this store, from now on — explicit injection after the
     * store already exists, for whatever wasn't known yet at [createScope]/[ScopeConfig.provide]
     * time (ports Swift Statoscope's `injectObject(_:)`, callable any time on a live scope, not
     * just at construction). Already-active children aren't rebuilt or notified — nothing needs
     * to be: their own [Dependencies] chain resolves through this store's live, mutable
     * injection map on every call (see [MapDependencies.override]), so the very next `resolve(key)`
     * anywhere in this store's subtree that doesn't already override [key] itself sees [value].
     * Wins over this store's own [ScopeConfig.provide] entry for [key] too, if it had one.
     */
    fun <T> inject(key: InjectionKey<T>, value: T)

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

    /**
     * Effects currently tracked as ongoing — launched for real, or held back only as bookkeeping
     * because [scopeEffectsDisabledInUnitTests] is set (see [launchEffect]) — oldest first, as
     * their [Effect.pristine] type. Exists so [statoscope.testing.StoreTestPlan] can assert on a
     * *real* composed store's pending effects the same way [statoscope.testing.TestPlan] already
     * does for a single reducer's [EffectsState.effects].
     */
    @StatoscopeInternalApi
    fun pendingEffects(): List<Effect<*>>

    /**
     * Completes the oldest tracked effect (see [pendingEffects]) with [result] — cancelling its
     * real running `Job` first if one was actually launched, then calling [send] directly, never
     * running the effect's own [Effect.run] for real. Mirrors Swift's
     * `effectsState._cancelOlderEffect()` + `_unsafeSendImplementation(effectResult)`
     * (`WHEN_OlderEffectCompletes`). Returns `false`, sending nothing, when nothing is pending.
     */
    @StatoscopeInternalApi
    fun completeOldestEffect(result: When): Boolean

    /**
     * Completes the first tracked effect (see [pendingEffects]) whose pristine type is
     * [effectType] with [pristineResult] — a value shaped like *that effect's own* natural
     * result, replayed through its real `.map()` chain via [completePristine], never a
     * tester-guessed final [When] value. Mirrors Swift's `WHEN_EffectCompletes<EffectType>`.
     * Returns `false`, sending nothing, when no pending effect matches.
     */
    @StatoscopeInternalApi
    fun completeEffect(effectType: KClass<out Effect<*>>, pristineResult: Any?): Boolean

    /**
     * Same as [completeEffect], but simulates [effectType]'s pristine effect THROWING [error]
     * instead of succeeding — replayed through its real `.map()`/`.mapToResult()` chain via
     * [completePristineFailure], never running [Effect.run] for real. Mirrors Swift's
     * `WHEN_EffectFails<EffectType>`. Returns `false`, sending nothing, when no pending effect
     * matches. **Throws [error] itself**, to the caller, if nothing in that effect's chain
     * actually catches it (no `mapToResult()` anywhere between the pristine effect and the
     * enqueue site) — same as a real failure would do in that shape: propagate all the way out.
     */
    @StatoscopeInternalApi
    fun completeEffectWithFailure(effectType: KClass<out Effect<*>>, error: Throwable): Boolean
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
 * [effectScope] is where enqueued effects are launched. Defaults to [defaultEffectDispatcher] so
 * callers that never enqueue effects (e.g. Tutorial 01's `counterConfig`) don't need to provide
 * one, and callers that do get a consistent delivery thread without asking for it. Real
 * cancellation: an [EffectsState.cancelEffect]/[EffectsState.cancelAllEffects] call from
 * `update()` cancels the actual running `Job`, not just bookkeeping.
 */
fun <State, When> createScope(
    config: ScopeConfig<State, When>,
    effectScope: CoroutineScope = CoroutineScope(SupervisorJob() + defaultEffectDispatcher)
): ScopeStore<State, When> = ScopeStoreImpl(config, effectScope, inheritedDependencies = null)

/**
 * The terminal link in the ancestor chain — reached once no scope from the root down to the
 * caller declared an override for either kind of key. For [InjectionKey], that means
 * [InjectionKey.defaultValue] genuinely applies; for [SuperstateKey], it means nothing on the
 * path declared it at all, which is always a programmer error (see [SuperstateKey]'s own doc).
 */
private object NoAncestorDependencies : Dependencies {
    override fun <T> resolve(key: InjectionKey<T>): T = key.defaultValue
    override fun <T> resolve(key: SuperstateKey<T>): T =
        error("No ancestor provides $key — declare it via a SubScope's superstateKey.")
    // Unreachable in practice: every real scope's chain starts with its own CombinedDependencies,
    // which handles inject() itself before ever delegating here.
    override fun <T> inject(key: InjectionKey<T>, value: T): Unit =
        error("No scope to inject $key into.")
}

/**
 * A store's real [Dependencies]: an [InjectionKey] resolves from this scope's own `config.provide`
 * ([ownInjection]) if present there, otherwise falls through to [ancestorFallback] — whatever was
 * inherited from the parent, itself a [CombinedDependencies] (or a [SuperstateDependencies]
 * wrapping one) all the way up to [NoAncestorDependencies] at the root — so a composition root's
 * `provide` reaches every descendant with zero plumbing in between, and the *nearest* ancestor
 * that overrides a key wins over a farther one. [SuperstateKey]s always go straight to
 * [ancestorFallback] — they're never looked up locally at all, only via a [SubScope.superstateKey]
 * declared somewhere on the path (see [SuperstateDependencies]).
 */
@OptIn(StatoscopeInternalApi::class)
private class CombinedDependencies(
    private val ownInjection: MapDependencies,
    private val ancestorFallback: Dependencies
) : Dependencies {
    override fun <T> resolve(key: InjectionKey<T>): T =
        if (ownInjection.hasOwnProvide(key)) ownInjection.resolve(key) else ancestorFallback.resolve(key)
    override fun <T> resolve(key: SuperstateKey<T>): T = ancestorFallback.resolve(key)

    // Same as ScopeStore.inject: this scope's own injection map, which children resolve through.
    override fun <T> inject(key: InjectionKey<T>, value: T) = ownInjection.override(key, value)

    fun findSuperstateOwner(key: SuperstateKey<*>): ScopeStoreImpl<*, *>? = ancestorFallback.findSuperstateOwnerOrNull(key)
}

/**
 * Wraps [base], intercepting reads of exactly [key] with a fresh read of [owner]'s own `state` —
 * never a cached value — and falling through to [base] for everything else, [InjectionKey]s
 * included. Built fresh, one layer per [SubScope.superstateKey], each time a child scope with one
 * is activated (see [ScopeStoreImpl.activateChild]) — since [base] is itself whatever the parent
 * inherited, a chain of these is how a [SuperstateKey] declared several levels up stays
 * resolvable by a descendant several levels further down than the scope that declared it.
 *
 * [owner] is kept as a direct reference (not just a `() -> T` getter closure) so
 * [findSuperstateOwner] can hand back the actual [ScopeStoreImpl] a descendant needs to register
 * with for the live-push cascade (see [ScopeConfig.liveAncestorKey]) — the resolve path and the
 * registration path both need to find the exact same owner, however many levels up it sits.
 */
private class SuperstateDependencies<T>(
    private val base: Dependencies,
    private val key: SuperstateKey<T>,
    private val owner: ScopeStoreImpl<*, *>
) : Dependencies {
    override fun <U> resolve(key: InjectionKey<U>): U = base.resolve(key)

    override fun <U> inject(key: InjectionKey<U>, value: U) = base.inject(key, value)

    @Suppress("UNCHECKED_CAST")
    override fun <U> resolve(key: SuperstateKey<U>): U =
        if (key === this.key) owner.state as U else base.resolve(key)

    fun findSuperstateOwner(key: SuperstateKey<*>): ScopeStoreImpl<*, *>? =
        if (key === this.key) owner else base.findSuperstateOwnerOrNull(key)
}

/**
 * Walks a [Dependencies] chain (see [SuperstateDependencies]/[CombinedDependencies]) to find the
 * real [ScopeStoreImpl] that owns [key] — possibly several levels up from whatever scope holds
 * this [Dependencies] instance (a skip-level [SuperstateKey], inherited unchanged through an
 * intermediate [SubScope] that declares no `superstateKey` of its own). `null` when nothing on
 * the chain declares [key] at all — the same "no ancestor provides this" case
 * [NoAncestorDependencies]'s own `resolve` throws on, but a lookup here is allowed to just come
 * back empty: a scope's own [ScopeConfig.liveAncestorKey] being wrong is a real codegen/config
 * bug, but this function itself needs to stay total to be usable for the lookup that would
 * *detect* that bug.
 */
private fun Dependencies.findSuperstateOwnerOrNull(key: SuperstateKey<*>): ScopeStoreImpl<*, *>? = when (this) {
    is SuperstateDependencies<*> -> findSuperstateOwner(key)
    is CombinedDependencies -> findSuperstateOwner(key)
    else -> null
}

@OptIn(StatoscopeInternalApi::class)
private class ScopeStoreImpl<State, When>(
    private val config: ScopeConfig<State, When>,
    private val effectScope: CoroutineScope,
    inheritedDependencies: Dependencies?
) : ScopeStore<State, When> {

    // Named, not inlined into `dependencies` below, so `inject()` has something to mutate.
    private val ownInjection = MapDependencies(config.provide)
    private val dependencies: Dependencies = CombinedDependencies(
        ownInjection = ownInjection,
        ancestorFallback = inheritedDependencies ?: NoAncestorDependencies
    )
    private val logPrefix: String = scopeLogPrefix(config.initial, this)
    private val effectIdCounter = AtomicLong(0)

    private val mutableStateFlow = MutableStateFlow(config.initial)
    override val stateFlow: StateFlow<State> = mutableStateFlow.asStateFlow()
    override val state: State get() = mutableStateFlow.value

    private var middleware: Middleware<State, When>? = null

    // job is null exactly when scopeEffectsDisabledInUnitTests held this effect back from ever
    // actually launching (see launchEffect) — bookkeeping-only, nothing to cancel. enqueued is
    // the actual object passed to enqueue() (possibly .map()-wrapped, with its transform chain
    // intact) — needed by completeEffect(); pristine is its walked-back type, used to look it up.
    private class RunningEffect(val enqueued: Effect<*>, val pristine: Effect<*>, val job: Job?)

    // Positive ids only — MutableEffectsState reserves negative ones for effects enqueued but
    // not yet actually launched within a single update() call (see its own doc). Mutated only
    // from inside send()'s lock (process()/launchEffect() below) or, for the self-removal on
    // completion, under an explicit synchronized(this) — see launchEffect.
    private val runningEffects = LinkedHashMap<Long, RunningEffect>()

    // Keyed by the exact SubScope reference config.subScopes was declared with — its identity
    // is stable for the store's whole lifetime, so this is a legitimate map key without
    // overriding equals/hashCode. Erased to Any at this level (Kotlin can't express "one map
    // entry per distinct ChildState/ChildWhen pair"); every read/write goes through activateChild
    // /deactivateChild/childStore, each generic over the one SubScope it was handed, so the cast
    // back is always safe — the same pattern MapDependencies uses for InjectionKey.
    private val activeChildren = HashMap<SubScope<*, *, *, *>, ScopeStore<*, *>>()

    // Null for the root of a composed tree (or any plain, uncomposed createScope call); set once,
    // in activateChild, when this store is created as someone's child. Walked by notifyAncestors
    // to give every ancestor — not just the direct parent — a chance to react to this store's own
    // events via its own config.onDescendantAction, the runtime-broadcast counterpart to
    // SubScope.onChildAction's one-hop, compile-time-validated relay.
    private var parent: ScopeStoreImpl<*, *>? = null

    // Callbacks THIS store owes a push to — one per descendant (at any depth) whose own
    // config.liveAncestorKey names a SuperstateKey this store owns, registered in activateChild
    // and removed in deactivateChild. CopyOnWriteArrayList, not a synchronized MutableList,
    // deliberately: notifyLiveDependents() runs from inside process(), itself already inside
    // send()'s own lock on THIS store — iterating a list guarded by a *different* lock (even this
    // store's own, re-entered for the iteration) is exactly the kind of thing that could someday
    // grow into a cross-thread lock-ordering deadlock the moment a callback does anything other
    // than a lock-free write (see reDeriveFromLiveAncestor's own doc for the concrete scenario
    // this is guarding against). A lock-free collection sidesteps the whole question.
    private val liveDependents = CopyOnWriteArrayList<() -> Unit>()

    // Set at activation time, only when config.liveAncestorKey is non-null — the exact callback
    // to remove from the owner's own liveDependents when this store is torn down (deactivateChild).
    // Keeping the closure itself (not just a flag) is what makes removal exact: CopyOnWriteArrayList.remove
    // needs the same reference, not merely "some callback", in case several siblings or several
    // descendants of the same owner are registered at once.
    private var liveAncestorUnregister: (() -> Unit)? = null

    // Activates any child already non-null in `config.initial` — covers a parent whose own
    // update() populates a child's state with an already-non-null grandchild (or deeper) in one
    // shot, rather than one level at a time across separate events the way every tutorial/
    // example built so far does. syncSubScopes (below) only reacts to a null<->non-null
    // TRANSITION observed during process(); nothing built this way was ever null first FROM
    // THIS STORE'S OWN PERSPECTIVE, so without this, such a descendant's own ScopeStore would
    // never be created — its state would say it exists, but childStore(...) would return null
    // forever (confirmed by ScopeStoreTest.testCreatingAChildWithAnAlreadyNonNullGrandchildActivatesTheGrandchildToo
    // before this fix). Ports Swift's recursive child-slot initialization at Store-creation time
    // (Store.swift's _applyChildSlotsRecursively) — this runs for every ScopeStoreImpl, so a
    // multi-level pre-populated hierarchy activates recursively for free: each activateChild
    // call below constructs a new ScopeStoreImpl, which runs this same init block for ITS OWN
    // subScopes in turn.
    init {
        config.subScopes.forEach { subScope -> activateIfAlreadyPresent(subScope) }
    }

    private fun <ChildState, ChildWhen> activateIfAlreadyPresent(subScope: SubScope<State, When, ChildState, ChildWhen>) {
        val initialChild = subScope.get(config.initial) ?: return
        activateChild(subScope, initialChild)
    }

    override fun addMiddleware(middleware: Middleware<State, When>): ScopeStore<State, When> {
        this.middleware = middleware
        return this
    }

    @Suppress("UNCHECKED_CAST")
    override fun <ChildState, ChildWhen> childStore(
        subScope: SubScope<State, When, ChildState, ChildWhen>
    ): ScopeStore<ChildState, ChildWhen>? =
        activeChildren[subScope] as? ScopeStore<ChildState, ChildWhen>

    @Synchronized
    override fun pendingEffects(): List<Effect<*>> =
        runningEffects.values.map { it.pristine }

    @Synchronized
    override fun completeOldestEffect(result: When): Boolean {
        val oldest = runningEffects.entries.firstOrNull() ?: return false
        runningEffects.remove(oldest.key)
        oldest.value.job?.cancel()
        send(result)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun completeEffect(effectType: KClass<out Effect<*>>, pristineResult: Any?): Boolean {
        val found = runningEffects.entries.firstOrNull { effectType.isInstance(it.value.pristine) } ?: return false
        runningEffects.remove(found.key)
        found.value.job?.cancel()
        send(found.value.enqueued.completePristine(pristineResult) as When)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun completeEffectWithFailure(effectType: KClass<out Effect<*>>, error: Throwable): Boolean {
        val found = runningEffects.entries.firstOrNull { effectType.isInstance(it.value.pristine) } ?: return false
        runningEffects.remove(found.key)
        found.value.job?.cancel()
        send(found.value.enqueued.completePristineFailure(error) as When)
        return true
    }

    override fun <T> inject(key: InjectionKey<T>, value: T) {
        ownInjection.override(key, value)
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
        val effects = MutableEffectsState<When>(
            ongoing = runningEffects.entries.map { (id, running) -> id to running.pristine }
        )
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
        // Split in two passes around the stateFlow publish below, not run as one
        // syncSubScopes(...) call the way this used to read: a newly-activated child must exist
        // in activeChildren BEFORE any collector of stateFlow can observe newState (where its
        // field just went non-null) — otherwise a collector resumed synchronously inline on this
        // same call stack (confirmed via :android's ConnectedScreenTest.kt Molecule-driven tests, using
        // UnconfinedTestDispatcher — the same inline-resume shape kotlinx-coroutines-android's
        // Dispatchers.Main.immediate uses whenever send() is already called from the main
        // thread) sees childStore(subScope) return null for a child whose own state field is
        // already non-null, and nothing ever triggers a further recomposition to correct it,
        // since newState's own value never changes again on its own. Deactivation doesn't have
        // this hazard — ConnectedChild checks subScope.get(parentState) first and never reaches
        // childStore(subScope) once that's null — so removal can stay a single pass, after
        // publish, same as before.
        //
        // The finishing half (installing each new child's middleware, the live-ancestor push,
        // defaultTrigger) stays AFTER the publish, deliberately: that logic recursively calls
        // back into this store (onChildAction delegation, notifyAncestors) expecting `state` —
        // mutableStateFlow.value — to already be newState, exactly as it did before this split.
        val finishActivations = preActivateNewChildren(previousState, newState)
        mutableStateFlow.value = newState
        finishActivations.forEach { finish -> finish() }
        deactivateRemovedChildren(previousState, newState)
        notifyLiveDependents()
        notifyAncestors(action as Any, newState as Any)

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

        effects.flushCancelledExistingIds().forEach { id -> runningEffects.remove(id)?.job?.cancel() }
        effects.flushNewlyEnqueued().forEach { effect -> launchEffect(effect) }
    }

    private fun launchEffect(effect: Effect<When>) {
        val id = effectIdCounter.incrementAndGet()
        val pristineEffect = effect.pristine()
        StatoscopeLogger.log(LogLevel.Effects, logPrefix) { "🪃 ↗ [$id] ${describeObject(effect)}" }

        // Bookkeeping is recorded regardless of scopeEffectsDisabledInUnitTests — a test needs to
        // see this effect as pending (pendingEffects()/completeOldestEffect()) even though it's
        // never actually launched under the default JUnit-detected setting. Only the real
        // effectScope.launch below is skipped.
        if (scopeEffectsDisabledInUnitTests) {
            runningEffects[id] = RunningEffect(enqueued = effect, pristine = pristineEffect, job = null)
            return
        }

        val job = effectScope.launch {
            val result = try {
                effect.run()
            } catch (error: Throwable) {
                StatoscopeLogger.log(LogLevel.Effects, logPrefix) {
                    "🪃 💥 [$id] Unhandled throw: ${describeObject(effect)}\nError: $error"
                }
                synchronized(this@ScopeStoreImpl) { runningEffects.remove(id) }
                throw error
            }
            StatoscopeLogger.log(LogLevel.Effects, logPrefix) { "🪃 ↩ [$id] ${describeObject(effect)}" }
            synchronized(this@ScopeStoreImpl) { runningEffects.remove(id) }
            send(result)
        }
        runningEffects[id] = RunningEffect(enqueued = effect, pristine = pristineEffect, job = job)
    }

    // Walks every ancestor above this store — not just the direct parent — giving each a chance
    // to react to this store's own event via its own config.onDescendantAction. Called from
    // process() right after this store's own state is committed, so every ancestor sees the
    // *real* originating descendant's already-updated state directly (never a mirrored copy, and
    // never through an intermediate level's own onChildAction relay) — exactly like Swift's
    // event.child always being the genuine descendant, never a proxy. Starting at `parent` rather
    // than `this` means a store's own self-sent event never triggers its own
    // onDescendantAction — only a genuine descendant's does, matching Swift's
    // `callParentEnclosedHierarchialUpdate` (the walk begins at the sender's parent, not the
    // sender itself).
    private fun notifyAncestors(descendantWhen: Any, descendantState: Any) {
        var current = parent
        while (current != null) {
            current.reactToDescendant(descendantWhen, descendantState)
            current = current.parent
        }
    }

    // Invoked on `this` (an ancestor) by some descendant's notifyAncestors walk. A null
    // onDescendantAction (the common case: this reducer doesn't react to anything beyond its own
    // direct children, if that) is a no-op. A non-null result is sent through this ancestor's own
    // send() — recursive, synchronous, and safe: send() synchronizes on `this`'s own lock, a
    // different monitor than whichever descendant's process() is still running further down the
    // call stack.
    @Suppress("UNCHECKED_CAST")
    private fun reactToDescendant(descendantWhen: Any, descendantState: Any) {
        val handler = config.onDescendantAction ?: return
        val delegated = handler(descendantWhen, descendantState, state, dependencies) ?: return
        send(delegated as When)
    }

    // Registration side of the live-ancestor cascade (see ScopeConfig.liveAncestorKey) — called
    // on THIS store when it's the real owner of some descendant's key, from that descendant's own
    // activateChild (possibly several levels down). Internal, not private: called from a
    // *different* ScopeStoreImpl instance than `this`, same pattern childStore()/activateChild
    // already establish for cross-instance access within this one file.
    internal fun registerLiveDependent(callback: () -> Unit) {
        liveDependents += callback
    }

    internal fun unregisterLiveDependent(callback: () -> Unit) {
        liveDependents -= callback
    }

    // Called from process(), right after this store's own new state is committed — every
    // registered descendant gets pushed a fresh, re-derived value synchronously, as part of THIS
    // same call, not on its own schedule. Each callback closes over a specific descendant's own
    // reDeriveFromLiveAncestor() (see activateChild) — iterating liveDependents itself never
    // touches any lock, descendant or otherwise (see that field's own doc); only
    // reDeriveFromLiveAncestor's lock-free MutableStateFlow.update keeps that true all the way
    // through.
    private fun notifyLiveDependents() {
        liveDependents.forEach { it() }
    }

    // Re-derives THIS store's own state from config.deriveFromLiveAncestor, called two ways: once
    // at activation (activateChild, before this store has processed a single event of its own —
    // closes the OTHER half of @ParentState's old staleness bug, the one even before any tap),
    // and every time the ancestor registerLiveDependent registered this callback with commits a
    // new state of its own (via notifyLiveDependents, above).
    //
    // Deliberately NOT @Synchronized (does not acquire THIS store's own lock) — a real,
    // cross-thread deadlock risk, not a theoretical one: send() already synchronizes on `this`,
    // and the existing child-delegates-to-parent path (activateChild's own middleware,
    // onChildAction) acquires a CHILD's lock first, then the PARENT's — exactly the opposite
    // order this cascade would need (ANCESTOR's lock, already held via its own process(), THEN
    // the descendant's) if this acquired a lock here. Two different threads — one delivering an
    // effect completion into the ancestor, one delivering a UI event into the descendant that
    // delegates back up — taking those two opposite orders concurrently is a textbook deadlock.
    // MutableStateFlow.update {} is lock-free (CAS-based): no monitor acquired on this store at
    // all, so no ordering to invert in the first place, at the cost of accepting that a genuinely
    // concurrent send() on this exact store could still interleave with this write (the existing,
    // already-accepted class of risk for any unsynchronized concurrent StateFlow write — not a
    // new one this introduces).
    internal fun reDeriveFromLiveAncestor() {
        val derive = config.deriveFromLiveAncestor ?: return
        mutableStateFlow.update { current -> derive(current, dependencies) }
        notifyLiveDependents()
    }

    // One declared SubScope's non-null→null transition tears its current child down with no
    // replacement — see preActivateNewChildren below for the other two cases this doesn't cover
    // (a fresh null→non-null activation, and a non-null→different-non-null *reassignment*, which
    // also tears the old child down but folds that into its own pre-publish pass instead — see
    // its own doc for why).
    private fun deactivateRemovedChildren(previousState: State, newState: State) {
        config.subScopes.forEach { subScope -> deactivateOne(subScope, previousState, newState) }
    }

    private fun <ChildState, ChildWhen> deactivateOne(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        previousState: State,
        newState: State
    ) {
        val previousChild = subScope.get(previousState)
        val currentChild = subScope.get(newState)
        if (previousChild != null && currentChild == null) {
            deactivateChild(subScope)
        }
    }

    // The "something new needs to exist" half of syncing SubScopes — a fresh null→non-null
    // activation, OR a non-null→different-non-null *reassignment* (ports Swift's dirty-flag
    // semantics: `MiddlewareReducerTests.testReassigningChildStateAlwaysCreatesNewStore` — ANY
    // assignment to an already-active child's field discards its whole subtree and creates a
    // genuinely new Store, even to a value that looks the same, never mutates the live one in
    // place — see ReducerTests.testReassignChildStateDiscardsGrandchildren for the grandchild
    // side of this). Reference inequality (`!==`), not structural (`!=`), is the direct Kotlin
    // analog of Swift's dirty flag: a `data class.copy()` call that never touches this field
    // keeps the exact same reference, while any explicit new value — even one that would compare
    // structurally equal — is a fresh object. The only place that writes this field WITHOUT going
    // through `process()` at all (a child mirroring its own state back up, or a live-ancestor
    // push) always does so via `subScope.set(...)`'s own `.copy(...)`, itself a fresh reference
    // each time — but those writes bypass `process()`/this comparison entirely (raw
    // `mutableStateFlow` writes, not `send()`), so they can never accidentally trigger a
    // reassignment-driven recreate of the very child that just mirrored into this field.
    //
    // Split from deactivateRemovedChildren so process() can register every new/replacement child
    // in activeChildren BEFORE publishing newState to stateFlow, and only run each one's
    // middleware install / live-ancestor push / defaultTrigger — plus, for a reassignment, the
    // OLD child's teardown — AFTER, same as a fresh activation (see process()'s own comment for
    // why). The old child's teardown can't simply reuse deactivateChild(subScope): by the time
    // the after-publish pass would run, activeChildren[subScope] already points to the NEW child
    // (registered here, in the pre-publish pass) — removing "whatever's under this key" at that
    // point would tear down the new child instead. Captured up front, before the map entry is
    // overwritten, and torn down directly by instance, not by key, in the returned closure.
    private fun preActivateNewChildren(previousState: State, newState: State): List<() -> Unit> =
        config.subScopes.mapNotNull { subScope -> preActivateOne(subScope, previousState, newState) }

    private fun <ChildState, ChildWhen> preActivateOne(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        previousState: State,
        newState: State
    ): (() -> Unit)? {
        val previousChild = subScope.get(previousState)
        val currentChild = subScope.get(newState) ?: return null
        if (previousChild === currentChild) return null
        @Suppress("UNCHECKED_CAST")
        val replacedChildStore = activeChildren[subScope] as? ScopeStoreImpl<ChildState, ChildWhen>
        val childStore = createChildAndRegister(subScope, currentChild)
        return {
            replacedChildStore?.let(::deactivateInstance)
            finishActivatingChild(subScope, childStore)
        }
    }

    // Activates a child already non-null in config.initial (a parent whose own update()
    // populates a child's state — or a deeper descendant's — in one shot, rather than one level
    // at a time) — see this store's init block. Nothing published to stateFlow yet at
    // construction time for any collector to race with, so no split is needed here: create,
    // register and finish in one call, same as this used to work for every activation.
    private fun <ChildState, ChildWhen> activateChild(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        initial: ChildState
    ) {
        val childStore = createChildAndRegister(subScope, initial)
        finishActivatingChild(subScope, childStore)
    }

    private fun <ChildState, ChildWhen> createChildAndRegister(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        initial: ChildState
    ): ScopeStoreImpl<ChildState, ChildWhen> {
        val superstateChain: Dependencies = subScope.superstateKey?.let { key ->
            SuperstateDependencies(dependencies, key, owner = this)
        } ?: dependencies

        val childStore = ScopeStoreImpl(
            subScope.config.copy(initial = initial),
            effectScope,
            inheritedDependencies = superstateChain
        )
        childStore.parent = this
        // Registered here, as soon as the child exists — see preActivateNewChildren's own doc for
        // why this has to happen before newState reaches stateFlow, not after.
        activeChildren[subScope] = childStore
        return childStore
    }

    private fun <ChildState, ChildWhen> finishActivatingChild(
        subScope: SubScope<State, When, ChildState, ChildWhen>,
        childStore: ScopeStoreImpl<ChildState, ChildWhen>
    ) {
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

        // The live-ancestor cascade's own registration (see ScopeConfig.liveAncestorKey): find
        // the real owner — possibly several levels up, through a chain of inherited
        // Dependencies — and register a callback with it, so every later state commit from that
        // owner pushes straight into this child without waiting for the child's own next event.
        // Run it once right here, too, synchronously, before defaultTrigger below ever fires —
        // so this child's very first observable state already reflects the owner's true current
        // value, never config.initial's own placeholder default.
        //
        // Critically, the push ALSO re-mirrors into THIS store's own field for it (the same
        // subScope.set the middleware above performs on every real send()) — childStore's own
        // mutableStateFlow isn't the only place a reader can observe this child's state from.
        // ConnectedChild (statoscope.android.compose) deliberately reads a child through its
        // DIRECT PARENT's own collected stateFlow (subScope.get(parentState)), not a second
        // subscription on the child itself — exactly so one parent-level collectAsState() catches
        // both "child created/destroyed" and "child's own state changed." Without re-mirroring
        // here, reDeriveFromLiveAncestor's direct write would correct childStore.state (every
        // ScopeStoreTest/NewsFeedTest assertion that reads the child store directly would still
        // pass) while leaving THIS field of this store's own state — what a real app's UI
        // actually renders through — showing the pre-cascade value: the exact gap that let the
        // newsfeed app's on-device lag survive this cascade's first version even after its own
        // unit tests went green.
        //
        // mutableStateFlow.update {}, not a synchronized assignment, for the same reason
        // reDeriveFromLiveAncestor's own write is lock-free: this runs from inside the OWNER's
        // notifyLiveDependents (see below), on the OWNER's own thread — for a skip-level owner,
        // `this` is a DIFFERENT instance (an intermediate ancestor) than that owner, and
        // synchronizing on it here would be the exact ancestor-acquires-descendant's-lock
        // ordering reDeriveFromLiveAncestor's own doc explains is a real cross-thread deadlock
        // risk against the existing child-delegates-up path.
        childStore.config.liveAncestorKey?.let { key ->
            val owner = childStore.dependencies.findSuperstateOwnerOrNull(key)
            val pushAndMirror = {
                childStore.reDeriveFromLiveAncestor()
                mutableStateFlow.update { current -> subScope.set(current, childStore.state) }
            }
            if (owner != null) {
                owner.registerLiveDependent(pushAndMirror)
                childStore.liveAncestorUnregister = { owner.unregisterLiveDependent(pushAndMirror) }
            }
            pushAndMirror()
        }

        // See ScopeConfig.defaultTrigger's own doc for exactly what fires and when (every
        // activation, including an init-time pre-populated one) and what deliberately doesn't
        // (a plain createScope root).
        subScope.config.defaultTrigger?.let { trigger -> childStore.send(trigger) }
    }

    private fun <ChildState, ChildWhen> deactivateChild(subScope: SubScope<State, When, ChildState, ChildWhen>) {
        val removed = activeChildren.remove(subScope)
        (removed as? ScopeStoreImpl<*, *>)?.let(::deactivateInstance)
    }

    // The by-instance half of tearing a child down — shared by deactivateChild (removed from
    // activeChildren by key, the plain non-null→null case) and preActivateOne's own reassignment
    // closure (which already overwrote the map entry with the replacement child, so it tears the
    // old instance down directly, never by key — see preActivateOne's own doc).
    private fun deactivateInstance(childStore: ScopeStoreImpl<*, *>) {
        childStore.cancelAllRunningEffectsRecursively()
        childStore.liveAncestorUnregister?.invoke()
    }

    // Called on a child store right as it's torn down (see deactivateChild) — without this, a
    // deactivated child's own in-flight effect Jobs keep running (real IO, wasted work) and, on
    // completion, call send() on a store nobody observes anymore. Recurses into the child's own
    // activeChildren first, so a grandchild's effects are cancelled too, before clearing this
    // level's own runningEffects.
    @Synchronized
    private fun cancelAllRunningEffectsRecursively() {
        activeChildren.values.forEach { (it as? ScopeStoreImpl<*, *>)?.cancelAllRunningEffectsRecursively() }
        runningEffects.values.forEach { it.job?.cancel() }
        runningEffects.clear()
    }
}
