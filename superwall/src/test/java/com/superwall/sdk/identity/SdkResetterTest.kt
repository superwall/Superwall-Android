package com.superwall.sdk.identity

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.IOScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkResetterTest {
    private val steps = mutableListOf<String>()
    private val tracked = mutableListOf<Trackable>()

    private fun resetter(clearStorage: () -> Unit = { steps += "storage" }) =
        SdkResetter(
            scope = IOScope(Dispatchers.Unconfined),
            clearStorage = clearStorage,
            clearPaywallCache = { steps += "paywallCache" },
            clearPresentationItems = { steps += "presentationItems" },
            resetConfig = { steps += "config" },
            clearAppUserRedemptions = { steps += "redemptions" },
            track = { tracked += it },
        )

    @Test
    fun `a reset clears everything in order, then tracks Reset`() =
        Given("a resetter") {
            When("the reset completes") {
                resetter().completeReset()
                Then("storage, paywall cache, presentation items, config and redemptions are cleared in order") {
                    assertEquals(
                        listOf("storage", "paywallCache", "presentationItems", "config", "redemptions"),
                        steps,
                    )
                }
                And("Reset is tracked once") {
                    assertEquals(listOf<Trackable>(InternalSuperwallEvent.Reset), tracked)
                }
            }
        }

    @Test
    fun `a failing step is swallowed and stops the rest`() =
        Given("storage that fails to clear") {
            When("the reset completes") {
                resetter(clearStorage = { throw IllegalStateException("disk") }).completeReset()
                Then("nothing after it runs and nothing is tracked, as before the move") {
                    assertEquals(emptyList<String>(), steps)
                    assertEquals(emptyList<Trackable>(), tracked)
                }
            }
        }
}
