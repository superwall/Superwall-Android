package com.superwall.sdk.config.options

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.primitives.SequentialActor
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdConsentPublisherTest {
    private val configAttributes: Trackable = mockk()

    private fun publisher(
        scope: CoroutineScope,
        track: suspend (Trackable) -> Unit,
        makeDeviceAttributes: suspend () -> HashMap<String, Any>,
        currentAdConsent: ReportedAdConsent = ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEFAULT),
        eventTrackingBehavior: EventTrackingBehavior = EventTrackingBehavior.ALL,
        updateEventsQueue: suspend (EventTrackingBehavior) -> Unit = {},
    ) = AdConsentPublisher(
        scope = scope,
        tracker = track,
        makeDeviceAttributes = makeDeviceAttributes,
        makeConfigAttributes = { configAttributes },
        currentAdConsent = { currentAdConsent },
        eventTrackingBehavior = { eventTrackingBehavior },
        updateEventsQueue = updateEventsQueue,
        actor = SequentialActor(AdConsentState(), scope),
    )

    @Test
    fun `publish sends device attributes then config attributes`() =
        runTest {
            Given("a publisher reporting denied personalization") {
                val tracked = mutableListOf<Trackable>()
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adPersonalizationConsent" to "denied") },
                    )

                When("it publishes") {
                    publisher.publish()
                    runCurrent()

                    Then("both events are tracked with the current consent") {
                        assertEquals(2, tracked.size)
                        val device = tracked[0] as InternalSuperwallEvent.DeviceAttributes
                        assertEquals("denied", device.deviceAttributes["adPersonalizationConsent"])
                        assertTrue(tracked[1] === configAttributes)
                    }
                }
            }
        }

    @Test
    fun `a publish superseded while waiting is dropped`() =
        runTest {
            Given("a first publish blocked inside its send") {
                val tracked = mutableListOf<Trackable>()
                val release = CompletableDeferred<Unit>()
                var consent = "granted"
                var calls = 0
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = {
                            calls += 1
                            if (calls == 1) release.await()
                            hashMapOf("adPersonalizationConsent" to consent)
                        },
                    )
                publisher.publish()
                runCurrent()

                When("two more assignments arrive before it finishes") {
                    consent = "denied"
                    publisher.publish()
                    publisher.publish()
                    release.complete(Unit)
                    runCurrent()

                    Then("only the first (already sending) and the latest are tracked, latest last") {
                        val devices = tracked.filterIsInstance<InternalSuperwallEvent.DeviceAttributes>()
                        assertEquals(2, devices.size)
                        assertEquals("denied", devices.last().deviceAttributes["adPersonalizationConsent"])
                        assertTrue(tracked.last() === configAttributes)
                    }
                }
            }
        }

    @Test
    fun `a tracking change resends consent only once the events queue has changed`() =
        runTest {
            Given("an events queue that takes a while to apply the change") {
                val tracked = mutableListOf<Trackable>()
                val queueUpdated = CompletableDeferred<Unit>()
                var appliedBehavior: EventTrackingBehavior? = null
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adUserDataConsent" to "denied") },
                        updateEventsQueue = {
                            queueUpdated.await()
                            appliedBehavior = it
                        },
                    )

                When("tracking is turned back on") {
                    publisher.trackingBehaviorChanged(EventTrackingBehavior.NONE, EventTrackingBehavior.ALL)
                    runCurrent()
                    val trackedBeforeChange = tracked.size
                    queueUpdated.complete(Unit)
                    runCurrent()

                    Then("device and config attributes follow the queue change") {
                        assertEquals(0, trackedBeforeChange)
                        assertEquals(EventTrackingBehavior.ALL, appliedBehavior)
                        assertTrue(tracked[0] is InternalSuperwallEvent.DeviceAttributes)
                        assertTrue(tracked[1] === configAttributes)
                    }
                }
            }
        }

    @Test
    fun `opting out sends nothing and an unchanged ALL sends only config attributes`() =
        runTest {
            Given("a publisher") {
                val tracked = mutableListOf<Trackable>()
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adUserDataConsent" to "granted") },
                    )

                When("tracking is turned off") {
                    publisher.trackingBehaviorChanged(EventTrackingBehavior.ALL, EventTrackingBehavior.NONE)
                    runCurrent()

                    Then("nothing is sent") {
                        assertTrue(tracked.isEmpty())
                    }
                }
                When("ALL is set again while already ALL") {
                    publisher.trackingBehaviorChanged(EventTrackingBehavior.ALL, EventTrackingBehavior.ALL)
                    runCurrent()

                    Then("only the config attributes are sent") {
                        assertEquals(listOf(configAttributes), tracked)
                    }
                }
            }
        }

    @Test
    fun `a device attributes send carrying stale consent is followed by a resend`() =
        runTest {
            Given("current consent with personalization denied") {
                val tracked = mutableListOf<Trackable>()
                val current = ReportedAdConsent(AdConsent.Custom(adPersonalization = AdConsentStatus.Denied), AdConsentSource.TCF)
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { HashMap(current.toAttributes()) },
                        currentAdConsent = current,
                    )

                When("a send carrying the older granted consent is reconciled") {
                    publisher.reconcileAfterSend(
                        hashMapOf(
                            "adUserDataConsent" to "granted",
                            "adPersonalizationConsent" to "granted",
                            "adConsentSource" to "default",
                        ),
                    )
                    runCurrent()

                    Then("the current consent is sent again") {
                        val device = tracked.filterIsInstance<InternalSuperwallEvent.DeviceAttributes>().single()
                        assertEquals("denied", device.deviceAttributes["adPersonalizationConsent"])
                    }
                }
            }
        }

    @Test
    fun `consent is republished on foreground only when it changed since the last send`() =
        runTest {
            Given("a publisher whose current consent is granted") {
                val tracked = mutableListOf<Trackable>()
                val current = ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEFAULT)
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { HashMap(current.toAttributes()) },
                        currentAdConsent = current,
                    )

                When("nothing was sent yet, then the same consent, then an older one") {
                    publisher.republishIfChanged()
                    runCurrent()
                    val beforeFirstSend = tracked.size

                    publisher.reconcileAfterSend(HashMap(current.toAttributes()))
                    publisher.republishIfChanged()
                    runCurrent()
                    val afterSameSend = tracked.size

                    publisher.reconcileAfterSend(
                        hashMapOf("adUserDataConsent" to "denied", "adPersonalizationConsent" to "denied", "adConsentSource" to "tcf"),
                    )
                    runCurrent()

                    Then("only the change is republished") {
                        assertEquals(0, beforeFirstSend)
                        assertEquals(0, afterSameSend)
                        assertEquals(2, tracked.size)
                    }
                }
            }
        }
}
