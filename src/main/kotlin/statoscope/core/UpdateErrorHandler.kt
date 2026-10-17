//
// UpdateErrorHandler.kt
// statoscope-kotlin
//
package statoscope.core

/**
 * Hears every exception that stops an event from being processed, in any store of the tree, before [ScopeStore.send] swallows it.
 *
 * Swift Statoscope lets a root `Statostore` conforming to `HierarchialScopeMiddleWare` wrap `event.forward()` of every descendant
 * in a `do/catch` (an app's error boundary reporting to Crashlytics). A [Middleware] here only sees the events its own store
 * sends to itself, so the hook is an injection instead: inject a handler on the root with [ScopeStore.inject] and it reaches
 * every descendant through the dependencies chain, like any other dependency.
 *
 * Called on the thread that sent the event, after it was logged. If the handler throws, that is ignored: reporting an error must
 * never be what breaks the app.
 */
val UpdateErrorHandlerKey = InjectionKey<(Throwable) -> Unit>({ }, "UpdateErrorHandler")
