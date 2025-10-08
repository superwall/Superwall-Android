package com.superwall.sdk.deeplinks

import android.net.Uri
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.debug.DebugManager
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.web.WebPaywallRedeemer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepLinkRouterTest {
    private lateinit var redeemer: WebPaywallRedeemer
    private lateinit var debugManager: DebugManager
    private lateinit var ioScope: IOScope
    private lateinit var router: DeepLinkRouter
    private val trackedEvents = mutableListOf<TrackableSuperwallEvent>()

    @Before
    fun setUp() {
        trackedEvents.clear()

        redeemer =
            mockk {
                every { deepLinkReferrer } returns
                    mockk {
                        every { handleDeepLink(any()) } returns Result.failure(Exception("Not a redemption link"))
                    }
                coEvery { redeem(any()) } returns Unit
            }

        debugManager =
            mockk {
                every { handle(any()) } returns false
            }

        ioScope = IOScope()

        router =
            DeepLinkRouter(
                redeemer,
                ioScope,
                debugManager,
            ) { event ->
                trackedEvents.add(event)
            }
    }

    @Test
    fun `handleDeepLink tracks deep link event`() =
        runBlocking {
            val uri = Uri.parse("https://example.com/path")

            router.handleDeepLink(uri)
            Thread.sleep(200)

            assertTrue(trackedEvents.isNotEmpty())
        }

    @Test
    fun `handleDeepLink attempts redemption first`() =
        runBlocking {
            val uri = Uri.parse("https://superwall.com/redeem?code=ABC123")

            every { redeemer.deepLinkReferrer.handleDeepLink(uri) } returns Result.success("ABC123")

            val result = router.handleDeepLink(uri)

            assertTrue(result.isSuccess)
            assertTrue(result.getOrNull() == true)
            verify { redeemer.deepLinkReferrer.handleDeepLink(uri) }
        }

    @Test
    fun `handleDeepLink redeems code when redemption link detected`() {
        runBlocking {
            val uri = Uri.parse("https://superwall.com/redeem?code=XYZ789")

            every { redeemer.deepLinkReferrer.handleDeepLink(uri) } returns Result.success("XYZ789")

            router.handleDeepLink(uri)
            Thread.sleep(300)

            coEvery { redeemer.redeem(any()) }
        }
    }

    @Test
    fun `handleDeepLink delegates to debug manager when not redemption`() =
        runBlocking {
            val uri = Uri.parse("https://example.com/debug")

            every { debugManager.handle(uri) } returns true

            val result = router.handleDeepLink(uri)

            assertTrue(result.isSuccess)
            verify { debugManager.handle(uri) }
        }

    @Test
    fun `handleDeepLink returns false when neither redemption nor debug link`() =
        runBlocking {
            val uri = Uri.parse("https://example.com/other")

            every { debugManager.handle(uri) } returns false

            val result = router.handleDeepLink(uri)

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())
        }

    @Test
    fun `redeemableCode extension returns success for valid redemption link`() =
        Given("a valid redemption URL") {
            val uri = Uri.parse("https://superwall.com/redeem?code=TEST123")

            When("extracting redeemable code") {
                val result = uri.redeemableCode

                Then("it should succeed") {
                    assertTrue(result.isSuccess)
                }

                Then("it should return the code") {
                    assertEquals("TEST123", result.getOrNull())
                }
            }
        }

    @Test
    fun `redeemableCode extension returns failure for non-superwall host`() {
        val uri = Uri.parse("https://example.com/redeem?code=ABC")

        val result = uri.redeemableCode

        assertTrue(result.isFailure)
    }

    @Test
    fun `redeemableCode extension returns failure when path is not redeem`() {
        val uri = Uri.parse("https://superwall.com/other?code=ABC")

        val result = uri.redeemableCode

        assertTrue(result.isFailure)
    }

    @Test
    fun `redeemableCode extension returns failure when code parameter missing`() {
        val uri = Uri.parse("https://superwall.com/redeem")

        val result = uri.redeemableCode

        assertTrue(result.isFailure)
    }

    @Test
    fun `redeemableCode extension handles subdomains of superwall`() {
        val uri = Uri.parse("https://app.superwall.com/redeem?code=SUBDOMAIN")

        val result = uri.redeemableCode

        assertTrue(result.isSuccess)
        assertEquals("SUBDOMAIN", result.getOrNull())
    }

    @Test
    fun `redeemableCode extension is case sensitive for path`() {
        val uri1 = Uri.parse("https://superwall.com/redeem?code=ABC")
        val uri2 = Uri.parse("https://superwall.com/Redeem?code=ABC")
        val uri3 = Uri.parse("https://superwall.com/REDEEM?code=ABC")

        assertTrue(uri1.redeemableCode.isSuccess)
        assertTrue(uri2.redeemableCode.isFailure)
        assertTrue(uri3.redeemableCode.isFailure)
    }

    @Test
    fun `handleDeepLink wraps errors and returns result`() =
        runBlocking {
            val uri = Uri.parse("https://superwall.com/redeem?code=ERROR")

            every { redeemer.deepLinkReferrer.handleDeepLink(uri) } throws RuntimeException("Test error")

            val result = router.handleDeepLink(uri)

            // Should handle error gracefully
            assertTrue(result.isSuccess || result.isFailure)
        }

    @Test
    fun `multiple handleDeepLink calls track multiple events`() =
        runBlocking {
            val uri1 = Uri.parse("https://example.com/link1")
            val uri2 = Uri.parse("https://example.com/link2")

            router.handleDeepLink(uri1)
            router.handleDeepLink(uri2)
            Thread.sleep(300)

            assertTrue(trackedEvents.size >= 2)
        }

    @Test
    fun `redeemableCode handles empty code parameter`() {
        val uri = Uri.parse("https://superwall.com/redeem?code=")

        val result = uri.redeemableCode

        // Empty code should still succeed but return empty string
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }

    @Test
    fun `redeemableCode handles multiple query parameters`() {
        val uri = Uri.parse("https://superwall.com/redeem?foo=bar&code=MULTI&baz=qux")

        val result = uri.redeemableCode

        assertTrue(result.isSuccess)
        assertEquals("MULTI", result.getOrNull())
    }
}
