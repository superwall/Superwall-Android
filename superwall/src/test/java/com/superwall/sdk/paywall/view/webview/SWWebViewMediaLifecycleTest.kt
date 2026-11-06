package com.superwall.sdk.paywall.view.webview

import android.app.Activity
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val webView = testSWWebView(activity)
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

            // Detaching dispatches window visibility before the window state changes, so a
            // resumed view must still end paused.
            activity.setContentView(View(activity))
            assertMediaAllowed(webView, false)
            activity.setContentView(webView)
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

    @Test
    fun `a preload stops rendering once it is ready and off screen`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val webView = testSWWebView(activity)
        try {
            // A preload renders while it loads (it needs its timers to render and accept templates), and
            // stops once it is ready while still off screen. A host resume does not wake it before attach.
            webView.prepareWebview()
            assertFalse(webView.renderingPaused)
            webView.onPaywallReady()
            assertTrue(webView.renderingPaused)

            // A reload of the cached view renders again until the next ready.
            webView.prepareWebview()
            assertFalse(webView.renderingPaused)
            webView.onPaywallReady()
            assertTrue(webView.renderingPaused)

            activity.setContentView(webView)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(webView.renderingPaused)
            // Ready while on screen (the first presentation of a paywall that was not preloaded): keeps rendering.
            webView.onPaywallReady()
            assertFalse(webView.renderingPaused)

            webView.onPause()
            assertTrue(webView.renderingPaused)
            webView.onResume()
            assertFalse(webView.renderingPaused)

            // Dismissed back into the cache: paused again until the next host attaches it.
            activity.setContentView(View(activity))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(webView.renderingPaused)
            activity.setContentView(webView)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(webView.renderingPaused)
        } finally {
            controller.pause().stop().destroy()
            webView.destroy()
        }
    }

    @Test
    fun `a page detached before it is ready keeps rendering until ready`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val webView = testSWWebView(activity)
        try {
            // Presented while still loading, then dismissed back into the cache before it was ready.
            webView.prepareWebview()
            activity.setContentView(webView)
            shadowOf(Looper.getMainLooper()).idle()
            activity.setContentView(View(activity))
            shadowOf(Looper.getMainLooper()).idle()
            // Pausing now would stop its timers and leave it half-loaded.
            assertFalse(webView.renderingPaused)

            webView.onPaywallReady()
            assertTrue(webView.renderingPaused)
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
