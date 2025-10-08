package com.superwall.sdk.analytics.internal

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PaywallInfo.Companion.empty
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.store.transactions.TransactionError
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.LocalDateTime
import org.threeten.bp.Month
import java.net.URI

class TrackingLogicTest {
    @Test
    fun processParameters_filtersUnsupportedValuesAndPrefixesKeys() =
        runBlocking {
            val now = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0)
            val paywallUrl = PaywallInfo.empty().url
            val trackable =
                TrackableTestUtils.fakeTrackable(
                    rawName = "event_a",
                    audienceParams =
                        mapOf(
                            "custom_int" to 3,
                            "custom_list" to listOf("ignore"),
                            "custom_json" to JsonPrimitive("json"),
                        ),
                    superwallParams =
                        mapOf(
                            "string_value" to "value",
                            "list_value" to listOf("skip"),
                            "map_value" to mapOf("nested" to "ok"),
                            "json_value" to Json.parseToJsonElement("{\"foo\":1}"),
                            "date_value" to now,
                            "uri_value" to URI("https://example.com"),
                            "paywall_url" to paywallUrl,
                        ),
                    canImplicitlyTrigger = false,
                )

            val parameters = TrackingLogic.processParameters(trackable, appSessionId = "session-123")

            assertEquals("value", parameters.delegateParams["string_value"])
            assertFalse(parameters.delegateParams.containsKey("list_value"))
            assertEquals(mapOf("nested" to "ok"), parameters.delegateParams["map_value"])
            assertEquals("json", parameters.delegateParams["custom_json"])
            assertTrue(parameters.audienceFilterParams.containsKey("\$string_value"))
            assertEquals("event_a", parameters.audienceFilterParams["event_name"])
            assertEquals("session-123", parameters.delegateParams["app_session_id"])
            assertEquals(false, parameters.audienceFilterParams["\$is_standard_event"])
            assertEquals(
                now.atZone(org.threeten.bp.ZoneOffset.UTC).toInstant().toEpochMilli(),
                parameters.delegateParams["date_value"],
            )
            assertEquals("https://example.com", parameters.delegateParams["uri_value"])
        }

    @Test
    fun isNotDisabledVerboseEvent_handlesVariousEventTypes() {
        val paywallInfo = PaywallInfo.empty()
        val presentationRequest =
            InternalSuperwallEvent.PresentationRequest(
                eventData = EventData.stub(),
                type = com.superwall.sdk.paywall.presentation.internal.PresentationRequestType.Presentation,
                status = com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus.Presentation,
                statusReason = null,
                factory = TrackingLogicTestFactory,
            )

        assertFalse(
            TrackingLogic.isNotDisabledVerboseEvent(
                presentationRequest,
                disableVerboseEvents = true,
                isSandbox = false,
            ),
        )

        assertTrue(
            TrackingLogic.isNotDisabledVerboseEvent(
                presentationRequest,
                disableVerboseEvents = true,
                isSandbox = true,
            ),
        )

        val startEvent =
            InternalSuperwallEvent.PaywallLoad(
                state = InternalSuperwallEvent.PaywallLoad.State.Start(),
                eventData = EventData.stub(),
            )

        assertFalse(
            TrackingLogic.isNotDisabledVerboseEvent(
                startEvent,
                disableVerboseEvents = true,
                isSandbox = false,
            ),
        )

        val failEvent =
            InternalSuperwallEvent.PaywallLoad(
                state = InternalSuperwallEvent.PaywallLoad.State.Fail(),
                eventData = EventData.stub(),
            )
        assertTrue(
            TrackingLogic.isNotDisabledVerboseEvent(
                failEvent,
                disableVerboseEvents = true,
                isSandbox = false,
            ),
        )

        val shimmer =
            InternalSuperwallEvent.ShimmerLoad(
                state = InternalSuperwallEvent.ShimmerLoad.State.Started,
                paywallId = "pw",
                visibleDuration = null,
                delay = 0.0,
                preloadingEnabled = false,
            )
        assertFalse(
            TrackingLogic.isNotDisabledVerboseEvent(
                shimmer,
                disableVerboseEvents = true,
                isSandbox = false,
            ),
        )
    }

    @Test
    fun checkNotSuperwallEvent_throwsForReservedNames() {
        assertThrows(Exception::class.java) {
            TrackingLogic.checkNotSuperwallEvent(SuperwallEvent.AppOpen().rawName)
        }

        TrackingLogic.checkNotSuperwallEvent("my_custom_event")
    }

    @Test
    fun canTriggerPaywall_handlesDifferentOutcomes() {
        val triggers = setOf("event_a")

        val deepLinkEvent = InternalSuperwallEvent.DeepLink(uri = URI("https://example.com"))
        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.DeepLinkTrigger,
            TrackingLogic.canTriggerPaywall(deepLinkEvent, triggers, null),
        )

        val nonTriggerEvent =
            TrackableTestUtils.fakeTrackable(rawName = "other", superwallParams = emptyMap())
        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall,
            TrackingLogic.canTriggerPaywall(nonTriggerEvent, triggers, null),
        )

        val paywallInfo =
            PaywallInfo.empty().copy(
                presentedByEventWithName =
                    SuperwallEvent
                        .TransactionFail(
                            TransactionError.Pending(""),
                            PaywallInfo.empty(),
                        ).rawName,
            )
        val paywallView =
            mockk<PaywallView> {
                every { info } returns paywallInfo
            }
        val triggerEvent =
            TrackableTestUtils.fakeTrackable(
                rawName = "event_a",
                superwallParams = emptyMap(),
                canImplicitlyTrigger = false,
            )
        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall,
            TrackingLogic.canTriggerPaywall(triggerEvent, triggers, paywallView),
        )

        val trackableSuperwallEvent =
            object : TrackableSuperwallEvent {
                override val superwallPlacement: SuperwallEvent =
                    SuperwallEvent.PaywallDecline(paywallInfo = paywallInfo)
                override val rawName: String = superwallPlacement.rawName
                override val audienceFilterParams: Map<String, Any> = emptyMap()
                override val canImplicitlyTriggerPaywall: Boolean = true

                override suspend fun getSuperwallParameters(): Map<String, Any> = emptyMap()
            }
        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall,
            TrackingLogic.canTriggerPaywall(trackableSuperwallEvent, triggers, null),
        )

        val allowedReferrerInfo = paywallInfo.copy(presentedByEventWithName = null)
        val openPaywallView =
            mockk<PaywallView> {
                every { info } returns allowedReferrerInfo
            }
        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall,
            TrackingLogic.canTriggerPaywall(triggerEvent, triggers, openPaywallView),
        )

        assertEquals(
            TrackingLogic.ImplicitTriggerOutcome.TriggerPaywall,
            TrackingLogic.canTriggerPaywall(triggerEvent, triggers, null),
        )
    }
}

private object TrackingLogicTestFactory :
    InternalSuperwallEvent.PresentationRequest.Factory,
    com.superwall.sdk.dependencies.RuleAttributesFactory,
    com.superwall.sdk.dependencies.FeatureFlagsFactory,
    com.superwall.sdk.dependencies.ComputedPropertyRequestsFactory {
    override suspend fun makeRuleAttributes(
        event: EventData?,
        computedPropertyRequests: List<com.superwall.sdk.models.config.ComputedPropertyRequest>,
    ): Map<String, Any> = emptyMap()

    override fun makeFeatureFlags(): com.superwall.sdk.models.config.FeatureFlags? = null

    override fun makeComputedPropertyRequests(): List<com.superwall.sdk.models.config.ComputedPropertyRequest> = emptyList()
}

private object TrackableTestUtils {
    fun fakeTrackable(
        rawName: String,
        audienceParams: Map<String, Any> = emptyMap(),
        superwallParams: Map<String, Any>,
        canImplicitlyTrigger: Boolean = true,
    ): Trackable =
        object : Trackable {
            override val rawName: String = rawName
            override val audienceFilterParams: Map<String, Any> = audienceParams
            override val canImplicitlyTriggerPaywall: Boolean = canImplicitlyTrigger

            override suspend fun getSuperwallParameters(): Map<String, Any> = superwallParams
        }
}
