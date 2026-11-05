package com.superwall.sdk.dependencies

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperwallGraphTest {
    private val attached = mutableListOf<DependencyContainer>()

    private fun attachNew(): DependencyContainer =
        mockk<DependencyContainer>(relaxed = true).also {
            attached += it
            SuperwallGraph.attach(it)
        }

    @After
    fun tearDown() {
        attached.forEach { SuperwallGraph.detach(it) }
    }

    @Test
    fun `container is null before anything is attached`() {
        Given("no container is attached") {
            Then("the graph has no container and requireContainer throws") {
                assertNull(SuperwallGraph.container)
                assertTrue(runCatching { SuperwallGraph.requireContainer() }.isFailure)
            }
        }
    }

    @Test
    fun `attach exposes the container and detach clears it`() {
        Given("an attached container") {
            val container = attachNew()
            Then("the graph returns it") {
                assertSame(container, SuperwallGraph.container)
                assertSame(container, SuperwallGraph.requireContainer())
            }
            When("it is detached") {
                SuperwallGraph.detach(container)
                Then("the graph is empty again") {
                    assertNull(SuperwallGraph.container)
                }
            }
        }
    }

    @Test
    fun `detaching a stale container keeps the newer one`() {
        Given("a container replaced by a newer one") {
            val old = attachNew()
            val current = attachNew()
            When("the old one is detached late, as a slow teardown would") {
                SuperwallGraph.detach(old)
                Then("the newer container stays attached") {
                    assertSame(current, SuperwallGraph.container)
                }
            }
        }
    }

    @Test
    fun `track goes through the attached container`() {
        Given("an attached container") {
            val container = attachNew()
            val event = InternalSuperwallEvent.FirstSeen()
            coEvery { container.trackEvent(event) } returns Result.success(mockk<TrackingResult>())
            When("an event is tracked through the graph") {
                val result = runBlocking { SuperwallGraph.track(event) }
                Then("the container tracks it") {
                    assertTrue(result.isSuccess)
                    coVerify(exactly = 1) { container.trackEvent(event) }
                }
            }
        }
    }

    @Test
    fun `track fails when the SDK is not configured`() {
        Given("no container is attached") {
            When("an event is tracked through the graph") {
                val result = runBlocking { SuperwallGraph.track(InternalSuperwallEvent.FirstSeen()) }
                Then("tracking fails instead of throwing") {
                    assertTrue(result.isFailure)
                }
            }
        }
    }
}
