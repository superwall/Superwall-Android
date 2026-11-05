package com.superwall.sdk.paywall.view.webview

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
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
    private var gameControllerEnabled = false
    private lateinit var webView: SWWebView

    @Before
    fun setUp() {
        webView = testSWWebView(context, gameControllerEnabled = { gameControllerEnabled })
    }

    @Test
    fun `returns dummy input connection when game controller is enabled`() {
        Given("game controller support is enabled") {
            gameControllerEnabled = true
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
            gameControllerEnabled = false
            When("the IME requests an input connection") {
                val connection = webView.onCreateInputConnection(EditorInfo())
                Then("the dummy connection is not returned, so Chromium's IME handling applies") {
                    assertFalse(connection is BaseInputConnection)
                }
            }
        }
    }
}
