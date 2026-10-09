package com.superwall.sdk.config.options

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdConsentPublisherTest {
    private val configAttributes: Trackable = mockk()

    @Test
    fun `publish sends device attributes then config attributes`() =
        runTest {
            Given("a publisher reporting denied personalization") {
                val tracked = mutableListOf<Trackable>()
                val publisher =
                    AdConsentPublisher(
                        scope = this@runTest,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adPersonalizationConsent" to "denied") },
                        makeConfigAttributes = { configAttributes },
                    )

                When("it publishes") {
                    publisher.publish().join()

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
                    AdConsentPublisher(
                        scope = this@runTest,
                        track = { tracked += it },
                        makeDeviceAttributes = {
                            calls += 1
                            if (calls == 1) release.await()
                            hashMapOf("adPersonalizationConsent" to consent)
                        },
                        makeConfigAttributes = { configAttributes },
                    )
                val first = publisher.publish()
                testScheduler.runCurrent()

                When("two more assignments arrive before it finishes") {
                    consent = "denied"
                    val second = publisher.publish()
                    val third = publisher.publish()
                    release.complete(Unit)
                    first.join()
                    second.join()
                    third.join()

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
                    AdConsentPublisher(
                        scope = this@runTest,
                        track = { tracked += it },
                        makeDeviceAttributes = { hashMapOf("adUserDataConsent" to "denied") },
                        makeConfigAttributes = { configAttributes },
                    )

                When("it publishes after that change") {
                    val sent = publisher.publish(after = queueUpdated)
                    testScheduler.runCurrent()
                    val trackedBeforeChange = tracked.size
                    queueUpdated.complete()
                    sent.join()

                    Then("nothing is tracked until the queue has changed") {
                        assertEquals(0, trackedBeforeChange)
                        assertEquals(2, tracked.size)
                    }
                }
            }
        }
}
