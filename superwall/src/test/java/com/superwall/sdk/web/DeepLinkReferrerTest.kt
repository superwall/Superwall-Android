package com.superwall.sdk.web

import android.content.Context
import android.os.DeadObjectException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.IOScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkReferrerTest {
    private val client = mockk<InstallReferrerClient>(relaxed = true)
    private val listener = slot<InstallReferrerStateListener>()

    @Before
    fun setUp() {
        mockkStatic(InstallReferrerClient::class)
        every { InstallReferrerClient.newBuilder(any()) } returns
            mockk<InstallReferrerClient.Builder> { every { build() } returns client }
        every { client.startConnection(capture(listener)) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(InstallReferrerClient::class)
    }

    private fun referrer() = DeepLinkReferrer({ mockk<Context>(relaxed = true) }, IOScope())

    @Test
    fun `a dead Play service when setup finishes does not crash the callback`() =
        Given("a connected client whose referrer read throws DeadObjectException") {
            every { client.isReady } returns true
            every { client.installReferrer } throws DeadObjectException()
            val referrer = referrer()

            When("Play reports that setup finished") {
                listener.captured.onInstallReferrerSetupFinished(InstallReferrerResponse.OK)
            }

            Then("the callback does not read the referrer") {
                verify(exactly = 0) { client.installReferrer }
            }
            Then("a later referral check fails instead of throwing") {
                assertTrue(runBlocking { referrer.checkForReferral() }.isFailure)
            }
        }

    @Test
    fun `a failure while closing the client does not escape the callback`() =
        Given("a client that throws when its connection is ended") {
            every { client.endConnection() } throws IllegalStateException("not bound")
            val referrer = referrer()

            When("Play reports that the feature is not supported") {
                listener.captured.onInstallReferrerSetupFinished(InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
            }

            Then("the client is dropped and referral checks fail fast") {
                verify(exactly = 1) { client.endConnection() }
                assertTrue(runBlocking { referrer.checkForReferral() }.isFailure)
            }
        }

    @Test
    fun `the referrer is still read and cached after setup finishes`() =
        Given("a connected client with a referrer that carries a code") {
            every { client.isReady } returns true
            every { client.installReferrer } returns
                mockk<ReferrerDetails> { every { installReferrer } returns "code=abc123&sw_mmp_click_id=42" }
            val referrer = referrer()
            listener.captured.onInstallReferrerSetupFinished(InstallReferrerResponse.OK)

            Then("both readers get their value from one read") {
                assertEquals("abc123", runBlocking { referrer.checkForReferral() }.getOrNull())
                assertEquals(42L, runBlocking { referrer.checkForMmpClickId() }.getOrNull())
                verify(exactly = 1) { client.installReferrer }
            }
        }
}
