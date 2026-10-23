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
import kotlinx.coroutines.Job
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
    ) = AdConsentPublisher(
        scope = scope,
        tracker = track,
        makeDeviceAttributes = makeDeviceAttributes,
        makeConfigAttributes = { configAttributes },
        currentAdConsent = { currentAdConsent },
        eventTrackingBehavior = { eventTrackingBehavior },
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
    fun `publish waits for the events-queue change it follows`() =
        runTest {
            Given("a queue change that hasn't run yet") {
                val tracked = mutableListOf<Trackable>()
                val queueUpdated = Job()
                val publisher =
                    publisher(
                        scope = backgroundScope,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adUserDataConsent" to "denied") },
                    )

                When("it publishes after that change") {
                    publisher.publish(after = queueUpdated)
                    runCurrent()
                    val trackedBeforeChange = tracked.size
                    queueUpdated.complete()
                    runCurrent()

                    Then("nothing is tracked until the queue has changed") {
                        assertEquals(0, trackedBeforeChange)
                        assertEquals(2, tracked.size)
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
