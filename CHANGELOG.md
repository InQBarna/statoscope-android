# Changelog

## Unreleased

Fixes
- A descendant already present in the state of a child created by `update()` (a grandchild pre-populated in the same update) is now
  finished (middleware, live-ancestor push, `defaultTrigger`) after the ancestors publish their new state, parent first. Its
  `defaultTrigger` used to run while the ancestors still held the previous state, so a `SuperstateKey` read saw stale data
  (Swift's `@SuperState` sees the new one).

## 3.1.0

New API
- `Dependencies.inject`: `update()` can inject into its own scope.
- Codegen: a reducer's `defaultTrigger` becomes `ScopeConfig.defaultTrigger`.
- Codegen: `@SubState(provideSuperstate = true)` exposes the owner's live state to its whole subtree.
- Codegen: `@ParentState` can name any ancestor, not only the direct parent.
- `UpdateErrorHandlerKey`: a handler injected on the root hears every exception `send()` swallows, in any store of the tree (an app-wide
  error boundary; a root middleware that controls the events is planned).
- The artifacts are published as `com.inqbarna.statoscope:statoscope-*` (before: `statoscope:statoscope-kotlin`, `statoscope:android`,
  `statoscope:codegen`, `statoscope:codegen-processor`, `statoscope:testing`).

Fixes
- A child's state is mirrored up to the root, not only to its direct parent; a deactivated store no longer mirrors its state into its parent.
- `@ParentState` leaves out the branch that goes down to the reading child (it used to grow without limit).
- Generated `SubScope` vals are lazy: the order in which files are initialized no longer leaves half-built configs.
- Cancelling the running effects of a deactivated child no longer throws (a `ConcurrentModificationException` that `send()` swallowed
  silently). `send()` now logs what it swallows after the update.
- State dumps: `describeObject` guards against cycles, depth and unreadable values, uses Java reflection instead of `kotlin-reflect`, and a
  failing log message no longer breaks the store; `diffLines` falls back to linear memory on big dumps.

## 3.0.0

First release of the Kotlin port.
