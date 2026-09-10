package com.superwall.sdk.paywall.view.webview

import android.app.Activity
import android.os.Looper
import android.view.View
import com.superwall.sdk.config.options.PaywallOptions
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class SWWebViewMediaLifecycleTest {
    @Test
    fun `media follows host pause visibility and cached reattachment`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val webView = SWWebView(activity, mockk(relaxed = true), options = { PaywallOptions() })
        try {
            // A preload must remain paused even if its host resumes.
            webView.onResume()
            assertMediaAllowed(webView, false)

            activity.setContentView(webView)
            setWindowVisibility(webView, View.VISIBLE)
            assertMediaAllowed(webView, true)
            webView.onPause()
            assertMediaAllowed(webView, false)
            webView.onResume()
            assertMediaAllowed(webView, true)

            webView.visibility = View.GONE
            assertMediaAllowed(webView, false)
            webView.visibility = View.VISIBLE
            assertMediaAllowed(webView, true)

            setWindowVisibility(webView, View.GONE)
            assertMediaAllowed(webView, false)
            setWindowVisibility(webView, View.VISIBLE)
            assertMediaAllowed(webView, true)

            webView.onPause()
            activity.setContentView(View(activity))
            assertMediaAllowed(webView, false)
            // Reusing a dismissed paywall in another host must clear the old pause state.
            activity.setContentView(webView)
            assertMediaAllowed(webView, true)
        } finally {
            controller.pause().stop().destroy()
            webView.destroy()
        }
    }

    private fun setWindowVisibility(webView: SWWebView, visibility: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric attaches the view but leaves the window GONE. Emulate WindowManager's
        // visibility update before dispatching the same callback Android uses.
        val attachInfo = ReflectionHelpers.getField<Any>(webView, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", visibility)
        webView.dispatchWindowVisibilityChanged(visibility)
    }

    private fun assertMediaAllowed(webView: SWWebView, allowed: Boolean) {
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "attached=${webView.isAttachedToWindow}, shown=${webView.isShown}, window=${webView.windowVisibility}",
            MediaPlaybackScript.build(allowed),
            shadowOf(webView).lastEvaluatedJavascript,
        )
    }
}
