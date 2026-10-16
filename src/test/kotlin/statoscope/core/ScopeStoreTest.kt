//
// ScopeStoreTest.kt
// statoscope-kotlin
//
// Direct unit tests of the library's own core classes — not tutorial material, not extracted
// into any doc. Mirrors Swift Statoscope's top-level Tests/StatoscopeTests/*.swift, kept
// separate from Tests/StatoscopeTests/Examples/ there and from statoscope.examples here, so a
// tutorial rewrite can never accidentally break the library's own correctness net or vice versa.
//
package statoscope.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class TestState(val count: Int = 0, val marker: String = "initial")

private sealed interface TestWhen {
    data object Increment : TestWhen
    data class MarkerSet(val value: String) : TestWhen
}

private val testConfig = ScopeConfig<TestState, TestWhen>(
    initial = TestState(),
    update = { state, action, _, _ ->
        when (action) {
            is TestWhen.Increment -> state.copy(count = state.count + 1)
            is TestWhen.MarkerSet -> state.copy(marker = action.value)
        }
    }
)

private class FixedResultEffect(private val result: TestWhen) : Effect<TestWhen> {
    override suspend fun run(): TestWhen = result
}

// Two differently-typed raw effects, both real .map()-ped — for proving completeEffect() finds
// the one matching the REQUESTED type among several simultaneously pending, and really replays
// its own map (not just accepts whatever value it's handed).
private class RawIntFetch(private val value: Int) : Effect<Int> {
    override suspend fun run(): Int = value
}

private class RawStringFetch(private val value: String) : Effect<String> {
    override suspend fun run(): String = value
}

private sealed interface MultiEffectWhen {
    data object StartBoth : MultiEffectWhen
    data class IntLoaded(val value: Int) : MultiEffectWhen
    data class StringLoaded(val value: String) : MultiEffectWhen
}

private val multiEffectConfig = ScopeConfig<TestState, MultiEffectWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is MultiEffectWhen.StartBoth -> {
                // Enqueued oldest-first: RawIntFetch, then RawStringFetch — deliberately, so a
                // completeEffect() that ignored the requested type and just grabbed the oldest
                // would find the wrong one here.
                effects.enqueue(RawIntFetch(0).map(MultiEffectWhen::IntLoaded))
                effects.enqueue(RawStringFetch("").map(MultiEffectWhen::StringLoaded))
                state
            }
            is MultiEffectWhen.IntLoaded -> state.copy(count = action.value)
            is MultiEffectWhen.StringLoaded -> state.copy(marker = action.value)
        }
    }
)

private sealed interface CancelWhen {
    data object Start : CancelWhen
    data object CancelOngoing : CancelWhen
    data class Completed(val value: String) : CancelWhen
}

/** Never completes within a test's virtual time unless something actually advances past its
 *  delay — the point being that a *cancelled* SlowEffect must never get the chance to. */
private class SlowEffect : Effect<CancelWhen> {
    override suspend fun run(): CancelWhen {
        delay(10_000)
        return CancelWhen.Completed("should never arrive")
    }
}

private val cancelConfig = ScopeConfig<TestState, CancelWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is CancelWhen.Start -> {
                effects.enqueue(SlowEffect())
                state.copy(marker = "started")
            }
            is CancelWhen.CancelOngoing -> {
                effects.cancelEffect { it is SlowEffect }
                state.copy(marker = "cancelled")
            }
            is CancelWhen.Completed -> state.copy(marker = action.value)
        }
    }
)

/** A parent that composes [cancelConfig] as a child — reused as-is, [SlowEffect] included, so
 *  deactivating the child can be proven to cancel its own still-running effect Job. */
private data class ParentWithChildState(val child: TestState? = null)

private sealed interface ParentWithChildWhen {
    data object ActivateChild : ParentWithChildWhen
    data object DeactivateChild : ParentWithChildWhen
}

private val childSubScope = SubScope<ParentWithChildState, ParentWithChildWhen, TestState, CancelWhen>(
    config = cancelConfig,
    get = { it.child },
    set = { state, child -> state.copy(child = child) }
)

private val parentWithChildConfig = ScopeConfig<ParentWithChildState, ParentWithChildWhen>(
    initial = ParentWithChildState(),
    update = { state, action, _, _ ->
        when (action) {
            is ParentWithChildWhen.ActivateChild -> state.copy(child = TestState())
            is ParentWithChildWhen.DeactivateChild -> state.copy(child = null)
        }
    },
    subScopes = listOf(childSubScope)
)

// A second, sibling child slot sharing TestState/TestWhen (the same child type) — so
// childStore()'s per-SubScope-reference identity lookup is actually exercised against a real
// collision risk, not just assumed safe. Mirrors Swift Statoscope's own
// testSiblingSlotsOfTheSameChildReducerTypeResolveIndependently (ReducerKeyPathTests.swift),
// which proves the equivalent for ReducerKeyPath's per-property (not per-type) keying.
private data class TwoSameTypeSiblingsState(val first: TestState? = null, val second: TestState? = null)

private sealed interface TwoSameTypeSiblingsWhen {
    data object ActivateFirst : TwoSameTypeSiblingsWhen
    data object ActivateSecond : TwoSameTypeSiblingsWhen
}

private val firstSiblingSubScope = SubScope<TwoSameTypeSiblingsState, TwoSameTypeSiblingsWhen, TestState, TestWhen>(
    config = testConfig,
    get = { it.first },
    set = { state, child -> state.copy(first = child) }
)

private val secondSiblingSubScope = SubScope<TwoSameTypeSiblingsState, TwoSameTypeSiblingsWhen, TestState, TestWhen>(
    config = testConfig,
    get = { it.second },
    set = { state, child -> state.copy(second = child) }
)

private val twoSameTypeSiblingsConfig = ScopeConfig<TwoSameTypeSiblingsState, TwoSameTypeSiblingsWhen>(
    initial = TwoSameTypeSiblingsState(),
    update = { state, action, _, _ ->
        when (action) {
            is TwoSameTypeSiblingsWhen.ActivateFirst -> state.copy(first = TestState())
            is TwoSameTypeSiblingsWhen.ActivateSecond -> state.copy(second = TestState())
        }
    },
    subScopes = listOf(firstSiblingSubScope, secondSiblingSubScope)
)

private sealed interface QueryWhen {
    data object StartIfNotAlreadyRunning : QueryWhen
    data class Completed(val value: String) : QueryWhen
}

private class CountingSlowEffect : Effect<QueryWhen> {
    override suspend fun run(): QueryWhen {
        delay(10_000)
        return QueryWhen.Completed("effect-completed")
    }
}

/** Mirrors Swift's own documented `guard nil == effects.first(where: { $0 is NetworkEffect<DTO> })`
 *  pattern — a reducer reading its own currently-ongoing effects to avoid double-triggering. */
private val queryConfig = ScopeConfig<TestState, QueryWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is QueryWhen.StartIfNotAlreadyRunning ->
                if (effects.effects.none { it is CountingSlowEffect }) {
                    effects.enqueue(CountingSlowEffect())
                    state.copy(marker = "started")
                } else {
                    state.copy(marker = "already running, skipped")
                }
            is QueryWhen.Completed -> state.copy(marker = action.value)
        }
    }
)

/** Like [testConfig], but Increment also enqueues an effect completing with a marker only the
 *  effect's own completion could have produced — proof the effect actually ran, not just that
 *  the synchronous part of update() did. */
private val effectConfig = ScopeConfig<TestState, TestWhen>(
    initial = TestState(),
    update = { state, action, effects, _ ->
        when (action) {
            is TestWhen.Increment -> {
                effects.enqueue(FixedResultEffect(TestWhen.MarkerSet("effect-completed")))
                state.copy(count = state.count + 1)
            }
            is TestWhen.MarkerSet -> state.copy(marker = action.value)
        }
    }
)

// Three real levels (Root -> Middle -> Grandchild), proving ScopeConfig.onDescendantAction
// reaches a GRANDCHILD directly from the ROOT, with zero relay at the Middle level — the
// runtime-broadcast counterpart to SubScope.onChildAction's one-hop, compile-time-validated
// relay. Mirrors the shape (if not the exact mechanism) SubScopeProcessorTest proves for the
// codegen path.
private data class BroadcastGrandchildState(val tapped: Boolean = false)
private sealed interface BroadcastGrandchildWhen {
    data object Tap : BroadcastGrandchildWhen
    // Mirrors Swift's ReducerUpdateSubstateErrorTests' .fail case — proves a descendant's
    // thrown update() pre-empts onDescendantAction entirely, not just "runs it with stale data".
    data object Fail : BroadcastGrandchildWhen
}
private class DeliberateBroadcastTestError : RuntimeException("deliberate test failure")
private val broadcastGrandchildConfig = ScopeConfig<BroadcastGrandchildState, BroadcastGrandchildWhen>(
    initial = BroadcastGrandchildState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastGrandchildWhen.Tap -> state.copy(tapped = true)
            is BroadcastGrandchildWhen.Fail -> throw DeliberateBroadcastTestError()
        }
    }
)

private data class BroadcastMiddleState(val grandchild: BroadcastGrandchildState? = null)
private sealed interface BroadcastMiddleWhen {
    data object ActivateGrandchild : BroadcastMiddleWhen
}

// Deliberately NO onChildAction here — proving the root sees the grandchild's event with no
// relay from this level at all, not even an unconditional pass-through one.
private val broadcastMiddleGrandchildSubScope =
    SubScope<BroadcastMiddleState, BroadcastMiddleWhen, BroadcastGrandchildState, BroadcastGrandchildWhen>(
        config = broadcastGrandchildConfig,
        get = { it.grandchild },
        set = { state, grandchild -> state.copy(grandchild = grandchild) }
    )

private val broadcastMiddleConfig = ScopeConfig<BroadcastMiddleState, BroadcastMiddleWhen>(
    initial = BroadcastMiddleState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastMiddleWhen.ActivateGrandchild -> state.copy(grandchild = BroadcastGrandchildState())
        }
    },
    subScopes = listOf(broadcastMiddleGrandchildSubScope)
)

private data class BroadcastRootState(
    val middle: BroadcastMiddleState? = null,
    val grandchildTapsSeenAtRoot: Int = 0
)
private sealed interface BroadcastRootWhen {
    data object ActivateMiddle : BroadcastRootWhen
    data object RecordGrandchildTap : BroadcastRootWhen
}
private val broadcastRootMiddleSubScope =
    SubScope<BroadcastRootState, BroadcastRootWhen, BroadcastMiddleState, BroadcastMiddleWhen>(
        config = broadcastMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )

private val broadcastRootConfig = ScopeConfig<BroadcastRootState, BroadcastRootWhen>(
    initial = BroadcastRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is BroadcastRootWhen.ActivateMiddle -> state.copy(middle = BroadcastMiddleState())
            is BroadcastRootWhen.RecordGrandchildTap ->
                state.copy(grandchildTapsSeenAtRoot = state.grandchildTapsSeenAtRoot + 1)
        }
    },
    subScopes = listOf(broadcastRootMiddleSubScope),
    onDescendantAction = { descendantWhen, _, _, _ ->
        if (descendantWhen is BroadcastGrandchildWhen.Tap) BroadcastRootWhen.RecordGrandchildTap else null
    }
)

// Three real levels (Root -> Middle -> Grandchild) proving InjectionKey resolution now walks the
// ancestor chain — a provider declared only at the Root reaches a Grandchild with zero plumbing
// at the Middle level, and a closer override wins over a farther one.
private val ancestorGreetingKey = InjectionKey(defaultValue = "default-greeting")

private data class AncestorGrandchildState(val greeting: String = "")
private sealed interface AncestorGrandchildWhen {
    data object ReadGreeting : AncestorGrandchildWhen
}
private val ancestorGrandchildConfig = ScopeConfig<AncestorGrandchildState, AncestorGrandchildWhen>(
    initial = AncestorGrandchildState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is AncestorGrandchildWhen.ReadGreeting -> state.copy(greeting = dependencies.resolve(ancestorGreetingKey))
        }
    }
)

private data class AncestorMiddleState(val grandchild: AncestorGrandchildState? = null)
private sealed interface AncestorMiddleWhen {
    data object ActivateGrandchild : AncestorMiddleWhen
}
private val ancestorMiddleGrandchildSubScope =
    SubScope<AncestorMiddleState, AncestorMiddleWhen, AncestorGrandchildState, AncestorGrandchildWhen>(
        config = ancestorGrandchildConfig,
        get = { it.grandchild },
        set = { state, grandchild -> state.copy(grandchild = grandchild) }
    )
private val ancestorMiddleUpdate: Reducer<AncestorMiddleState, AncestorMiddleWhen> = { state, action, _, _ ->
    when (action) {
        is AncestorMiddleWhen.ActivateGrandchild -> state.copy(grandchild = AncestorGrandchildState())
    }
}

// Deliberately no `provide` override — proving the grandchild still resolves the root's value
// with zero plumbing at this level.
private val ancestorMiddleConfigNoOverride = ScopeConfig(
    initial = AncestorMiddleState(),
    update = ancestorMiddleUpdate,
    subScopes = listOf(ancestorMiddleGrandchildSubScope)
)

// A second Middle config, identical except it DOES override the key — for the
// nearest-ancestor-wins test, kept separate so the no-override tree above stays a clean proof on
// its own.
private val ancestorMiddleConfigWithOverride = ScopeConfig(
    initial = AncestorMiddleState(),
    update = ancestorMiddleUpdate,
    subScopes = listOf(ancestorMiddleGrandchildSubScope),
    provide = listOf(ancestorGreetingKey.pair("hello-from-middle"))
)

private data class AncestorRootState(val middle: AncestorMiddleState? = null)
private sealed interface AncestorRootWhen {
    data object ActivateMiddle : AncestorRootWhen
}
private val ancestorRootUpdate: Reducer<AncestorRootState, AncestorRootWhen> = { state, action, _, _ ->
    when (action) {
        is AncestorRootWhen.ActivateMiddle -> state.copy(middle = AncestorMiddleState())
    }
}

private val ancestorRootMiddleSubScope =
    SubScope<AncestorRootState, AncestorRootWhen, AncestorMiddleState, AncestorMiddleWhen>(
        config = ancestorMiddleConfigNoOverride,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )
private val ancestorRootConfig = ScopeConfig(
    initial = AncestorRootState(),
    update = ancestorRootUpdate,
    subScopes = listOf(ancestorRootMiddleSubScope),
    provide = listOf(ancestorGreetingKey.pair("hello-from-root"))
)

private val ancestorRootMiddleWithOverrideSubScope =
    SubScope<AncestorRootState, AncestorRootWhen, AncestorMiddleState, AncestorMiddleWhen>(
        config = ancestorMiddleConfigWithOverride,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )
private val ancestorRootConfigWithMiddleOverride = ScopeConfig(
    initial = AncestorRootState(),
    update = ancestorRootUpdate,
    subScopes = listOf(ancestorRootMiddleWithOverrideSubScope),
    provide = listOf(ancestorGreetingKey.pair("hello-from-root"))
)

// Three real levels (Root -> Middle -> Leaf), the Leaf skipping its direct parent to read the
// Root's own state live via SuperstateKey — ports Swift's `SkipLevelSuperStateStalenessTests`
// (`Tests/StatoscopeTests/MiddlewareReducerTests.swift`, Audit Issue #6, fixed there
// 2026-09-18). Swift's bug was reading a STALE cached mirror (`_rawState`) instead of the
// ancestor's always-current computed `state`; Kotlin only ever has one representation per store
// (`mutableStateFlow.value`), kept synchronously current by every child's own send() mirroring
// up into its parent before returning (see `activateChild`'s middleware) — structurally immune
// to this bug class, not just fixed after the fact. Same scenario, same assertion, as a
// regression test rather than a theoretical claim.
private val stalenessRootSuperstateKey = SuperstateKey<StalenessRootState>("StalenessRoot")

private data class StalenessLeafState(val observedMiddleLabel: String? = null)
private sealed interface StalenessLeafWhen {
    data object ReadRootsMiddleLabel : StalenessLeafWhen
}
private val stalenessLeafConfig = ScopeConfig<StalenessLeafState, StalenessLeafWhen>(
    initial = StalenessLeafState(),
    update = { state, action, _, dependencies ->
        when (action) {
            is StalenessLeafWhen.ReadRootsMiddleLabel -> {
                val root = dependencies.resolve(stalenessRootSuperstateKey)
                state.copy(observedMiddleLabel = root.middle?.label)
            }
        }
    }
)

private data class StalenessMiddleState(val label: String = "initial", val leaf: StalenessLeafState? = null)
private sealed interface StalenessMiddleWhen {
    data object CreateLeaf : StalenessMiddleWhen
    data class SetLabel(val label: String) : StalenessMiddleWhen
}
// Deliberately no superstateKey on THIS SubScope — the leaf skips straight to the root's own
// key, inherited unchanged through this level, with zero plumbing here, exactly like Swift's
// skip-level @SuperState.
private val stalenessMiddleLeafSubScope =
    SubScope<StalenessMiddleState, StalenessMiddleWhen, StalenessLeafState, StalenessLeafWhen>(
        config = stalenessLeafConfig,
        get = { it.leaf },
        set = { state, leaf -> state.copy(leaf = leaf) }
    )
private val stalenessMiddleConfig = ScopeConfig<StalenessMiddleState, StalenessMiddleWhen>(
    initial = StalenessMiddleState(),
    update = { state, action, _, _ ->
        when (action) {
            is StalenessMiddleWhen.CreateLeaf -> state.copy(leaf = StalenessLeafState())
            is StalenessMiddleWhen.SetLabel -> state.copy(label = action.label)
        }
    },
    subScopes = listOf(stalenessMiddleLeafSubScope)
)

private data class StalenessRootState(val middle: StalenessMiddleState? = null)
private sealed interface StalenessRootWhen {
    data object CreateMiddle : StalenessRootWhen
}
private val stalenessRootMiddleSubScope =
    SubScope<StalenessRootState, StalenessRootWhen, StalenessMiddleState, StalenessMiddleWhen>(
        config = stalenessMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) },
        superstateKey = stalenessRootSuperstateKey
    )
private val stalenessRootConfig = ScopeConfig<StalenessRootState, StalenessRootWhen>(
    initial = StalenessRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is StalenessRootWhen.CreateMiddle -> state.copy(middle = StalenessMiddleState())
        }
    },
    subScopes = listOf(stalenessRootMiddleSubScope)
)

// Verifies a scenario flagged by this session's test-parity audit against Swift's
// `ReducerTests.testNewChildWithPrePopulatedGrandchildCreatesFullHierarchy`: a parent's single
// update() call creates a child whose OWN initial state already has a non-null grandchild field
// — never built one level at a time the way every existing tutorial/example does. Swift
// recursively activates the whole pre-populated hierarchy at child-creation time
// (`Store.swift`'s `_applyChildSlotsRecursively`). This fixture exists to find out whether
// Kotlin's `syncSubScopes` (which only runs inside `process()`, triggered by `send()` — nothing
// scans a freshly-constructed child's own initial state for already-non-null descendants) does
// the same, or leaves the grandchild's own ScopeStore never created.
private data class PrePopulatedGrandchildState(val value: String = "initial")
private sealed interface PrePopulatedGrandchildWhen {
    data object NoOp : PrePopulatedGrandchildWhen
}
private val prePopulatedGrandchildConfig = ScopeConfig<PrePopulatedGrandchildState, PrePopulatedGrandchildWhen>(
    initial = PrePopulatedGrandchildState(),
    update = { state, _, _, _ -> state }
)

private data class PrePopulatedMiddleState(val grandchild: PrePopulatedGrandchildState? = null)
private sealed interface PrePopulatedMiddleWhen {
    data object NoOp : PrePopulatedMiddleWhen
}
private val prePopulatedMiddleGrandchildSubScope =
    SubScope<PrePopulatedMiddleState, PrePopulatedMiddleWhen, PrePopulatedGrandchildState, PrePopulatedGrandchildWhen>(
        config = prePopulatedGrandchildConfig,
        get = { it.grandchild },
        set = { state, grandchild -> state.copy(grandchild = grandchild) }
    )
private val prePopulatedMiddleConfig = ScopeConfig<PrePopulatedMiddleState, PrePopulatedMiddleWhen>(
    initial = PrePopulatedMiddleState(),
    update = { state, _, _, _ -> state },
    subScopes = listOf(prePopulatedMiddleGrandchildSubScope)
)

private data class PrePopulatedRootState(val middle: PrePopulatedMiddleState? = null)
private sealed interface PrePopulatedRootWhen {
    data object CreateMiddleWithGrandchildAlreadyPresent : PrePopulatedRootWhen

    // Ports Swift's dirty-flag semantics (`MiddlewareReducerTests.testReassigningChildStateAlwaysCreatesNewStore`,
    // `ReducerTests.testReassignChildStateDiscardsGrandchildren`): a direct reassignment of an
    // already-active child's field — not nulling it first, replacing it outright — to a brand
    // new value, with no grandchild of its own this time.
    data object ReassignMiddleToAFreshValue : PrePopulatedRootWhen
}
private val prePopulatedRootMiddleSubScope =
    SubScope<PrePopulatedRootState, PrePopulatedRootWhen, PrePopulatedMiddleState, PrePopulatedMiddleWhen>(
        config = prePopulatedMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )
private val prePopulatedRootConfig = ScopeConfig<PrePopulatedRootState, PrePopulatedRootWhen>(
    initial = PrePopulatedRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is PrePopulatedRootWhen.CreateMiddleWithGrandchildAlreadyPresent ->
                // One single update() call, not two separate events — the middle AND its own
                // grandchild both come into existence at once, exactly the scenario Swift's
                // _applyChildSlotsRecursively exists to handle.
                state.copy(middle = PrePopulatedMiddleState(grandchild = PrePopulatedGrandchildState(value = "pre-populated")))
            is PrePopulatedRootWhen.ReassignMiddleToAFreshValue ->
                state.copy(middle = PrePopulatedMiddleState())
        }
    },
    subScopes = listOf(prePopulatedRootMiddleSubScope)
)

// Ports Swift's defaultTrigger tests (`MiddlewareReducerTests.swift`'s "defaultTrigger Tests"
// section) — an event a config declares to fire automatically, exactly once, the moment a scope
// built from it is activated as a child. See ScopeConfig.defaultTrigger's own doc for the one
// deliberate divergence from Swift tested separately below: Kotlin fires it uniformly for every
// activation, including an init-time pre-populated one, where Swift's own recursive descent
// suppresses it (`triggerDefaults: false`).
private data class DefaultTriggerChildState(val initializeCount: Int = 0, val otherEventCount: Int = 0)
private sealed interface DefaultTriggerChildWhen {
    data object OnAppear : DefaultTriggerChildWhen
    data object SomeOtherEvent : DefaultTriggerChildWhen
}
private val defaultTriggerChildUpdate: Reducer<DefaultTriggerChildState, DefaultTriggerChildWhen> = { state, action, _, _ ->
    when (action) {
        is DefaultTriggerChildWhen.OnAppear -> state.copy(initializeCount = state.initializeCount + 1)
        is DefaultTriggerChildWhen.SomeOtherEvent -> state.copy(otherEventCount = state.otherEventCount + 1)
    }
}
private val defaultTriggerChildConfig = ScopeConfig(
    initial = DefaultTriggerChildState(),
    update = defaultTriggerChildUpdate,
    defaultTrigger = DefaultTriggerChildWhen.OnAppear
)
// Same shape, deliberately no defaultTrigger — proves "no defaultTrigger means no automatic event."
private val noDefaultTriggerChildConfig = ScopeConfig(
    initial = DefaultTriggerChildState(),
    update = defaultTriggerChildUpdate
)

private data class DefaultTriggerParentState(val child: DefaultTriggerChildState? = null)
private sealed interface DefaultTriggerParentWhen {
    data object CreateChild : DefaultTriggerParentWhen
    data object DestroyChild : DefaultTriggerParentWhen
}
private val defaultTriggerParentUpdate: Reducer<DefaultTriggerParentState, DefaultTriggerParentWhen> = { state, action, _, _ ->
    when (action) {
        is DefaultTriggerParentWhen.CreateChild -> state.copy(child = DefaultTriggerChildState())
        is DefaultTriggerParentWhen.DestroyChild -> state.copy(child = null)
    }
}

private val defaultTriggerChildSubScope =
    SubScope<DefaultTriggerParentState, DefaultTriggerParentWhen, DefaultTriggerChildState, DefaultTriggerChildWhen>(
        config = defaultTriggerChildConfig,
        get = { it.child },
        set = { state, child -> state.copy(child = child) }
    )
private val defaultTriggerParentConfig = ScopeConfig(
    initial = DefaultTriggerParentState(),
    update = defaultTriggerParentUpdate,
    subScopes = listOf(defaultTriggerChildSubScope)
)

private val noDefaultTriggerChildSubScope =
    SubScope<DefaultTriggerParentState, DefaultTriggerParentWhen, DefaultTriggerChildState, DefaultTriggerChildWhen>(
        config = noDefaultTriggerChildConfig,
        get = { it.child },
        set = { state, child -> state.copy(child = child) }
    )
private val noDefaultTriggerParentConfig = ScopeConfig(
    initial = DefaultTriggerParentState(),
    update = defaultTriggerParentUpdate,
    subScopes = listOf(noDefaultTriggerChildSubScope)
)

// A separate, minimal root -> middle -> grandchild tree (not reusing the PrePopulated* fixtures
// above, to keep this test's concern isolated) proving defaultTrigger also fires for a
// grandchild activated via ScopeStoreImpl's init-time pre-populated-child path, not just the
// normal one-field-at-a-time update() transition.
private data class DefaultTriggerGrandchildState(val initializeCount: Int = 0)
private sealed interface DefaultTriggerGrandchildWhen {
    data object OnAppear : DefaultTriggerGrandchildWhen
}
private val defaultTriggerGrandchildConfig = ScopeConfig<DefaultTriggerGrandchildState, DefaultTriggerGrandchildWhen>(
    initial = DefaultTriggerGrandchildState(),
    update = { state, action, _, _ ->
        when (action) {
            is DefaultTriggerGrandchildWhen.OnAppear -> state.copy(initializeCount = state.initializeCount + 1)
        }
    },
    defaultTrigger = DefaultTriggerGrandchildWhen.OnAppear
)

private data class DefaultTriggerMiddleState(val grandchild: DefaultTriggerGrandchildState? = null)
private sealed interface DefaultTriggerMiddleWhen {
    data object NoOp : DefaultTriggerMiddleWhen
}
private val defaultTriggerMiddleGrandchildSubScope =
    SubScope<DefaultTriggerMiddleState, DefaultTriggerMiddleWhen, DefaultTriggerGrandchildState, DefaultTriggerGrandchildWhen>(
        config = defaultTriggerGrandchildConfig,
        get = { it.grandchild },
        set = { state, grandchild -> state.copy(grandchild = grandchild) }
    )
private val defaultTriggerMiddleConfig = ScopeConfig<DefaultTriggerMiddleState, DefaultTriggerMiddleWhen>(
    initial = DefaultTriggerMiddleState(),
    update = { state, _, _, _ -> state },
    subScopes = listOf(defaultTriggerMiddleGrandchildSubScope)
)

private data class DefaultTriggerRootState(val middle: DefaultTriggerMiddleState? = null)
private sealed interface DefaultTriggerRootWhen {
    data object CreateMiddleWithGrandchildAlreadyPresent : DefaultTriggerRootWhen
}
private val defaultTriggerRootMiddleSubScope =
    SubScope<DefaultTriggerRootState, DefaultTriggerRootWhen, DefaultTriggerMiddleState, DefaultTriggerMiddleWhen>(
        config = defaultTriggerMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) }
    )
private val defaultTriggerRootConfig = ScopeConfig<DefaultTriggerRootState, DefaultTriggerRootWhen>(
    initial = DefaultTriggerRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is DefaultTriggerRootWhen.CreateMiddleWithGrandchildAlreadyPresent ->
                state.copy(middle = DefaultTriggerMiddleState(grandchild = DefaultTriggerGrandchildState()))
        }
    },
    subScopes = listOf(defaultTriggerRootMiddleSubScope)
)

// Live-ancestor cascade (ScopeConfig.liveAncestorKey/deriveFromLiveAncestor) — the synchronous,
// direct-field-push mechanism that replaces the old "@ParentState re-stamps after THIS reducer's
// own update() returns" approach, which permanently lagged by one cycle whenever the ancestor's
// data changed as a side effect of the very event reading it (see ParentState.kt's own doc,
// written before this cascade existed, for the device-confirmed real-app bug this closes).
// Mirrors the newsfeed app's actual shape: a child delegates UserTappedFavorite up via
// onChildAction, the owner's OWN update() is what actually changes favorites, and the child needs
// the owner's brand new value WITHIN that same send() call, not on its own next event.
private val cascadeOwnerKey = SuperstateKey<CascadeOwnerState>("CascadeOwner")

private data class CascadeOwnerState(val favorites: List<String> = emptyList(), val child: CascadeChildState? = null)
private sealed interface CascadeOwnerWhen {
    data object ActivateChild : CascadeOwnerWhen
    data object DeactivateChild : CascadeOwnerWhen
    data class ToggleFavorite(val id: String) : CascadeOwnerWhen
}

// The placeholder default is deliberately distinct from anything the owner could ever hold, so a
// test observing it proves the cascade never ran, not just that it happened to match by accident.
private data class CascadeChildState(val parentFavorites: List<String> = listOf("placeholder-should-never-be-observed"))
private sealed interface CascadeChildWhen {
    data class UserTappedFavorite(val id: String) : CascadeChildWhen
}
private val cascadeChildConfig = ScopeConfig<CascadeChildState, CascadeChildWhen>(
    initial = CascadeChildState(),
    // Never touches parentFavorites itself — deriveFromLiveAncestor, below, is the only thing
    // that ever writes it, proving the field's correctness comes entirely from the cascade.
    update = { state, _, _, _ -> state },
    liveAncestorKey = cascadeOwnerKey,
    deriveFromLiveAncestor = { state, dependencies -> state.copy(parentFavorites = dependencies.resolve(cascadeOwnerKey).favorites) }
)
private val cascadeOwnerChildSubScope =
    SubScope<CascadeOwnerState, CascadeOwnerWhen, CascadeChildState, CascadeChildWhen>(
        config = cascadeChildConfig,
        get = { it.child },
        set = { state, child -> state.copy(child = child) },
        onChildAction = { childWhen, _, _ ->
            when (childWhen) {
                is CascadeChildWhen.UserTappedFavorite -> CascadeOwnerWhen.ToggleFavorite(childWhen.id)
            }
        },
        superstateKey = cascadeOwnerKey
    )
private val cascadeOwnerConfig = ScopeConfig<CascadeOwnerState, CascadeOwnerWhen>(
    initial = CascadeOwnerState(),
    update = { state, action, _, _ ->
        when (action) {
            is CascadeOwnerWhen.ActivateChild -> state.copy(child = CascadeChildState())
            is CascadeOwnerWhen.DeactivateChild -> state.copy(child = null)
            is CascadeOwnerWhen.ToggleFavorite ->
                state.copy(favorites = if (action.id in state.favorites) state.favorites - action.id else state.favorites + action.id)
        }
    },
    subScopes = listOf(cascadeOwnerChildSubScope)
)

// Root -> Middle -> Leaf, the Leaf skipping its direct parent to read the Root's own live state —
// proving the cascade reaches a skip-level dependent too, with zero plumbing at the Middle level,
// the same shape testSuperstateKeySkipLevelReflectsLiveMiddleStateNotStaleRootMirror already
// proves for plain on-demand resolve().
private val cascadeRootKey = SuperstateKey<CascadeRootState>("CascadeRoot")

private data class CascadeRootState(val favorites: List<String> = emptyList(), val middle: CascadeMiddleState? = null)
private sealed interface CascadeRootWhen {
    data object ActivateMiddle : CascadeRootWhen
    data class ToggleFavorite(val id: String) : CascadeRootWhen
}

private data class CascadeMiddleState(val leaf: CascadeLeafState? = null)
private sealed interface CascadeMiddleWhen {
    data object ActivateLeaf : CascadeMiddleWhen
}

private data class CascadeLeafState(val rootFavorites: List<String> = listOf("placeholder"))
private sealed interface CascadeLeafWhen {
    data object NoOp : CascadeLeafWhen
}

// Deliberately no superstateKey here — inherited unchanged from the root, exactly like
// stalenessMiddleLeafSubScope above.
private val cascadeMiddleLeafSubScope =
    SubScope<CascadeMiddleState, CascadeMiddleWhen, CascadeLeafState, CascadeLeafWhen>(
        config = ScopeConfig(
            initial = CascadeLeafState(),
            update = { state, _, _, _ -> state },
            liveAncestorKey = cascadeRootKey,
            deriveFromLiveAncestor = { state, dependencies -> state.copy(rootFavorites = dependencies.resolve(cascadeRootKey).favorites) }
        ),
        get = { it.leaf },
        set = { state, leaf -> state.copy(leaf = leaf) }
    )
private val cascadeMiddleConfig = ScopeConfig<CascadeMiddleState, CascadeMiddleWhen>(
    initial = CascadeMiddleState(),
    update = { state, action, _, _ ->
        when (action) {
            is CascadeMiddleWhen.ActivateLeaf -> state.copy(leaf = CascadeLeafState())
        }
    },
    subScopes = listOf(cascadeMiddleLeafSubScope)
)
private val cascadeRootMiddleSubScope =
    SubScope<CascadeRootState, CascadeRootWhen, CascadeMiddleState, CascadeMiddleWhen>(
        config = cascadeMiddleConfig,
        get = { it.middle },
        set = { state, middle -> state.copy(middle = middle) },
        superstateKey = cascadeRootKey
    )
private val cascadeRootConfig = ScopeConfig<CascadeRootState, CascadeRootWhen>(
    initial = CascadeRootState(),
    update = { state, action, _, _ ->
        when (action) {
            is CascadeRootWhen.ActivateMiddle -> state.copy(middle = CascadeMiddleState())
            is CascadeRootWhen.ToggleFavorite -> state.copy(favorites = state.favorites + action.id)
        }
    },
    subScopes = listOf(cascadeRootMiddleSubScope)
)

class ScopeStoreTest {

    // scopeEffectsDisabledInUnitTests defaults to true under JUnit (see ScopeStore.kt) so a test
    // calling createScope(...) directly doesn't accidentally run real effects. A test whose whole
    // point is proving genuine effect execution (real Job launched, real cancellation, real
    // cross-thread delivery) opts back in with this — scoped to exactly the one test that calls
    // it, via try/finally, so the flag can never leak into any other test in this class.
    //
    // NOT a @BeforeTest/@AfterTest pair: kotlin.test's @BeforeTest/@AfterTest apply to every test
    // method in the class, unconditionally — there is no per-method setup/teardown in
    // Kotlin/JUnit5. An earlier version of this file had exactly that mistake (a @BeforeTest
    // that set this flag false "for the one test that needs it"), which silently flipped it false
    // for every other test in the class too, including ones whose correctness depends on it being
    // true (completeEffect() relies on an effect being tracked as pending-but-never-launched
    // bookkeeping; with real effects enabled, a same-tick-completing effect could remove itself
    // from bookkeeping before completeEffect() ever ran, racing non-deterministically). Found via
    // exactly that symptom: testCompleteEffectFindsTheRequestedTypeAmongMultiplePendingAndReplaysItsRealMap
    // failed intermittently until this was fixed.
    private fun withRealEffectsEnabled(block: () -> Unit) {
        scopeEffectsDisabledInUnitTests = false
        try {
            block()
        } finally {
            scopeEffectsDisabledInUnitTests = true
        }
    }

    @Test
    fun testInitialStateIsConfigInitial() {
        val store = createScope(testConfig)
        assertEquals(TestState(), store.state)
    }

    @Test
    fun testStateFlowValueAlwaysMatchesStateAfterSend() {
        val store = createScope(testConfig)

        assertEquals(store.state, store.stateFlow.value)

        store.send(TestWhen.Increment)

        assertEquals(1, store.stateFlow.value.count, "stateFlow must reflect a synchronous send()")
        assertEquals(store.state, store.stateFlow.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testStateFlowEmitsWhenAnEffectCompletionUpdatesState() = withRealEffectsEnabled {
        runTest {
            val store = createScope(effectConfig, effectScope = this)

            val seenMarkers = mutableListOf<String>()
            val collector = launch { store.stateFlow.collect { seenMarkers += it.marker } }

            store.send(TestWhen.Increment)
            advanceUntilIdle()
            collector.cancel()

            // Only the effect's own completion event could have produced this — proves a
            // collector sees the async, effect-driven update, not just the synchronous part of
            // send().
            assertTrue(seenMarkers.contains("effect-completed"), "expected stateFlow to emit the effect-driven marker, got: $seenMarkers")
        }
    }

    @Test
    fun testSendUpdatesStateWithoutMiddleware() {
        val store = createScope(testConfig)
        store.send(TestWhen.Increment)
        assertEquals(1, store.state.count)
    }

    @Test
    fun testAddMiddlewareReturnsSameStore() {
        val store = createScope(testConfig)
        val returned = store.addMiddleware { _, action, forward -> forward(action) }
        assertSame(store, returned)
    }

    @Test
    fun testMiddlewareCanBlockAnEventByNeverCallingForward() {
        val store = createScope(testConfig)
            .addMiddleware { _, _, _ -> /* never calls forward: the event is blocked */ }

        store.send(TestWhen.Increment)

        assertEquals(0, store.state.count, "Blocked event must never reach update()")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEnqueuedEffectActuallyRunsAndFeedsBackResult() = withRealEffectsEnabled {
        runTest {
            // Passing this test's own TestScope as effectScope lets advanceUntilIdle() below
            // deterministically drive the effect's coroutine to completion — no real delay, no
            // flaky polling.
            val store = createScope(effectConfig, effectScope = this)

            store.send(TestWhen.Increment)
            // The synchronous part of update() has run; the effect it enqueued has not yet.
            assertEquals(1, store.state.count)
            assertEquals("initial", store.state.marker)

            advanceUntilIdle()

            // Only the effect's own completion event could have produced this value.
            assertEquals("effect-completed", store.state.marker)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectDoesNotRunWhenDisabledInUnitTests() = runTest {
        scopeEffectsDisabledInUnitTests = true // the actual default; set explicitly for clarity

        val store = createScope(effectConfig, effectScope = this)
        store.send(TestWhen.Increment)
        advanceUntilIdle()

        assertEquals(1, store.state.count, "the synchronous part of update() still ran")
        assertEquals("initial", store.state.marker, "the enqueued effect must never have been launched")
    }

    @Test
    fun testEffectCompletionsAlwaysArriveOnTheSameDedicatedThread() = withRealEffectsEnabled {
        // Thread.currentThread().id (a stable OS-thread identity), not .name, proves this —
        // kotlinx.coroutines' debug mode (auto-enabled by Gradle's -ea) suffixes the thread's
        // *name* with " @coroutine#N" per coroutine, so two completions on the very same
        // physical thread can still show different names.
        val threadIds = ConcurrentLinkedQueue<Long>()
        var oneThreadName: String? = null
        val bothCompleted = CountDownLatch(2)
        val config = ScopeConfig<TestState, TestWhen>(
            initial = TestState(),
            update = { state, action, effects, _ ->
                when (action) {
                    is TestWhen.Increment -> {
                        effects.enqueue(object : Effect<TestWhen> {
                            override suspend fun run(): TestWhen {
                                threadIds += Thread.currentThread().threadId()
                                oneThreadName = Thread.currentThread().name
                                bothCompleted.countDown()
                                return TestWhen.MarkerSet("done")
                            }
                        })
                        state.copy(count = state.count + 1)
                    }
                    is TestWhen.MarkerSet -> state.copy(marker = action.value)
                }
            }
        )

        // Uses the real default effectScope (defaultEffectDispatcher), not a TestScope — the
        // point is proving genuine cross-thread delivery lands on one consistent thread, which
        // a virtual test dispatcher wouldn't demonstrate.
        val store = createScope(config)
        store.send(TestWhen.Increment)
        store.send(TestWhen.Increment)

        assertTrue(bothCompleted.await(2, TimeUnit.SECONDS), "both effects should have completed within 2s")
        assertEquals(2, threadIds.size)
        assertEquals(1, threadIds.toSet().size, "expected both effect completions on the same OS thread, got ids: $threadIds")
        assertTrue(
            oneThreadName?.startsWith("Statoscope-EffectDispatcher") == true,
            "expected the dedicated dispatcher's thread name, got: $oneThreadName"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCancelEffectActuallyCancelsTheRunningJob() = withRealEffectsEnabled {
        runTest {
            val store = createScope(cancelConfig, effectScope = this)

            store.send(CancelWhen.Start)
            // SlowEffect is now launched, suspended in delay(10_000) — genuinely ongoing, not completed.
            store.send(CancelWhen.CancelOngoing)

            // If cancelEffect only removed bookkeeping without cancelling the real Job, advancing
            // past the 10s delay here would let SlowEffect complete anyway and overwrite the marker.
            advanceUntilIdle()

            assertEquals("cancelled", store.state.marker, "a cancelled effect's completion must never arrive")
        }
    }

    @OptIn(StatoscopeInternalApi::class)
    @Test
    fun testCompleteEffectFindsTheRequestedTypeAmongMultiplePendingAndReplaysItsRealMap() {
        val store = createScope(multiEffectConfig)
        store.send(MultiEffectWhen.StartBoth)
        // Both RawIntFetch and RawStringFetch are pending, oldest (Int) first — completeEffect
        // must find RawStringFetch specifically, not whichever happens to be oldest.
        assertTrue(store.completeEffect(RawStringFetch::class, "real-string"))

        assertEquals("real-string", store.state.marker, "the real .map() must have run, not a guessed value")
        assertEquals(0, store.state.count, "the still-pending RawIntFetch must be untouched")

        assertTrue(store.completeEffect(RawIntFetch::class, 7))
        assertEquals(7, store.state.count)
    }

    @OptIn(StatoscopeInternalApi::class)
    @Test
    fun testCompleteEffectReturnsFalseWhenNoPendingEffectMatchesTheType() {
        val store = createScope(multiEffectConfig)
        assertTrue(!store.completeEffect(RawStringFetch::class, "unused"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDeactivatingAChildCancelsItsOwnRunningEffect() = withRealEffectsEnabled {
        runTest {
            val store = createScope(parentWithChildConfig, effectScope = this)

            store.send(ParentWithChildWhen.ActivateChild)
            val childStore = store.childStore(childSubScope)!!
            childStore.send(CancelWhen.Start)
            // SlowEffect is now launched under the child, suspended in delay(10_000) — genuinely ongoing.
            assertEquals("started", childStore.state.marker)

            store.send(ParentWithChildWhen.DeactivateChild)
            assertEquals(null, store.state.child, "child was deactivated")

            // If deactivateChild only dropped the child from activeChildren without cancelling its
            // real Job, advancing past the 10s delay here would let SlowEffect complete anyway,
            // silently updating this now-orphaned store's own state that nothing in the tree
            // references anymore.
            advanceUntilIdle()

            assertEquals(
                "started", childStore.state.marker,
                "a deactivated child's own running effect must never complete"
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectsLetsAReducerAvoidDoubleTriggeringAnAlreadyOngoingEffect() = runTest {
        val store = createScope(queryConfig, effectScope = this)

        store.send(QueryWhen.StartIfNotAlreadyRunning)
        assertEquals("started", store.state.marker)

        // Deliberately no withRealEffectsEnabled here: effects.effects reflects runningEffects'
        // bookkeeping, which is recorded regardless of scopeEffectsDisabledInUnitTests (only the
        // real launch is skipped under the default) — this test exercises that query mechanism
        // directly, not real execution, so the default (true) is both correct and sufficient.
        store.send(QueryWhen.StartIfNotAlreadyRunning)
        assertEquals(
            "already running, skipped", store.state.marker,
            "effects.effects should have shown the still-ongoing CountingSlowEffect to this second call"
        )
    }

    // MARK: - onDescendantAction (broadcast reach, Issue 7)

    @Test
    fun testOnDescendantActionReachesAGrandchildWithNoRelayAtTheMiddleLevel() {
        val root = createScope(broadcastRootConfig)
        root.send(BroadcastRootWhen.ActivateMiddle)
        val middle = root.childStore(broadcastRootMiddleSubScope)!!
        middle.send(BroadcastMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(broadcastMiddleGrandchildSubScope)!!

        grandchild.send(BroadcastGrandchildWhen.Tap)

        assertTrue(grandchild.state.tapped, "the grandchild's own update() still ran normally")
        assertEquals(
            1, root.state.grandchildTapsSeenAtRoot,
            "the root saw the grandchild's event directly, with no onChildAction relay anywhere " +
                "on the middle level's SubScope"
        )
    }

    @Test
    fun testOnDescendantActionFiresExactlyOncePerDescendantEvent() {
        val root = createScope(broadcastRootConfig)
        root.send(BroadcastRootWhen.ActivateMiddle)
        val middle = root.childStore(broadcastRootMiddleSubScope)!!
        middle.send(BroadcastMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(broadcastMiddleGrandchildSubScope)!!

        grandchild.send(BroadcastGrandchildWhen.Tap)
        grandchild.send(BroadcastGrandchildWhen.Tap)
        grandchild.send(BroadcastGrandchildWhen.Tap)

        assertEquals(
            3, root.state.grandchildTapsSeenAtRoot,
            "each descendant event should notify ancestors exactly once — not zero, not more than once"
        )
    }

    @Test
    fun testOnDescendantActionDoesNotFireForTheRootsOwnSelfSentEvents() {
        val root = createScope(broadcastRootConfig)

        // ActivateMiddle is the root's OWN event, sent to itself — not a descendant's. The walk
        // starts at `parent`, not `this`, so this must never reach the root's own
        // onDescendantAction (which only matches BroadcastGrandchildWhen.Tap anyway, but the
        // real point is this is a *self*-sent event with no descendant involved at all).
        root.send(BroadcastRootWhen.ActivateMiddle)

        assertEquals(
            0, root.state.grandchildTapsSeenAtRoot,
            "a store's own self-sent event must never trigger its own onDescendantAction"
        )
    }

    // TEST_PARITY_MAP.md #3 / Swift's ReducerUpdateSubstateErrorTests.
    // testUpdateSubstateIsNotCalledWhenGrandchildThrows: a throwing descendant update() must
    // pre-empt onDescendantAction entirely — not deliver it a partial/stale descendantState, not
    // leave some ancestors notified and others not. Reading ScopeStoreImpl.process()/
    // notifyAncestors first (per this item's own caution) showed WHY that's already true here,
    // by construction, not by any explicit guard: config.update's throw happens before newState
    // is even computed, so process() never reaches its own mutableStateFlow.value publish OR its
    // notifyAncestors(...) call — both are later statements in the same function, and a Kotlin
    // exception skips everything after the throw point, same function or not. The exception then
    // unwinds through the grandchild's own installed middleware (finishActivatingChild) — skipping
    // the parent-mirroring write and the middle level's own onChildAction, for the same reason —
    // and is finally swallowed by the grandchild's own send() (matching every other store's
    // documented at-the-boundary swallow). Net effect: a fully atomic no-op from the broadcast
    // machinery's perspective, exactly what Swift's two tests prove. This test closes the gap by
    // confirming it, not by changing any production code.
    @Test
    fun testOnDescendantActionDoesNotFireWhenTheDescendantsUpdateThrows() {
        val root = createScope(broadcastRootConfig)
        root.send(BroadcastRootWhen.ActivateMiddle)
        val middle = root.childStore(broadcastRootMiddleSubScope)!!
        middle.send(BroadcastMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(broadcastMiddleGrandchildSubScope)!!

        grandchild.send(BroadcastGrandchildWhen.Fail)

        assertFalse(
            grandchild.state.tapped,
            "the throwing update() must not have committed any partial state of its own"
        )
        assertEquals(
            0, root.state.grandchildTapsSeenAtRoot,
            "a descendant's thrown exception must pre-empt onDescendantAction entirely, not just " +
                "skip it after a partial commit"
        )

        // The failed send() must not have left the broadcast machinery (or this store's own
        // middleware/send() plumbing) in a broken state — a normal event right after should
        // still work exactly as before the failure.
        grandchild.send(BroadcastGrandchildWhen.Tap)
        assertTrue(grandchild.state.tapped, "a normal event after the failure should still apply")
        assertEquals(
            1, root.state.grandchildTapsSeenAtRoot,
            "a normal event after the failure should still reach the root's onDescendantAction"
        )
    }

    // MARK: - Ancestor-tree InjectionKey resolution

    @Test
    fun testInjectionKeyResolvesFromAnAncestorWithNoIntermediatePlumbing() {
        val root = createScope(ancestorRootConfig)
        root.send(AncestorRootWhen.ActivateMiddle)
        val middle = root.childStore(ancestorRootMiddleSubScope)!!
        middle.send(AncestorMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(ancestorMiddleGrandchildSubScope)!!

        grandchild.send(AncestorGrandchildWhen.ReadGreeting)

        assertEquals(
            "hello-from-root", grandchild.state.greeting,
            "the grandchild should resolve the root's provide with zero plumbing at the middle level"
        )
    }

    @Test
    fun testInjectionKeyResolutionPrefersTheNearestAncestorsOverride() {
        val root = createScope(ancestorRootConfigWithMiddleOverride)
        root.send(AncestorRootWhen.ActivateMiddle)
        val middle = root.childStore(ancestorRootMiddleWithOverrideSubScope)!!
        middle.send(AncestorMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(ancestorMiddleGrandchildSubScope)!!

        grandchild.send(AncestorGrandchildWhen.ReadGreeting)

        assertEquals(
            "hello-from-middle", grandchild.state.greeting,
            "the nearer ancestor's override should win over the root's"
        )
    }

    @Test
    fun testInjectionKeyFallsBackToItsOwnDefaultWhenNoAncestorProvidesIt() {
        val orphan = createScope(ancestorGrandchildConfig)

        orphan.send(AncestorGrandchildWhen.ReadGreeting)

        assertEquals(
            "default-greeting", orphan.state.greeting,
            "no ancestor at all exists here, so the key's own defaultValue must apply"
        )
    }

    // MARK: - ScopeStore.inject (explicit injection after the store already exists)

    @Test
    fun testInjectOverridesAnInjectionKeyAfterTheStoreAlreadyExists() {
        val orphan = createScope(ancestorGrandchildConfig)

        orphan.inject(ancestorGreetingKey, "hello-from-inject")
        orphan.send(AncestorGrandchildWhen.ReadGreeting)

        assertEquals(
            "hello-from-inject", orphan.state.greeting,
            "inject() should win over the key's own defaultValue, same as a provide entry would"
        )
    }

    @Test
    fun testInjectOnAParentReachesAnAlreadyActiveChildWithNoRebuild() {
        val root = createScope(ancestorRootConfig)
        root.send(AncestorRootWhen.ActivateMiddle)
        val middle = root.childStore(ancestorRootMiddleSubScope)!!
        middle.send(AncestorMiddleWhen.ActivateGrandchild)
        val grandchild = middle.childStore(ancestorMiddleGrandchildSubScope)!!

        // The grandchild already exists, built before this call — proving inject() doesn't need
        // a rebuild to reach it; resolution just reads the root's (now-mutated) map fresh.
        root.inject(ancestorGreetingKey, "hello-from-inject")
        grandchild.send(AncestorGrandchildWhen.ReadGreeting)

        assertEquals(
            "hello-from-inject", grandchild.state.greeting,
            "an already-active grandchild should see a parent's inject() on its very next resolve"
        )
    }

    @Test
    fun testDependenciesInjectFromInsideUpdateIsSeenByTheSameStoresLaterResolves() {
        val key = InjectionKey("default", "injectedFromUpdate")
        val store = createScope(
            ScopeConfig<String, String>(
                initial = "",
                update = { state, action, _, dependencies ->
                    if (action == "inject") {
                        dependencies.inject(key, "injected")
                        state
                    } else {
                        dependencies.resolve(key)
                    }
                }
            )
        )

        store.send("read")
        assertEquals("default", store.state)
        store.send("inject")
        store.send("read")
        assertEquals("injected", store.state, "inject() inside update() should behave like ScopeStore.inject")
    }

    // MARK: - SuperstateKey (ancestor-tree live read)

    @Test
    fun testSuperstateKeySkipLevelReflectsLiveMiddleStateNotStaleRootMirror() {
        val root = createScope(stalenessRootConfig)
        root.send(StalenessRootWhen.CreateMiddle)
        val middle = root.childStore(stalenessRootMiddleSubScope)!!
        middle.send(StalenessMiddleWhen.CreateLeaf)
        val leaf = middle.childStore(stalenessMiddleLeafSubScope)!!

        // Mutate middle directly — root never reprocesses an event of its own because of this,
        // so if anything cached a mirror of middle at root-activation time, it would be stale here.
        middle.send(StalenessMiddleWhen.SetLabel("updated"))

        leaf.send(StalenessLeafWhen.ReadRootsMiddleLabel)

        assertEquals(
            "updated", leaf.state.observedMiddleLabel,
            "the leaf's skip-level read of the root must see middle's CURRENT label, not " +
                "whatever the root's own mirror of it held before middle was mutated directly"
        )
    }

    @OptIn(StatoscopeInternalApi::class)
    @Test
    fun testSuperstateKeyWithNoAncestorErrorsRatherThanFallingBackToADefault() {
        val dependencies = MapDependencies(emptyList<Pair<InjectionKey<*>, Any?>>())

        val error = assertFailsWith<IllegalStateException> {
            dependencies.resolve(stalenessRootSuperstateKey)
        }
        assertTrue(
            error.message!!.contains("No ancestor provides"),
            "unlike InjectionKey, a SuperstateKey has no key-level default to fall back to — " +
                "resolving one with nothing on the path declaring it is a programmer error"
        )
    }

    // MARK: - Pre-populated grandchild at child-creation time (test-parity audit finding)

    @Test
    fun testCreatingAChildWithAnAlreadyNonNullGrandchildActivatesTheGrandchildToo() {
        val root = createScope(prePopulatedRootConfig)
        root.send(PrePopulatedRootWhen.CreateMiddleWithGrandchildAlreadyPresent)

        val middle = root.childStore(prePopulatedRootMiddleSubScope)!!
        assertEquals(
            "pre-populated", middle.state.grandchild?.value,
            "the middle's own state should already reflect the grandchild it was created with"
        )

        val grandchild = middle.childStore(prePopulatedMiddleGrandchildSubScope)
        assertNotNull(
            grandchild,
            "a grandchild already present in the child's initial state (set in the SAME " +
                "update() call that created the child, not a separate later event) should have " +
                "its own real ScopeStore activated too — Swift does this recursively at " +
                "child-creation time (Store.swift's _applyChildSlotsRecursively)"
        )
    }

    // MARK: - Reassigning an already-active child's field (test-parity audit findings 1 & 2)

    @Test
    fun testReassigningAnActiveChildDiscardsItsGrandchildren() {
        val root = createScope(prePopulatedRootConfig)
        root.send(PrePopulatedRootWhen.CreateMiddleWithGrandchildAlreadyPresent)
        val originalMiddle = root.childStore(prePopulatedRootMiddleSubScope)!!
        assertNotNull(
            originalMiddle.childStore(prePopulatedMiddleGrandchildSubScope),
            "sanity check: the grandchild should exist before the reassignment"
        )

        root.send(PrePopulatedRootWhen.ReassignMiddleToAFreshValue)
        val replacementMiddle = root.childStore(prePopulatedRootMiddleSubScope)!!

        assertNull(
            replacementMiddle.childStore(prePopulatedMiddleGrandchildSubScope),
            "reassigning a child discards its previous subtree outright — no grandchild " +
                "migration — ports Swift's ReducerTests.testReassignChildStateDiscardsGrandchildren"
        )
    }

    @Test
    fun testReassigningAnActiveChildAlwaysCreatesANewStoreInstance() {
        val root = createScope(prePopulatedRootConfig)
        root.send(PrePopulatedRootWhen.CreateMiddleWithGrandchildAlreadyPresent)
        val originalMiddle = root.childStore(prePopulatedRootMiddleSubScope)!!

        root.send(PrePopulatedRootWhen.ReassignMiddleToAFreshValue)
        val replacementMiddle = root.childStore(prePopulatedRootMiddleSubScope)!!

        assertFalse(
            originalMiddle === replacementMiddle,
            "reassigning an already-active child's field must always produce a genuinely new " +
                "Store, matching the field's new value, never mutate the live one in place — " +
                "ports Swift's MiddlewareReducerTests.testReassigningChildStateAlwaysCreatesNewStore"
        )
        assertEquals(
            PrePopulatedMiddleState(), replacementMiddle.state,
            "the new store's own state should reflect the replacement value, not the old one"
        )
    }

    // MARK: - ScopeConfig.defaultTrigger

    @Test
    fun testDefaultTriggerFiresExactlyOnceWhenChildIsWired() {
        val root = createScope(defaultTriggerParentConfig)
        root.send(DefaultTriggerParentWhen.CreateChild)

        val child = root.childStore(defaultTriggerChildSubScope)!!
        assertEquals(1, child.state.initializeCount, "defaultTrigger should fire exactly once on wiring")
    }

    @Test
    fun testDefaultTriggerFiresOnlyOnceNotOnSubsequentEvents() {
        val root = createScope(defaultTriggerParentConfig)
        root.send(DefaultTriggerParentWhen.CreateChild)
        val child = root.childStore(defaultTriggerChildSubScope)!!

        child.send(DefaultTriggerChildWhen.SomeOtherEvent)
        child.send(DefaultTriggerChildWhen.SomeOtherEvent)

        assertEquals(1, child.state.initializeCount, "defaultTrigger fires only on wiring, not on subsequent events")
        assertEquals(2, child.state.otherEventCount)
    }

    @Test
    fun testDefaultTriggerFiresAgainWhenChildIsRecreated() {
        val root = createScope(defaultTriggerParentConfig)
        root.send(DefaultTriggerParentWhen.CreateChild)
        root.send(DefaultTriggerParentWhen.DestroyChild)
        root.send(DefaultTriggerParentWhen.CreateChild)

        val child = root.childStore(defaultTriggerChildSubScope)!!
        assertEquals(1, child.state.initializeCount, "a freshly (re)created child gets its own defaultTrigger")
    }

    @Test
    fun testNoDefaultTriggerForAReducerWithoutOne() {
        val root = createScope(noDefaultTriggerParentConfig)
        root.send(DefaultTriggerParentWhen.CreateChild)

        val child = root.childStore(noDefaultTriggerChildSubScope)!!
        assertEquals(0, child.state.initializeCount, "no defaultTrigger means no automatic event")
    }

    @Test
    fun testDefaultTriggerFiresForAnInitTimePrePopulatedChildToo() {
        val root = createScope(defaultTriggerRootConfig)
        root.send(DefaultTriggerRootWhen.CreateMiddleWithGrandchildAlreadyPresent)

        val middle = root.childStore(defaultTriggerRootMiddleSubScope)!!
        val grandchild = middle.childStore(defaultTriggerMiddleGrandchildSubScope)!!

        assertEquals(
            1, grandchild.state.initializeCount,
            "defaultTrigger fires for every activation, including an init-time pre-populated " +
                "one — a deliberate Kotlin choice, see ScopeConfig.defaultTrigger's own doc"
        )
    }

    // MARK: - Live-ancestor cascade (ScopeConfig.liveAncestorKey/deriveFromLiveAncestor)

    @Test
    fun testLiveAncestorCascadePushesTheOwnersCurrentValueImmediatelyAtActivation() {
        val owner = createScope(cascadeOwnerConfig)
        // A real favorite exists BEFORE the child is even created — proving activation pushes
        // the owner's genuinely current value, not just happens to match an empty default.
        owner.send(CascadeOwnerWhen.ToggleFavorite("pre-existing"))
        owner.send(CascadeOwnerWhen.ActivateChild)

        val child = owner.childStore(cascadeOwnerChildSubScope)!!

        assertEquals(
            listOf("pre-existing"), child.state.parentFavorites,
            "the child's very first observable state should already reflect the owner's current " +
                "value, never config.initial's own placeholder default"
        )
        assertEquals(
            listOf("pre-existing"), owner.state.child?.parentFavorites,
            "the push must also be visible through the OWNER's own mirrored copy of the child " +
                "— the exact read path statoscope.android.compose.ConnectedChild actually uses " +
                "(subScope.get(parentState)), not a second subscription on the child itself"
        )
    }

    @Test
    fun testLiveAncestorCascadePushesWithinTheSameSendThatCausedTheOwnersChange() {
        val owner = createScope(cascadeOwnerConfig)
        owner.send(CascadeOwnerWhen.ActivateChild)
        val child = owner.childStore(cascadeOwnerChildSubScope)!!
        assertEquals(emptyList<String>(), child.state.parentFavorites)

        // The exact device-confirmed bug scenario: the child's own event is what CAUSES the
        // owner's change (via onChildAction delegation), not something unrelated to it. The old
        // "re-stamp after this reducer's own update() returns" approach always lagged here —
        // this one send() call must leave the child already showing the owner's new value.
        child.send(CascadeChildWhen.UserTappedFavorite("x"))

        assertEquals(listOf("x"), owner.state.favorites, "sanity: the delegation itself worked")
        assertEquals(
            listOf("x"), child.state.parentFavorites,
            "no lag: the cascade must push the owner's new value within the same send() call " +
                "that caused it, not wait for the child's own next event"
        )
        assertEquals(
            listOf("x"), owner.state.child?.parentFavorites,
            "no lag through the OWNER's own mirror either — the read path a real Compose UI " +
                "(ConnectedChild) actually uses, not just the child store's own stateFlow"
        )

        // And the reverse direction (toggling off) must be immediate too, not lag by one more tap.
        child.send(CascadeChildWhen.UserTappedFavorite("x"))
        assertEquals(emptyList<String>(), owner.state.favorites)
        assertEquals(emptyList<String>(), child.state.parentFavorites)
        assertEquals(emptyList<String>(), owner.state.child?.parentFavorites)
    }

    @Test
    fun testLiveAncestorCascadeReachesASkipLevelDescendantWithNoPlumbingAtTheMiddleLevel() {
        val root = createScope(cascadeRootConfig)
        root.send(CascadeRootWhen.ToggleFavorite("x"))
        root.send(CascadeRootWhen.ActivateMiddle)
        val middle = root.childStore(cascadeRootMiddleSubScope)!!
        middle.send(CascadeMiddleWhen.ActivateLeaf)
        val leaf = middle.childStore(cascadeMiddleLeafSubScope)!!

        assertEquals(
            listOf("x"), leaf.state.rootFavorites,
            "the leaf's very first state should already reflect the root's current value, " +
                "through the middle's inherited (unlabeled) SuperstateKey"
        )

        root.send(CascadeRootWhen.ToggleFavorite("y"))

        assertEquals(
            listOf("x", "y"), leaf.state.rootFavorites,
            "a later root-level change should push straight through to the skip-level leaf, " +
                "with no action at the middle level at all"
        )
        assertEquals(
            listOf("x", "y"), middle.state.leaf?.rootFavorites,
            "the push must also reach the MIDDLE's own mirror of the leaf — middle is the " +
                "leaf's real structural parent regardless of which ancestor owns the key, and " +
                "that mirror is what a nested ConnectedChild(middleStore, ...) would read"
        )
    }

    @Test
    fun testDeactivatingADependentStopsItFromReceivingFurtherPushesAndDoesNotLeakIntoARecreatedChild() {
        val owner = createScope(cascadeOwnerConfig)
        owner.send(CascadeOwnerWhen.ActivateChild)
        owner.childStore(cascadeOwnerChildSubScope)!!
        owner.send(CascadeOwnerWhen.DeactivateChild)

        // If the deactivated child's callback weren't removed, this would either act on a
        // torn-down store or accumulate pointless registrations — neither should happen.
        owner.send(CascadeOwnerWhen.ToggleFavorite("after-deactivation"))

        owner.send(CascadeOwnerWhen.ActivateChild)
        val secondChild = owner.childStore(cascadeOwnerChildSubScope)!!

        assertEquals(
            listOf("after-deactivation"), secondChild.state.parentFavorites,
            "a freshly (re)activated child should see the owner's current value, with no " +
                "leftover registration from the previous (deactivated) child interfering"
        )
    }

    @Test
    fun testSiblingSlotsOfTheSameChildTypeResolveIndependently() {
        val store = createScope(twoSameTypeSiblingsConfig)
        store.send(TwoSameTypeSiblingsWhen.ActivateFirst)

        assertNotNull(store.childStore(firstSiblingSubScope))
        assertNull(
            store.childStore(secondSiblingSubScope),
            "a same-type sibling slot must not resolve the other slot's store"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testActivatingAChildRegistersItBeforeStateFlowPublishesTheFieldGoingNonNull() = runTest(UnconfinedTestDispatcher()) {
        val store = createScope(twoSameTypeSiblingsConfig)
        var childStoreSeenAtPublishTime: Any? = Unit // Unit = "never observed the transition"
        val collector = launch {
            store.stateFlow.collect { state ->
                if (state.first != null) {
                    childStoreSeenAtPublishTime = store.childStore(firstSiblingSubScope)
                }
            }
        }
        advanceUntilIdle()

        // UnconfinedTestDispatcher resumes a suspended collector inline, synchronously, the
        // moment mutableStateFlow.value is set — the same inline-resume shape
        // kotlinx-coroutines-android's Dispatchers.Main.immediate uses when send() is already
        // called from the main thread (the common case). Without this dispatcher, a collector
        // launched under the default StandardTestDispatcher only ever resumes later, well after
        // activateChild has already finished — which would hide the exact race this guards
        // against (see ConnectedScreenTest.kt in :android, which first caught it: ConnectedChild
        // calling childStore(subScope) ?: return during a synchronous recomposition triggered by
        // this same publish).
        store.send(TwoSameTypeSiblingsWhen.ActivateFirst)
        advanceUntilIdle()

        assertNotNull(
            childStoreSeenAtPublishTime,
            "a collector resumed synchronously while stateFlow publishes the newly-activated " +
                "child's field must already see childStore(subScope) non-null — not a transient " +
                "window where the state says the child exists but activeChildren doesn't yet"
        )
        assertTrue(
            childStoreSeenAtPublishTime !is Unit,
            "the collector never even observed the first != null transition"
        )
        collector.cancel()
    }
}
