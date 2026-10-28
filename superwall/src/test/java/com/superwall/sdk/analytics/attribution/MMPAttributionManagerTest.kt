package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.models.config.AttributionOptions
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.MmpAttributionOptions
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.DidCompleteMMPInstallAttributionRequest
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.MMPAcquisitionData
import com.superwall.sdk.storage.Storable
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS's `MMPAttributionManager` behaviour: cache the resolved `acquisition_*`
 * payload, merge it into user attributes, track the outcome, expose the cache for resets
 * without re-hitting the backend, and only match once config turns the MMP on.
 */
class MMPAttributionManagerTest {
    private val stored = mutableMapOf<String, Any?>()

    private fun storage(): LocalStorage {
        val storage = mockk<LocalStorage>(relaxed = true)
        every { storage.write(any<Storable<Any>>(), any()) } answers {
            stored[firstArg<Storable<Any>>().key] = secondArg()
        }
        every { storage.read(any<Storable<Any>>()) } answers {
            stored[firstArg<Storable<Any>>().key]
        }
        return storage
    }

    private fun matched(
        acquisitionAttributes: Map<String, JsonPrimitive>? =
            mapOf(
                "acquisition_source" to JsonPrimitive("tiktok"),
                "acquisition_campaign" to JsonPrimitive("spring_sale"),
            ),
    ) = MmpMatchResponse(
        matched = true,
        confidence = AttributionMatchInfo.Confidence.HIGH,
        matchScore = 0.92,
        network = "tiktok_ads",
        acquisitionAttributes = acquisitionAttributes,
        breakdown = mapOf("reason" to JsonPrimitive("ip_and_fingerprint")),
    )

    private fun configWithMmp(enabled: Boolean) =
        ConfigState.Retrieved(
            Config.stub().copy(attributionOptions = AttributionOptions(mmp = MmpAttributionOptions(enabled))),
        )

    private fun manager(
        scope: CoroutineScope,
        configState: MutableStateFlow<ConfigState> = MutableStateFlow(configWithMmp(enabled = true)),
        tracker: suspend (TrackableSuperwallEvent) -> Unit = {},
        userAttributes: Map<String, Any> = emptyMap(),
        setUserAttributes: (Map<String, Any?>) -> Unit = {},
        installReferrerClickId: Long? = null,
        eventTrackingBehavior: () -> EventTrackingBehavior = { EventTrackingBehavior.ALL },
        sendMatchRequest: suspend (Long?) -> Either<MmpMatchResponse, NetworkError> = {
            Either.Failure(NetworkError.Timeout)
        },
    ) = MMPAttributionManager(
        storage = storage(),
        tracker = tracker,
        userAttributes = { userAttributes },
        setUserAttributes = setUserAttributes,
        sendMatchRequest = sendMatchRequest,
        readInstallReferrerClickId = { installReferrerClickId },
        eventTrackingBehavior = eventTrackingBehavior,
        configState = configState,
        scope = scope,
        actor = SequentialActor(MMPAttributionState(), scope),
    )

    @Test
    fun `a successful match caches the payload, merges attributes and tracks the outcome`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()
            val applied = mutableListOf<Map<String, Any?>>()
            var sentClickId: Long? = null
            val manager =
                manager(
                    backgroundScope,
                    tracker = { tracked += it },
                    setUserAttributes = { applied += it },
                    installReferrerClickId = 42L,
                    sendMatchRequest = {
                        sentClickId = it
                        Either.Success(matched())
                    },
                )

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertEquals(42L, sentClickId)
            assertEquals("tiktok", applied.single()["acquisition_source"])
            assertEquals("spring_sale", applied.single()["acquisition_campaign"])
            assertEquals(2, (stored[MMPAcquisitionData.key] as Map<*, *>).size)
            assertEquals(true, stored[DidCompleteMMPInstallAttributionRequest.key])
            assertEquals(MMPAttributionState.Match.Finished, manager.state.value.match)

            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertTrue(event.info.matched)
            assertEquals(AttributionMatchInfo.Provider.MMP, event.info.provider)
            assertEquals("tiktok", event.info.source)
            assertEquals(AttributionMatchInfo.Confidence.HIGH, event.info.confidence)
            assertEquals(0.92, event.info.matchScore!!, 0.0001)
            assertEquals("ip_and_fingerprint", event.info.reason)
        }

    @Test
    fun `source falls back to the network name when no acquisition_source is present`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()
            val manager =
                manager(
                    backgroundScope,
                    tracker = { tracked += it },
                    sendMatchRequest = {
                        Either.Success(
                            matched(acquisitionAttributes = mapOf("acquisition_campaign" to JsonPrimitive("x"))),
                        )
                    },
                )

            manager.matchInstallOnceEnabled()
            runCurrent()

            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertEquals("tiktok_ads", event.info.source)
        }

    @Test
    fun `an unmatched response still counts as a completed request`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()
            val manager =
                manager(
                    backgroundScope,
                    tracker = { tracked += it },
                    sendMatchRequest = { Either.Success(MmpMatchResponse(matched = false)) },
                )

            manager.matchInstallOnceEnabled()
            runCurrent()

            // A processed request that found nothing must not be retried on next launch.
            assertEquals(true, stored[DidCompleteMMPInstallAttributionRequest.key])
            assertFalse((tracked.single() as InternalSuperwallEvent.AttributionMatch).info.matched)
            assertNull(stored[MMPAcquisitionData.key])
        }

    @Test
    fun `a failed request tracks request_failed and is retried next launch`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()
            val manager = manager(backgroundScope, tracker = { tracked += it })

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertNull(stored[DidCompleteMMPInstallAttributionRequest.key])
            assertEquals(MMPAttributionState.Match.Finished, manager.state.value.match)
            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertFalse(event.info.matched)
            assertEquals("request_failed", event.info.reason)
        }

    @Test
    fun `a completed request isn't sent again`() =
        runTest {
            var requests = 0
            val manager =
                manager(backgroundScope, sendMatchRequest = {
                    requests += 1
                    Either.Success(matched())
                })
            stored[DidCompleteMMPInstallAttributionRequest.key] = true

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertEquals(0, requests)
            assertFalse(manager.state.value.isMatchPending)
        }

    @Test
    fun `a match that throws still finishes so paywalls stop waiting`() =
        runTest {
            val manager =
                manager(backgroundScope, sendMatchRequest = { error("failed to build request") })

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertEquals(MMPAttributionState.Match.Finished, manager.state.value.match)
            assertFalse(manager.state.value.isMatchPending)
            assertNull(stored[DidCompleteMMPInstallAttributionRequest.key])
        }

    @Test
    fun `attributes already on the user are not re-applied`() =
        runTest {
            val applied = mutableListOf<Map<String, Any?>>()
            val manager =
                manager(
                    backgroundScope,
                    userAttributes = mapOf("acquisition_source" to "tiktok", "acquisition_campaign" to "spring_sale"),
                    setUserAttributes = { applied += it },
                    sendMatchRequest = { Either.Success(matched()) },
                )

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertTrue(applied.isEmpty())
        }

    @Test
    fun `cached attributes restore the payload to a new user without re-matching`() =
        runTest {
            var requests = 0
            val manager =
                manager(backgroundScope, sendMatchRequest = {
                    requests += 1
                    Either.Success(matched())
                })
            manager.matchInstallOnceEnabled()
            runCurrent()

            // After `reset()` the user's attributes are gone, but the install-scoped cache isn't.
            val cached = manager(backgroundScope).cachedAcquisitionAttributes()

            assertEquals(1, requests)
            assertEquals("tiktok", cached["acquisition_source"])
        }

    @Test
    fun `cached attributes are empty when no match ever resolved`() =
        runTest {
            assertTrue(manager(backgroundScope).cachedAcquisitionAttributes().isEmpty())
        }

    @Test
    fun `the match never starts while config has the MMP off`() =
        runTest {
            var requests = 0
            val manager =
                manager(
                    backgroundScope,
                    configState = MutableStateFlow(configWithMmp(enabled = false)),
                    sendMatchRequest = {
                        requests += 1
                        Either.Success(matched())
                    },
                )

            manager.matchInstallOnceEnabled()
            runCurrent()

            assertEquals(0, requests)
        }

    @Test
    fun `the match starts once when config turns the MMP on`() =
        runTest {
            val configState = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
            var requests = 0
            val manager =
                manager(backgroundScope, configState = configState, sendMatchRequest = {
                    requests += 1
                    Either.Success(matched())
                })

            manager.matchInstallOnceEnabled()
            runCurrent()
            assertEquals(0, requests)

            configState.value = configWithMmp(enabled = true)
            runCurrent()
            manager.startMatchIfEnabled()
            runCurrent()

            assertEquals(1, requests)
        }

    @Test
    fun `a match skipped while opted out starts when tracking is turned back on`() =
        runTest {
            var behavior = EventTrackingBehavior.NONE
            var requests = 0
            val manager =
                manager(backgroundScope, eventTrackingBehavior = { behavior }, sendMatchRequest = {
                    requests += 1
                    Either.Success(matched())
                })

            manager.matchInstallOnceEnabled()
            runCurrent()
            assertEquals(0, requests)
            assertEquals(MMPAttributionState.Match.Skipped, manager.state.value.match)

            behavior = EventTrackingBehavior.ALL
            manager.startMatchIfEnabled()
            manager.startMatchIfEnabled()
            runCurrent()

            assertEquals(1, requests)
        }

    private fun trigger(expression: String?) =
        Trigger(
            eventName = "campaign_trigger",
            rules = listOf(TriggerRule.stub().copy(expression = expression)),
        )

    private val acquisitionTrigger = trigger("user.acquisition_source == \"tiktok\"")

    @Test
    fun `audiences using acquisition attributes are detected in any expression`() {
        assertTrue(MMPAttributionManager.usesAcquisitionAttributes(acquisitionTrigger))
        assertTrue(
            MMPAttributionManager.usesAcquisitionAttributes(
                Trigger(
                    eventName = "campaign_trigger",
                    rules = listOf(TriggerRule.stub().copy(expressionCEL = "user.acquisition_campaign == 'x'")),
                ),
            ),
        )
        assertFalse(MMPAttributionManager.usesAcquisitionAttributes(trigger("user.plan == \"pro\"")))
        assertFalse(MMPAttributionManager.usesAcquisitionAttributes(trigger(null)))
    }

    @Test
    fun `a paywall using acquisition attributes waits for a running match`() =
        runTest {
            val response = CompletableDeferred<Either<MmpMatchResponse, NetworkError>>()
            val manager = manager(backgroundScope, sendMatchRequest = { response.await() })
            manager.matchInstallOnceEnabled()
            runCurrent()

            val wait = async { manager.waitForPendingMatch(acquisitionTrigger) }
            advanceTimeBy(500)
            assertFalse(wait.isCompleted)

            response.complete(Either.Success(matched()))
            runCurrent()
            assertTrue(wait.isCompleted)
            assertEquals(500L, currentTime)
        }

    @Test
    fun `a match marked pending before it's set up also holds the paywall`() =
        runTest {
            val manager = manager(backgroundScope, sendMatchRequest = { Either.Success(matched()) })
            manager.markMatchPending()

            val wait = async { manager.waitForPendingMatch(acquisitionTrigger) }
            runCurrent()
            assertFalse(wait.isCompleted)

            manager.matchInstallOnceEnabled()
            runCurrent()
            assertTrue(wait.isCompleted)
        }

    @Test
    fun `the wait gives up after the presentation timeout`() =
        runTest {
            val manager =
                manager(backgroundScope, sendMatchRequest = {
                    CompletableDeferred<Either<MmpMatchResponse, NetworkError>>().await()
                })
            manager.matchInstallOnceEnabled()
            runCurrent()

            manager.waitForPendingMatch(acquisitionTrigger)

            assertEquals(MMPAttributionManager.presentationWaitTimeout.inWholeMilliseconds, currentTime)
        }

    @Test
    fun `the wait returns straight away when it isn't needed`() =
        runTest {
            val neverResponds: suspend (Long?) -> Either<MmpMatchResponse, NetworkError> = {
                CompletableDeferred<Either<MmpMatchResponse, NetworkError>>().await()
            }

            // Audiences don't use acquisition attributes.
            val manager = manager(backgroundScope, sendMatchRequest = neverResponds)
            manager.matchInstallOnceEnabled()
            runCurrent()
            manager.waitForPendingMatch(trigger("user.plan == \"pro\""))
            manager.waitForPendingMatch(null)

            // The MMP is off.
            val offManager =
                manager(backgroundScope, configState = MutableStateFlow(configWithMmp(enabled = false)))
            offManager.markMatchPending()
            offManager.waitForPendingMatch(acquisitionTrigger)

            // No match pending, or the match was skipped because tracking is off.
            val idleManager =
                manager(
                    backgroundScope,
                    eventTrackingBehavior = { EventTrackingBehavior.NONE },
                    sendMatchRequest = neverResponds,
                )
            idleManager.waitForPendingMatch(acquisitionTrigger)
            idleManager.matchInstallOnceEnabled()
            runCurrent()
            idleManager.waitForPendingMatch(acquisitionTrigger)

            assertEquals(0L, currentTime)
        }

    @Test
    fun `attribution_match params are sent without a dollar prefix`() =
        runTest {
            val event =
                InternalSuperwallEvent.AttributionMatch(
                    AttributionMatchInfo(
                        provider = AttributionMatchInfo.Provider.MMP,
                        matched = true,
                        source = "tiktok",
                        confidence = AttributionMatchInfo.Confidence.HIGH,
                        matchScore = 0.92,
                        reason = "ip_and_fingerprint",
                    ),
                )

            val params = TrackingLogic.processParameters(event, "session").audienceFilterParams

            assertEquals("mmp", params["provider"])
            assertEquals(true, params["matched"])
            assertEquals("tiktok", params["source"])
            assertEquals("high", params["confidence"])
            assertEquals(0.92, params["match_score"])
            assertEquals("ip_and_fingerprint", params["reason"])
            assertNull(params["\$provider"])
        }
}
