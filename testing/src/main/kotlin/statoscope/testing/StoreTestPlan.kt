package statoscope.testing

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import statoscope.core.Effect
import statoscope.core.InjectionKey
import statoscope.core.ScopeConfig
import statoscope.core.ScopeStore
import statoscope.core.StatoscopeInternalApi
import statoscope.core.SubScope
import statoscope.core.createScope

/**
 * Fluent GIVEN/WHEN/THEN builder for a *real*, composed [ScopeStore] tree — the composed-tree
 * counterpart to [TestPlan], which only ever runs one reducer's `update()` directly, in isolation
 * (see its own doc). Mirrors Swift's `StoreTestPlan`/`WithStoreTestPlan`
 * (`Sources/StatoscopeTesting/StoreTestPlan/StoreTestPlan.swift`): [with] descends into an active
 * child by its [SubScope] reference — Kotlin's stable, identity-based equivalent of Swift's
 * `KeyPath<Parent, Child>` — returning a differently-typed plan whose steps are actually still
 * recorded on the root (see [WithStoreTestPlan]), and [pop] returns back up. Every step here runs
 * against a real store built via [statoscope.core.createScope] — real `send()`, real (if
 * [statoscope.core.scopeEffectsDisabledInUnitTests]-gated) effect bookkeeping, real parent/child
 * reconciliation — proving composition actually works end to end, the way
 * `statoscope.examples.tutorial05.ScopesIntegrationTest`'s hand-rolled `createScope`/`childStore`
 * calls did before this existed.
 *
 * The [Self] type parameter is what lets [pop] stay callable after any number of chained
 * [on]/[then]/etc. calls inside a [with] block — every builder method below returns [Self], not
 * the base [StoreTestPlan] type, so the concrete subtype ([WithStoreTestPlan]'s `pop()` included)
 * is never lost along the chain. Callers never write [Self] themselves: [givenStore] and [with]
 * both return an already-concrete type, inferred.
 *
 * ```kotlin
 * givenStore(newsFeedConfig)
 *     .on(NewsFeedWhen.UserEnabledFeature)
 *     .with(newsFeedListSubScope)
 *     .on(NewsFeedListWhen.UserSelectedArticle("1"))
 *     .with(newsFeedArticleSubScope)
 *     .on(NewsFeedArticleWhen.UserTappedFavoriteButton)
 *     .pop()
 *     .thenEquals(NewsFeedListState::favorites, listOf(Favorite("1", 0)))
 *     .pop()
 *     .runTest()
 * ```
 */
@OptIn(StatoscopeInternalApi::class)
abstract class StoreTestPlan<State, When, Self : StoreTestPlan<State, When, Self>> internal constructor() {

    private val rootSteps = mutableListOf<(ScopeStore<State, When>) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    private fun self(): Self = this as Self

    /**
     * Records [step] to run, in order, once the real store exists. Overridden by
     * [WithStoreTestPlan] to delegate up to the root's own list instead of keeping a separate one
     * of its own — every step in a whole plan, however many [with] calls deep, ends up here, on
     * whichever [StoreTestPlan] instance [givenStore] actually returned.
     */
    internal open fun addStep(step: (ScopeStore<State, When>) -> Unit) {
        rootSteps += step
    }

    internal fun runSteps(store: ScopeStore<State, When>) {
        rootSteps.forEach { it(store) }
    }

    /** A snapshot copy of every step recorded so far — what [FORK] replays onto a fresh store. */
    internal fun stepsSnapshot(): List<(ScopeStore<State, When>) -> Unit> = rootSteps.toList()

    /** Seeds this (freshly built, forked) plan's own steps with [steps] — see [buildLinkedFork]. */
    internal fun seedSteps(steps: List<(ScopeStore<State, When>) -> Unit>) {
        rootSteps += steps
    }

    /**
     * The real, ultimate root of this plan — itself for [RootStoreTestPlan], the [with]-chain's
     * [WithStoreTestPlan.pop] target all the way up for any nested frame. Lets [FORK] register the
     * alternate branch it builds for deferred execution, and reach the steps-so-far it needs to
     * replay, regardless of how deep into a [with] chain it was called.
     */
    internal abstract fun rootPlan(): RootStoreTestPlan<*, *>

    /** Delegates up to the root (see [rootPlan]) to record a fork branch for [runTest] to run. */
    internal abstract fun registerFork(action: () -> Unit)

    /**
     * Builds a new, independent plan of this same navigational shape (same [with]-depth, same
     * [SubScope]s) whose own steps start as a snapshot copy of every step recorded on this plan so
     * far — never sharing [this]'s own list, so further calls on either plan after the fork don't
     * leak into the other. The returned plan is never registered with [TestPlanRegistry]: only the
     * real root it ultimately bottoms out in tracks "forgot `runTest()`," and [FORK] always runs
     * it for the caller via [registerFork], never leaving it for the caller to run directly.
     */
    internal abstract fun buildLinkedFork(): Self

    /** Dispatch [action] through the current frame's real `send()`. */
    fun on(action: When): Self {
        addStep { store -> store.send(action) }
        return self()
    }

    /** Run a custom assertion against the current frame's state. */
    fun then(assertion: (State) -> Unit): Self {
        addStep { store -> assertion(store.state) }
        return self()
    }

    /** Assert `property.get(state) == value` on the current frame. */
    fun <Value> thenEquals(property: KProperty1<State, Value>, value: Value): Self =
        then { s -> assertEquals(value, property.get(s), "Expected ${property.name} to equal $value") }

    /** Asserts on the current frame's own pending effects (see [ScopeStore.pendingEffects]). */
    fun thenEffects(assertion: (List<Effect<*>>) -> Unit): Self {
        addStep { store -> assertion(store.pendingEffects()) }
        return self()
    }

    /** Asserts the current frame has no pending effects at all. */
    fun thenNoEffects(): Self = thenEffects { effects ->
        assertTrue(effects.isEmpty(), "Expected no pending effects, got ${effects.size}: $effects")
    }

    /**
     * Completes the current frame's oldest pending effect with [result] — see
     * [ScopeStore.completeOldestEffect]. Never actually runs the effect's own work; simulates its
     * result the same way [TestPlan] simulates a bare reducer's effect completion, just against a
     * real store this time. Fails the step if nothing is pending.
     */
    fun onOldestEffectCompletes(result: When): Self {
        addStep { store ->
            check(store.completeOldestEffect(result)) {
                "onOldestEffectCompletes: no pending effect to complete with $result"
            }
        }
        return self()
    }

    /**
     * Completes the pending effect of type [effectType] with [pristineResult] — a value shaped
     * like *that effect's own* natural result, replayed through its real `.map()` chain (see
     * [ScopeStore.completeEffect]) — rather than a tester-guessed final [When] value the way
     * [onOldestEffectCompletes] requires. Proves the effect's own mapping wiring is correct, not
     * just an assumption about what it produces. Mirrors Swift's `WHEN_EffectCompletes`.
     */
    fun <EffectType : Effect<R>, R> completeEffect(effectType: KClass<EffectType>, pristineResult: R): Self {
        addStep { store ->
            check(store.completeEffect(effectType, pristineResult)) {
                "completeEffect(${effectType.simpleName}): no pending effect of that type"
            }
        }
        return self()
    }

    /**
     * Descends into the child activated for [subScope] — every step recorded from here on drills
     * into `parentStore.childStore(subScope)` fresh each time it actually runs (never a value
     * captured once), so it always reaches whichever child instance is live at that point in the
     * plan, the same way Swift's keypath-based `WITH` re-reads through the keypath each step.
     * Fails the step, not [with] itself, if no child is active yet at that point — matching every
     * other step here being deferred until [runTest].
     */
    fun <ChildState, ChildWhen> with(
        subScope: SubScope<State, When, ChildState, ChildWhen>
    ): WithStoreTestPlan<State, When, Self, ChildState, ChildWhen> = WithStoreTestPlan(self(), subScope)

    /**
     * Tests an alternate branch from the exact same starting point as every step recorded on this
     * plan so far, without disturbing the "main" branch [self] continues on. Builds a second,
     * independent store (via [buildLinkedFork]) that replays this plan's prior steps first, then
     * dispatches [elseWhen] against it and runs [elseFlow] for any further assertions — deferred
     * until [runTest], same as every other step. Mirrors Swift's `StoreTestPlan.FORK(_:_:)`.
     *
     * ```kotlin
     * givenStore(config)
     *     .on(When.StartedLoad)
     *     .FORK(When.LoadFailed) { it.thenEquals(State::isLoading, false) }
     *     .onOldestEffectCompletes(When.LoadSucceeded(data))
     *     .thenEquals(State::isLoading, false)
     *     .runTest()
     * ```
     */
    fun FORK(elseWhen: When, elseFlow: (Self) -> Unit = {}): Self {
        val forkedPlan = buildLinkedFork()
        forkedPlan.on(elseWhen)
        elseFlow(forkedPlan)
        val forkedRoot = forkedPlan.rootPlan()
        rootPlan().registerFork { forkedRoot.runOwnStepsAndForks() }
        return self()
    }

    /** Builds the real store (root only) and runs every recorded step, in order. */
    abstract fun runTest()
}

/** The plan [givenStore] returns — the only [StoreTestPlan] that actually builds a real store. */
class RootStoreTestPlan<State, When> internal constructor(
    private val config: ScopeConfig<State, When>,
    private val isAFork: Boolean = false
) : StoreTestPlan<State, When, RootStoreTestPlan<State, When>>() {

    // Forks are never independently tracked: FORK() always runs the one it builds itself (see
    // registerFork/runOwnStepsAndForks below), so there's no "forgot runTest()" case for it to
    // ever be forgotten — mirrors Swift's own `!isAFork` guard skipping its deinit check.
    private val pendingMarker: PendingTestPlan? = if (isAFork) null else PendingTestPlan()
    private var hasRun = false
    private var provideOverride: List<Pair<InjectionKey<*>, Any?>>? = null
    private val forks = mutableListOf<() -> Unit>()

    init {
        pendingMarker?.let(TestPlanRegistry::register)
    }

    /**
     * Overrides [ScopeConfig.provide] before the real store is built — root-only. A child's own
     * injection comes only from its [SubScope.config] and the superstate chain, never an external
     * override (see [SubScope]'s own doc on why there's no parent-into-child injection hook), so
     * this isn't available once [with] has descended into a child frame. Call before any [with].
     */
    fun withProvide(entries: List<Pair<InjectionKey<*>, Any?>>): RootStoreTestPlan<State, When> = apply {
        provideOverride = entries
    }

    /**
     * Runs every accumulated step in order, starting from a freshly built store. Deregisters from
     * [TestPlanRegistry] first — before any step runs — mirroring [TestPlan.runTest]'s own
     * "still counts as run even if an assertion fails" ordering.
     */
    override fun runTest() {
        check(!isAFork) { "Don't call runTest() on a forked StoreTestPlan — FORK() runs it for you" }
        check(!hasRun) { "Don't call runTest() more than once on the same StoreTestPlan" }
        hasRun = true
        pendingMarker?.let(TestPlanRegistry::unregister)
        runOwnStepsAndForks()
    }

    /**
     * Builds this plan's own store and runs its own steps, then every fork branched off it —
     * shared by [runTest] (the real, caller-visible root) and by the deferred action [FORK]
     * registers for a forked root (reached only via [StoreTestPlan.registerFork], never directly).
     */
    internal fun runOwnStepsAndForks() {
        val store = createScope(config.copy(provide = provideOverride ?: config.provide))
        runSteps(store)
        forks.forEach { it() }
    }

    override fun rootPlan(): RootStoreTestPlan<*, *> = this

    override fun registerFork(action: () -> Unit) {
        forks += action
    }

    override fun buildLinkedFork(): RootStoreTestPlan<State, When> {
        val forked = RootStoreTestPlan(config, isAFork = true)
        forked.provideOverride = provideOverride
        forked.seedSteps(stepsSnapshot())
        return forked
    }
}

/**
 * The plan [StoreTestPlan.with] returns. Never builds a store of its own — [addStep] delegates to
 * [parent]'s, wrapping each step in a fresh `parentStore.childStore(subScope)` lookup, and
 * [runTest] delegates to [parent] too, matching Swift's `WithStoreTestPlan.runTest()` forwarding
 * to its own `parentPlan`. Parameterized over [ParentSelf] (not just `ParentState`/`ParentWhen`)
 * so [pop] hands back a plan that still remembers ITS OWN concrete type — chaining another
 * [StoreTestPlan.with] after a [pop], or calling [StoreTestPlan.runTest]'s sibling builder methods
 * on the popped-to frame, keeps working no matter how many levels deep the original [with] chain
 * went.
 */
class WithStoreTestPlan<ParentState, ParentWhen, ParentSelf, ChildState, ChildWhen> internal constructor(
    private val parent: ParentSelf,
    private val subScope: SubScope<ParentState, ParentWhen, ChildState, ChildWhen>
) : StoreTestPlan<ChildState, ChildWhen, WithStoreTestPlan<ParentState, ParentWhen, ParentSelf, ChildState, ChildWhen>>()
    where ParentSelf : StoreTestPlan<ParentState, ParentWhen, ParentSelf> {

    override fun addStep(step: (ScopeStore<ChildState, ChildWhen>) -> Unit) {
        parent.addStep { parentStore ->
            val childStore = parentStore.childStore(subScope)
                ?: error("with(): no active child for $subScope — was it created yet at this point in the plan?")
            step(childStore)
        }
    }

    /** Returns to the parent frame — subsequent steps operate on [ParentState]/[ParentWhen] again. */
    fun pop(): ParentSelf = parent

    override fun runTest() = parent.runTest()

    override fun rootPlan(): RootStoreTestPlan<*, *> = parent.rootPlan()

    override fun registerFork(action: () -> Unit) = parent.registerFork(action)

    /** Forks [parent] itself, then re-wraps the forked parent in a [WithStoreTestPlan] of the same shape. */
    override fun buildLinkedFork(): WithStoreTestPlan<ParentState, ParentWhen, ParentSelf, ChildState, ChildWhen> =
        WithStoreTestPlan(parent.buildLinkedFork(), subScope)
}

/** Starts a composed-tree test plan for the real store [config] would build via [createScope]. */
fun <State, When> givenStore(config: ScopeConfig<State, When>): RootStoreTestPlan<State, When> =
    RootStoreTestPlan(config)
