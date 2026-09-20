package statoscope.core

/**
 * Marks an API that exists only so `:testing` (the `statoscope.testing` module — Kotlin's
 * counterpart to Swift Statoscope's separate `StatoscopeTesting` SPM target) can see it across
 * a real Gradle module boundary. Ports the intent of Swift's
 * `@_spi(Internal) @testable import Statoscope`: technically public (Kotlin, unlike Swift, has
 * no SPI/`@testable` mechanism a build script can grant to one specific consumer module), but
 * gated behind opt-in so it never shows up as ordinary, supported API. App code has no reason to
 * ever write `@OptIn(StatoscopeInternalApi::class)` — if you find yourself doing that outside
 * the `:testing` module, you're almost certainly meant to use [statoscope.testing.TestPlan]
 * instead of the pieces it's built from.
 */
@RequiresOptIn(
    message = "Internal to Statoscope's own :testing module — app code should use TestPlan instead.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
annotation class StatoscopeInternalApi
