package com.superwall.sdk.dependencies

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackThroughOwnerTest {
    @Test
    fun `a container without an owner logs the dropped event`() =
        runBlocking {
            Given("a container that has no Superwall owner") {
                val logged = mutableListOf<String>()
                val event = InternalSuperwallEvent.AppLaunch()

                When("an event is tracked") {
                    val result = runBlocking { trackThroughOwner(event, track = null, logDropped = { logged += it }) }

                    Then("it fails and the drop is logged with the event name") {
                        assertTrue(result.isFailure)
                        assertEquals(1, logged.size)
                        assertTrue(logged.single().contains(event.rawName))
                    }
                }
            }
        }

    @Test
    fun `a container with an owner tracks through it and logs nothing`() =
        runBlocking {
            Given("a container whose owner tracks events") {
                val logged = mutableListOf<String>()
                val tracked = mutableListOf<Any>()
                val expected = mockk<TrackingResult>()
                val event = InternalSuperwallEvent.AppLaunch()

                When("an event is tracked") {
                    val result =
                        runBlocking {
                            trackThroughOwner(
                                event,
                                track = {
                                    tracked += it
                                    Result.success(expected)
                                },
                                logDropped = { logged += it },
                            )
                        }

                    Then("the owner receives it and nothing is logged") {
                        assertEquals(listOf<Any>(event), tracked)
                        assertEquals(expected, result.getOrNull())
                        assertTrue(logged.isEmpty())
                    }
                }
            }
        }
}
