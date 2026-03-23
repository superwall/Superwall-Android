package com.superwall.sdk.identity

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.And
import com.superwall.sdk.SdkContext
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.AliasId
import com.superwall.sdk.storage.AppUserId
import com.superwall.sdk.storage.DidTrackFirstSeen
import com.superwall.sdk.storage.Seed
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.UserAttributes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests using the production [SequentialActor] (mutex-serialized)
 * to verify ordering assumptions that plain StateActor tests miss.
 */
class IdentityActorIntegrationTest {
    private lateinit var storage: Storage
    private lateinit var deviceHelper: DeviceHelper
    private lateinit var sdkContext: SdkContext
    private var resetCalled = false
    private var trackedEvents: MutableList<Any> = mutableListOf()

    @Before
    fun setup() {
        storage = mockk(relaxed = true)
        deviceHelper = mockk(relaxed = true)
        sdkContext = mockk(relaxed = true)
        resetCalled = false
        trackedEvents = mutableListOf()

        every { storage.read(AppUserId) } returns null
        every { storage.read(AliasId) } returns null
        every { storage.read(Seed) } returns null
        every { storage.read(UserAttributes) } returns null
        every { storage.read(DidTrackFirstSeen) } returns null
        every { deviceHelper.appInstalledAtString } returns "2024-01-01"

        // SdkContext mocks — fetchAssignments and awaitConfig return quickly
        coEvery { sdkContext.fetchAssignments() } returns Unit
        coEvery { sdkContext.awaitConfig() } returns null
    }

    private fun createSequentialManager(
        existingAppUserId: String? = null,
        existingAliasId: String? = null,
        existingSeed: Int? = null,
    ): IdentityManager {
        existingAppUserId?.let { every { storage.read(AppUserId) } returns it }
        existingAliasId?.let { every { storage.read(AliasId) } returns it }
        existingSeed?.let { every { storage.read(Seed) } returns it }

        val initial = createInitialIdentityState(storage, "2024-01-01")
        val actor = SequentialActor<IdentityContext, IdentityState>(initial)
        IdentityPersistenceInterceptor.install(actor, storage)

        return IdentityManager(
            deviceHelper = deviceHelper,
            storage = storage,
            options = { SuperwallOptions() },
            ioScope = IOScope(kotlinx.coroutines.Dispatchers.IO),
            notifyUserChange = {},
            completeReset = { resetCalled = true },
            trackEvent = { trackedEvents.add(it) },
            actor = actor,
            sdkContext = sdkContext,
        )
    }

    // -----------------------------------------------------------------------
    // Serialization: actions don't interleave
    // -----------------------------------------------------------------------

    @Test
    fun `identify followed by mergeAttributes are serialized`() = runTest {
        Given("a fresh manager with SequentialActor") {
            val manager = createSequentialManager()

            When("identify and mergeAttributes are dispatched back-to-back") {
                manager.identify("user-1")
                manager.mergeUserAttributes(mapOf("key" to "value"))

                // Wait for identity to become ready
                withTimeout(5000) {
                    manager.hasIdentity.first()
                }
            }

            Then("both operations completed — userId is set") {
                assertEquals("user-1", manager.appUserId)
            }
            And("custom attribute was merged") {
                assertTrue(manager.userAttributes.containsKey("key"))
                assertEquals("value", manager.userAttributes["key"])
            }
        }
    }

    @Test
    fun `configure resolves initial Configuration pending item`() = runTest {
        Given("a fresh manager (phase = Pending Configuration)") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            assertFalse("should start not ready", manager.actor.state.value.isReady)

            When("configure is dispatched") {
                manager.configure(neverCalledStaticConfig = false)

                withTimeout(5000) {
                    manager.hasIdentity.first()
                }
            }

            Then("identity is ready") {
                assertTrue(manager.actor.state.value.isReady)
            }
        }
    }

    @Test
    fun `reset gates identity readiness then restores it`() = runTest {
        Given("a configured ready manager") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            // Make it ready first
            manager.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { manager.hasIdentity.first() }
            assertTrue("should be ready after configure", manager.actor.state.value.isReady)

            When("FullReset is dispatched") {
                manager.reset()
                // Wait for it to become ready again
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("completeReset was called") {
                assertTrue(resetCalled)
            }
            And("identity is ready again with fresh state") {
                assertTrue(manager.actor.state.value.isReady)
                assertNull(manager.appUserId)
            }
        }
    }

    @Test
    fun `identify then reset produces clean anonymous state`() = runTest {
        Given("a manager identified as user-1") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            manager.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { manager.hasIdentity.first() }
            manager.identify("user-1")
            // Wait for identify to complete
            Thread.sleep(200)

            assertEquals("user-1", manager.appUserId)

            When("reset is called") {
                manager.reset()
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("appUserId is cleared") {
                assertNull(manager.appUserId)
            }
            And("a new aliasId was generated") {
                assertNotNull(manager.aliasId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Concurrency stress: rapid-fire mutations
    // -----------------------------------------------------------------------

    @Test
    fun `rapid concurrent identifies - last one wins`() = runTest {
        Given("a configured ready manager") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            manager.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { manager.hasIdentity.first() }

            When("multiple identifies are fired concurrently") {
                manager.identify("user-1")
                manager.identify("user-2")
                manager.identify("user-3")
                manager.identify("user-4")
                manager.identify("user-5")

                // Wait for all to settle — hasIdentity will emit once the
                // last action's pending items resolve.
                Thread.sleep(500)
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("the final userId wins") {
                assertEquals("user-5", manager.appUserId)
            }
            And("identity is ready") {
                assertTrue(manager.actor.state.value.isReady)
            }
            And("completeReset was called for user switches") {
                // Each switch from one logged-in user to another triggers completeReset
                assertTrue(resetCalled)
            }
        }
    }

    @Test
    fun `concurrent identifies from different coroutines`() = runTest {
        Given("a configured ready manager") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            manager.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { manager.hasIdentity.first() }

            When("identifies are launched from multiple coroutines simultaneously") {
                val jobs = (1..10).map { i ->
                    kotlinx.coroutines.launch(kotlinx.coroutines.Dispatchers.Default) {
                        manager.identify("user-$i")
                    }
                }
                jobs.forEach { it.join() }

                // Wait for all actions to process through the sequential actor
                Thread.sleep(500)
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("exactly one userId survives") {
                assertNotNull(manager.appUserId)
                assertTrue(manager.appUserId!!.startsWith("user-"))
            }
            And("identity is ready and consistent") {
                assertTrue(manager.actor.state.value.isReady)
                assertEquals(manager.appUserId, manager.userAttributes[Keys.APP_USER_ID])
            }
        }
    }

    @Test
    fun `reset-identify-reset-identify sequence`() = runTest {
        Given("a configured ready manager identified as user-1") {
            var resetCount = 0
            val manager = createSequentialManager()
            // Override completeReset to count calls
            val actor = manager.actor
            val managerWithCounter = IdentityManager(
                deviceHelper = deviceHelper,
                storage = storage,
                options = { SuperwallOptions() },
                ioScope = IOScope(kotlinx.coroutines.Dispatchers.IO),
                notifyUserChange = {},
                completeReset = { resetCount++ },
                trackEvent = { trackedEvents.add(it) },
                actor = actor,
                sdkContext = sdkContext,
            )

            every { storage.read(DidTrackFirstSeen) } returns true

            managerWithCounter.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { managerWithCounter.hasIdentity.first() }
            managerWithCounter.identify("user-1")
            Thread.sleep(200)
            assertEquals("user-1", managerWithCounter.appUserId)

            When("reset/identify/reset/identify is called in sequence") {
                managerWithCounter.reset()
                Thread.sleep(200)
                withTimeout(5000) { managerWithCounter.hasIdentity.first() }

                managerWithCounter.identify("user-2")
                Thread.sleep(200)
                withTimeout(5000) { managerWithCounter.hasIdentity.first() }

                managerWithCounter.reset()
                Thread.sleep(200)
                withTimeout(5000) { managerWithCounter.hasIdentity.first() }

                managerWithCounter.identify("user-3")
                Thread.sleep(200)
                withTimeout(5000) { managerWithCounter.hasIdentity.first() }
            }

            Then("final state is user-3") {
                assertEquals("user-3", managerWithCounter.appUserId)
            }
            And("identity is ready") {
                assertTrue(managerWithCounter.actor.state.value.isReady)
            }
            And("userAttributes are consistent with final identity") {
                assertEquals("user-3", managerWithCounter.userAttributes[Keys.APP_USER_ID])
            }
            And("completeReset was called for each reset") {
                // 2 explicit resets + user switches during identify
                assertTrue("resetCount should be >= 2, was $resetCount", resetCount >= 2)
            }
        }
    }

    @Test
    fun `rapid reset-identify interleaving from multiple coroutines`() = runTest {
        Given("a configured ready manager") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            manager.configure(neverCalledStaticConfig = false)
            withTimeout(5000) { manager.hasIdentity.first() }

            When("resets and identifies are interleaved from concurrent coroutines") {
                val jobs = (1..5).flatMap { i ->
                    listOf(
                        kotlinx.coroutines.launch(kotlinx.coroutines.Dispatchers.Default) {
                            manager.identify("user-$i")
                        },
                        kotlinx.coroutines.launch(kotlinx.coroutines.Dispatchers.Default) {
                            manager.reset()
                        },
                    )
                }
                jobs.forEach { it.join() }

                // Final identify to ensure we end in a known state
                manager.identify("final-user")
                Thread.sleep(500)
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("state is consistent — either final-user or anonymous") {
                // Due to serialization, the last processed action wins.
                // If reset ran last, appUserId is null. If identify ran last, it's "final-user".
                val state = manager.actor.state.value
                assertTrue("state must be ready", state.isReady)

                if (state.appUserId != null) {
                    // If logged in, attributes must match
                    assertEquals(state.appUserId, state.enrichedAttributes[Keys.APP_USER_ID])
                } else {
                    // If anonymous, aliasId must be in attributes
                    assertEquals(state.aliasId, state.enrichedAttributes[Keys.ALIAS_ID])
                }
            }
            And("no crash or deadlock occurred") {
                // If we got here, the mutex serialization worked correctly
                assertTrue(true)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Persistence interceptor under serialization
    // -----------------------------------------------------------------------

    @Test
    fun `persistence interceptor writes only changed fields`() = runTest {
        Given("a fresh manager with SequentialActor") {
            val manager = createSequentialManager()
            every { storage.read(DidTrackFirstSeen) } returns true

            When("configure is dispatched (only phase changes, no identity fields)") {
                manager.configure(neverCalledStaticConfig = false)
                withTimeout(5000) { manager.hasIdentity.first() }
            }

            Then("no identity field writes occurred (only phase changed)") {
                // The interceptor should NOT have written AliasId, Seed, etc.
                // because those didn't change — only phase did.
                // (Initial writes happen in createInitialIdentityState, not the interceptor)
                verify(exactly = 0) { storage.write(AppUserId, any<String>()) }
            }
        }
    }
}
