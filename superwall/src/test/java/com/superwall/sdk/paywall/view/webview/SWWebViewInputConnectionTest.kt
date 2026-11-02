package com.superwall.sdk.paywall.view.webview

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.dependencies.attachMockGraph
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SWWebViewInputConnectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var webView: SWWebView
    private lateinit var superwallOptions: SuperwallOptions
    private lateinit var container: DependencyContainer

    @Before
    fun setUp() {
        webView =
            SWWebView(
                context = context,
                messageHandler = mockk<PaywallMessageHandler>(relaxed = true),
                options = { PaywallOptions() },
            )
        superwallOptions = SuperwallOptions()
        container = attachMockGraph(superwallOptions)
    }

    @After
    fun tearDown() {
        SuperwallGraph.detach(container)
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
            SuperwallGraph.detach(container)
            When("the IME requests an input connection") {
                val connection = webView.onCreateInputConnection(EditorInfo())
                Then("the dummy connection is not returned") {
                    assertFalse(connection is BaseInputConnection)
                }
            }
        }
    }
}
