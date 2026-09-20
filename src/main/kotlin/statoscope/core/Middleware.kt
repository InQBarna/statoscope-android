package statoscope.core

/**
 * Intercepts every event sent to a [ScopeStore] before its own `update()` runs. Call [forward]
 * to continue processing that event (or a substituted one); an exception [forward] throws can
 * be caught here instead of propagating to the original caller of `send`; omitting the call to
 * [forward] blocks the event entirely.
 *
 * This is a different mechanism from Swift Statoscope's `MiddlewareReducer` (not yet ported to
 * this library — see the `05-Scopes` tutorial when it lands): a `Middleware` sees every event a
 * *single* scope sends to itself; `MiddlewareReducer` lets a *parent* reducer react to events
 * from its own child scopes once they've already run. Reach for `Middleware` for cross-cutting
 * concerns like logging or crash reporting on one scope; `MiddlewareReducer`-equivalent
 * parent/child composition is future work here.
 */
typealias Middleware<State, When> =
    (store: ScopeStore<State, When>, action: When, forward: (When) -> Unit) -> Unit
