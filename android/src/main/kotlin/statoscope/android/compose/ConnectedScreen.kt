package statoscope.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import statoscope.core.ScopeStore
import statoscope.core.SubScope

/**
 * Renders [content] with [store]'s current state and a dispatch closure, re-invoked whenever
 * that state changes.
 *
 * The Compose counterpart to Swift's `ReducerStoreView`/`StoreViewProtocol` pairing — except
 * Compose needs no separate marker interface for the "isolated, State-in/When-out" view shape
 * (`StoreViewProtocol`): a plain `@Composable fun Screen(state: S, onEvent: (W) -> Unit)` already
 * *is* that shape, the idiomatic Compose convention on its own (unidirectional data flow /
 * "stateless composable"), not something this library needs to define a type for. This helper
 * exists only for the one line of ceremony (`collectAsState()` + the dispatch reference) that
 * would otherwise be repeated at every call site — see [ConnectedChild] for the version that
 * matters more: connecting a tree, not a single screen.
 *
 * ```kotlin
 * ConnectedScreen(store) { state, send ->
 *     MyScreen(state = state, onEvent = send)
 * }
 * ```
 */
@Composable
fun <State, When> ConnectedScreen(
    store: ScopeStore<State, When>,
    content: @Composable (state: State, send: (When) -> Unit) -> Unit
) {
    val state by store.stateFlow.collectAsState()
    content(state, store::send)
}

/**
 * Resolves [subScope]'s currently active child of [parentStore] — or renders nothing if it
 * doesn't exist yet — and renders [content] with the child's own state and dispatch closure,
 * re-invoked whenever either the parent creates/destroys the child, or the child's own state
 * changes.
 *
 * The Compose counterpart to Swift's `AutoConnectedView`/`build<Name>View` (macro-generated per
 * `@SubState` property) — but dramatically smaller, because the problem that machinery mostly
 * solves (`ReducerStoreRegistry`'s weak-reference stack, `ReducerRegistryCapture`,
 * `ReducerEnvironmentCapture`) doesn't exist here. That machinery exists because SwiftUI
 * evaluates `.navigationDestination`/`NavigationLink` content lazily and *detached* from the
 * environment of whoever pushed it — an `@EnvironmentObject` injected more than one level above
 * a destination becomes unreachable from inside it (confirmed on device, Swift side). Jetpack
 * Navigation Compose's `composable { }` blocks are ordinary composables in the SAME composition
 * tree as whatever hosts the `NavHost` — a [ScopeStore] reference captured in a lambda (or
 * passed as a parameter) stays reachable at any nesting depth, no registry needed.
 *
 * One subscription, not two: [parentStore]'s own `stateFlow` already mirrors the child's current
 * state synchronously the moment the child processes anything (see `ScopeStoreImpl.activateChild`'s
 * middleware) — reading the child's state through [subScope]'s own lens off the collected parent
 * state, rather than separately collecting the child store's `stateFlow` too, catches both "the
 * parent created/destroyed the child" and "the child's own state changed" from the one read.
 *
 * [subScope] is the same stable, identity-based reference `@SubState`'s own codegen already
 * generates one `val` per child slot for (see `CODEGEN.md`) — no new per-child codegen needed
 * for this either; a hand-written `SubScope` works exactly the same way.
 *
 * ```kotlin
 * ConnectedChild(parentStore, newsFeedReducerListSubScope) { listState, listSend ->
 *     NewsFeedListScreen(state = listState, onEvent = listSend)
 * }
 * ```
 */
@Composable
fun <ParentState, ParentWhen, ChildState, ChildWhen> ConnectedChild(
    parentStore: ScopeStore<ParentState, ParentWhen>,
    subScope: SubScope<ParentState, ParentWhen, ChildState, ChildWhen>,
    content: @Composable (state: ChildState, send: (ChildWhen) -> Unit) -> Unit
) {
    val parentState by parentStore.stateFlow.collectAsState()
    val childState = subScope.get(parentState) ?: return
    val childStore = parentStore.childStore(subScope) ?: return
    content(childState, childStore::send)
}
