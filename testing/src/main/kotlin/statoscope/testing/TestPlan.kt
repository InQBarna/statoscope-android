package statoscope.testing

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import statoscope.core.Dependencies
import statoscope.core.Effect
import statoscope.core.InjectionKey
import statoscope.core.MapDependencies
import statoscope.core.MutableEffectsState
import statoscope.core.ScopeConfig
import statoscope.core.StatoscopeInternalApi
import statoscope.core.SuperstateKey
import statoscope.core.completePristine
import statoscope.core.completePristineFailure
import statoscope.core.pristine

/**
 * Fluent GIVEN/WHEN/THEN builder for testing a scope's behavior.
 *
 * Mirrors `statoscope-zustand`'s `given()`/`.then()`/`.thenEquals()`/`.when()`/`.runTest()`
 * (see `src/testing/test-plan.ts`), with one deliberate naming change: dispatching an event is
 * [on] rather than `when` — `when` is a reserved keyword in Kotlin.
 *
 * Lives in its own Gradle module (`:testing`) rather than inside the library's own `src/test` —
 * ports Swift Statoscope's separate `StatoscopeTesting` SPM target, so any consumer (this
 * project's own tutorial/example tests, an app's tests, another module entirely) can depend on
 * it the same way it depends on the library itself. [MutableEffectsState]/[MapDependencies] are
 * public-but-opt-in ([StatoscopeInternalApi]) specifically so this file, a real separate
 * compilation module, can still construct them — Kotlin's nearest equivalent to Swift's
 * `@_spi(Internal) @testable import Statoscope`.
 *
 * Runs `update` directly rather than through a real [statoscope.core.ScopeStore] — **effects
 * enqueued during a test are never executed**, matching both `statoscope-zustand`'s and Swift
 * Statoscope's testing DSLs exactly: tests stay deterministic, and an effect's completion is
 * simulated by dispatching its result event directly with [on], not by waiting on real async
 * work.
 *
 * ```kotlin
 * given(counterConfig)
 *     .thenEquals(CounterState::viewDisplaysTotalCount, 0)
 *     .on(CounterWhen.UserTappedIncrementButton)
 *     .thenEquals(CounterState::viewDisplaysTotalCount, 1)
 *     .runTest()
 * ```
 */
@OptIn(StatoscopeInternalApi::class)
class TestPlan<State, When>(private val config: ScopeConfig<State, When>) {

    private var state: State = config.initial
    private var lastEnqueuedEffects: List<Effect<When>> = emptyList()
    private val steps = mutableListOf<() -> Unit>()
    private var provideOverride: List<Pair<InjectionKey<*>, Any?>>? = null
    private val superstateOverrides = mutableMapOf<SuperstateKey<*>, Any?>()
    private val pendingMarker = PendingTestPlan()
    private var hasRun = false

    init {
        TestPlanRegistry.register(pendingMarker)
    }

    // Read fresh each time a step actually runs, not when on()/then() was called — so calling
    // withProvide()/withSuperstate() any time before runTest() takes effect, regardless of call
    // order relative to on()/then().
    // Built once, on the first step that runs — so an inject() made by one update() is still
    // visible to the next step's resolve(), like on a real store.
    private var runtimeBase: MapDependencies? = null

    private val dependencies: Dependencies
        get() {
            val base = runtimeBase ?: MapDependencies(provideOverride ?: config.provide).also { runtimeBase = it }
            val overrides = superstateOverrides
            return object : Dependencies {
                override fun <T> resolve(key: InjectionKey<T>): T = base.resolve(key)

                override fun <T> inject(key: InjectionKey<T>, value: T) = base.inject(key, value)

                @Suppress("UNCHECKED_CAST")
                override fun <T> resolve(key: SuperstateKey<T>): T =
                    if (overrides.containsKey(key)) overrides[key] as T
                    else error(
                        "No fake provided for $key — a reducer under test that resolves a " +
                            "SuperstateKey needs withSuperstate(key, value) to stand in for the " +
                            "ancestor TestPlan doesn't have (it tests one scope in isolation, not " +
                            "a composed tree — see the real-Store integration test instead for that)."
                    )
            }
        }

    /**
     * Overrides [ScopeConfig.provide] for this test run with test-only fakes — mirrors
     * `statoscope-zustand`'s `TestPlan.withProvide`.
     * ```kotlin
     * given(config).withProvide([DateProviderKey.pair(fixedDateProvider)]).on(...).runTest()
     * ```
     */
    fun withProvide(entries: List<Pair<InjectionKey<*>, Any?>>): TestPlan<State, When> = apply {
        provideOverride = entries
    }

    /**
     * Stands in for a [statoscope.core.SubScope.superstateKey] an ancestor would otherwise
     * provide — needed to test, in isolation, a reducer whose `update()` resolves a
     * [SuperstateKey]. `TestPlan` never builds a real composed tree (it runs `update` directly,
     * bypassing [statoscope.core.ScopeStore] entirely — same reason effects never really run),
     * so there's no real ancestor here to read from.
     */
    fun <T> withSuperstate(key: SuperstateKey<T>, value: T): TestPlan<State, When> = apply {
        superstateOverrides[key] = value
    }

    /**
     * Dispatch [action] through `update`. Any effect it enqueues is recorded (readable via
     * [thenEffects]/[thenNoEffects] for the assertion immediately following) but never run —
     * same effects-never-execute rule as everywhere else in this file.
     */
    fun on(action: When): TestPlan<State, When> = apply {
        steps += { dispatch(action) }
    }

    // Always starts fresh, with no ongoing effects — TestPlan tests one update() call at a time,
    // in isolation (see the class doc), so it doesn't track effects as genuinely "launched" the
    // way a real ScopeStore does across calls; there's nothing there yet for a later step's own
    // EffectsState.effects/cancelEffect to see or cancel. A reducer that queries/cancels its
    // *own* already-ongoing effects (a real ScopeStore feature) needs a real Store to test, the
    // same way scope composition does. Shared by on() and onEffectCompletes() so both dispatch
    // through the exact same path.
    private fun dispatch(action: When) {
        val effects = MutableEffectsState<When>()
        state = config.update(state, action, effects, dependencies)
        lastEnqueuedEffects = effects.flushNewlyEnqueued()
    }

    /**
     * Completes the effect of type [effectType] most recently enqueued (see [thenEffects]) with
     * [pristineResult] — a value shaped like *that effect's own* natural result, replayed through
     * its real `.map()` chain — rather than a tester-guessed final [When] value the way plain
     * [on] requires when simulating a completion by hand. Proves the effect's own mapping wiring
     * is correct, not just an assumption about what it produces. Mirrors Swift's
     * `WHEN_EffectCompletes`.
     */
    @OptIn(StatoscopeInternalApi::class)
    fun <EffectType : Effect<R>, R> onEffectCompletes(
        effectType: KClass<EffectType>,
        pristineResult: R
    ): TestPlan<State, When> = apply {
        steps += {
            val found = lastEnqueuedEffects.firstOrNull { effectType.isInstance(it.pristine()) }
                ?: error("onEffectCompletes(${effectType.simpleName}): no matching enqueued effect")
            dispatch(found.completePristine(pristineResult))
        }
    }

    /**
     * Same as [onEffectCompletes], but simulates [effectType]'s pristine effect THROWING
     * [thrownError] instead of succeeding — replayed through its real `.map()`/`.mapToResult()`
     * chain via [completePristineFailure], never a tester-guessed final [When] value. Mirrors
     * Swift's `WHEN_EffectFails`. **Throws [thrownError] itself** if nothing in that effect's
     * chain actually catches it (no `mapToResult()` anywhere between the pristine effect and the
     * enqueue site) — same as a real failure would do in that shape.
     */
    @OptIn(StatoscopeInternalApi::class)
    fun <EffectType : Effect<*>> onEffectFails(
        effectType: KClass<EffectType>,
        thrownError: Throwable
    ): TestPlan<State, When> = apply {
        steps += {
            val found = lastEnqueuedEffects.firstOrNull { effectType.isInstance(it.pristine()) }
                ?: error("onEffectFails(${effectType.simpleName}): no matching enqueued effect")
            dispatch(found.completePristineFailure(thrownError))
        }
    }

    /**
     * Asserts on what [key] resolves to right now — including a value an earlier step's `update()`
     * put there via [Dependencies.inject].
     */
    fun <T> thenInjected(key: InjectionKey<T>, assertion: (T) -> Unit): TestPlan<State, When> = apply {
        steps += { assertion(dependencies.resolve(key)) }
    }

    /** Run a custom assertion against the current state. */
    fun then(assertion: (State) -> Unit): TestPlan<State, When> = apply {
        steps += { assertion(state) }
    }

    /**
     * Asserts on the effects enqueued by the most recently dispatched [on] step — mirrors
     * Swift's `THEN_EnquedEffect`/`THEN_Enqued(keyPath:effect:)`. Unlike Swift, Kotlin's
     * [Effect] isn't required to be `Equatable`, so this takes a predicate over the whole list
     * rather than comparing against one expected effect value.
     */
    fun thenEffects(assertion: (List<Effect<When>>) -> Unit): TestPlan<State, When> = apply {
        steps += { assertion(lastEnqueuedEffects) }
    }

    /** Asserts the most recently dispatched [on] step enqueued no effects at all. */
    fun thenNoEffects(): TestPlan<State, When> = thenEffects { effects ->
        assertTrue(effects.isEmpty(), "Expected no enqueued effects, got ${effects.size}: $effects")
    }

    /**
     * Assert `property.get(state) == value`. [property] is a real, statically-typed
     * `KProperty1` reference (e.g. `CounterState::viewDisplaysTotalCount`) — not a string key.
     */
    fun <Value> thenEquals(property: KProperty1<State, Value>, value: Value): TestPlan<State, When> =
        then { s ->
            assertEquals(value, property.get(s), "Expected ${property.name} to equal $value")
        }

    /**
     * Runs every accumulated step in order, starting from `config.initial`. Deregisters this
     * plan from [TestPlanRegistry] first — before any step runs, mirroring Swift's
     * `ransExecuted = ransExecuted + 1` happening before `runAllSteps` — so a plan whose steps
     * fail an assertion still counts as "run," not "forgotten."
     */
    fun runTest() {
        check(!hasRun) { "Don't call runTest() more than once on the same TestPlan" }
        hasRun = true
        TestPlanRegistry.unregister(pendingMarker)
        steps.forEach { it() }
    }
}

/** Starts a test plan for the scope described by [config]. */
fun <State, When> given(config: ScopeConfig<State, When>): TestPlan<State, When> = TestPlan(config)
