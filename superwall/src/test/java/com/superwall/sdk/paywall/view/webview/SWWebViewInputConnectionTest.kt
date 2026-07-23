package com.superwall.sdk.paywall.view.webview

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SWWebViewInputConnectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var webView: SWWebView
    private lateinit var superwallOptions: SuperwallOptions

    @Before
    fun setUp() {
        webView =
            SWWebView(
                context = context,
                messageHandler = mockk<PaywallMessageHandler>(relaxed = true),
                options = { PaywallOptions() },
            )
        superwallOptions = SuperwallOptions()
        val superwall = mockk<Superwall>()
        every { superwall.options } returns superwallOptions
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall
        Superwall.initialized = true
    }

    @After
    fun tearDown() {
        Superwall.initialized = false
        unmockkObject(Superwall.Companion)
    }

    @Test
    fun `returns dummy input connection when game controller is enabled`() {
        Given("game controller support is enabled") {
            superwallOptions.isGameControllerEnabled = true
            When("the IME requests an input connection") {
                val connection = webView.onCreateInputConnection(EditorInfo())
                Then("the dummy connection suppressing the IME pipeline is returned") {
                    assertTrue(connection is BaseInputConnection)
                }
            }
        }
    }

    @Test
    fun `delegates to the webview when game controller is disabled`() {
        Given("game controller support is disabled") {
            superwallOptions.isGameControllerEnabled = false
            When("the IME requests an input connection") {
                val connection = webView.onCreateInputConnection(EditorInfo())
                Then("the dummy connection is not returned, so Chromium's IME handling applies") {
                    assertFalse(connection is BaseInputConnection)
                }
            }
        }
    }

    @Test
    fun `delegates to the webview when Superwall is not initialized`() {
        Given("Superwall has not been configured yet") {
            Superwall.initialized = false
            When("the IME requests an input connection") {
                val connection = webView.onCreateInputConnection(EditorInfo())
                Then("the dummy connection is not returned") {
                    assertFalse(connection is BaseInputConnection)
                }
            }
        }
    }
}
