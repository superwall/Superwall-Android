package com.superwall.sdk.paywall.view

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.paywall.manager.PaywallViewRegistry
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.PaywallWebUI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real activity and WebView against device-delivered insets, without backend data. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PaywallActivityInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var activity: SuperwallPaywallActivity? = null
    private var container: DependencyContainer? = null
    private lateinit var webView: WebView

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { activity?.finish() }
        activity?.let { finishingActivity -> await("activity teardown") { finishingActivity.isDestroyed } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            if (::webView.isInitialized) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        SuperwallGraph.detach(container)
    }

    @Test
    fun modalContentStaysInsideSystemBarsWhenRotated() = exercise(PaywallPresentationStyle.Modal, "modal")

    @Test
    fun drawerContentStaysInsideSystemBarsWhenRotated() =
        exercise(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 16.0), "drawer")

    @Test
    fun fullscreenContentStaysInsideSystemBarsWhenRotated() = exercise(PaywallPresentationStyle.Fullscreen, "fullscreen")

    @Test
    fun popupContentStaysInsideSystemBarsWhenRotated() =
        exercise(PaywallPresentationStyle.Popup(height = 80.0, width = 90.0, cornerRadius = 16.0), "popup")

    private fun exercise(
        style: PaywallPresentationStyle,
        name: String,
    ) {
        launch(style, name)
        val orientations =
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to "reverse-landscape",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait-restored",
            )
        orientations.forEach { (orientation, label) ->
            instrumentation.runOnMainSync { activity!!.requestedOrientation = orientation }
            await("$name/$label rotation") {
                val rotation = activity!!.display!!.rotation
                when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> rotation == android.view.Surface.ROTATION_0
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> rotation == android.view.Surface.ROTATION_90
                    else -> rotation == android.view.Surface.ROTATION_270
                }
            }
            // Rotation animation and bottom-sheet expansion must settle before checking geometry.
            SystemClock.sleep(700)
            val file = File(context.getExternalFilesDir(null), "insets-$name-$label.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync {
                val decor = activity!!.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.systemBars())
                val position = IntArray(2)
                webView.getLocationInWindow(position)
                val bounds = Rect(position[0], position[1], position[0] + webView.width, position[1] + webView.height)
                println("INSETS $name/$label bars=$insets webView=$bounds window=${decor.width}x${decor.height}")
                assertTrue("$name/$label left $bounds vs $insets", bounds.left >= insets.left)
                assertTrue("$name/$label right $bounds vs $insets", bounds.right <= decor.width - insets.right)
                // A half-expanded drawer clips the lower part of its full-height WebView.
                if (style !is PaywallPresentationStyle.Drawer) {
                    assertTrue("$name/$label bottom $bounds vs $insets", bounds.bottom <= decor.height - insets.bottom)
                }
                if (label.contains("landscape") && expectedSideBar()) {
                    assertTrue("Three-button mode must exercise a horizontal inset", insets.left > 0 || insets.right > 0)
                }
            }
        }

        if (style !is PaywallPresentationStyle.Popup) assertBothSidesAndReset(style)
    }

    private fun expectedSideBar() = InstrumentationRegistry.getArguments().getString("navigationMode") == "threebutton"

    /** Also cover left-sided OEM navigation bars and repeated inset dispatch without accumulation. */
    private fun assertBothSidesAndReset(style: PaywallPresentationStyle) {
        instrumentation.runOnMainSync {
            val target =
                if (style is PaywallPresentationStyle.Fullscreen) {
                    webView
                } else {
                    activity!!.findViewById<FrameLayout>(com.superwall.sdk.R.id.container)
                }
            val top = if (target === webView) (target.layoutParams as ViewGroup.MarginLayoutParams).topMargin else target.paddingTop
            listOf(Insets.of(64, 24, 0, 0), Insets.of(0, 24, 64, 0), Insets.of(0, 24, 0, 48)).forEach { bars ->
                repeat(2) {
                    ViewCompat.dispatchApplyWindowInsets(
                        target,
                        WindowInsetsCompat
                            .Builder()
                            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
                            .build(),
                    )
                    if (target === webView) {
                        val params = target.layoutParams as ViewGroup.MarginLayoutParams
                        assertEquals(bars.left, params.leftMargin)
                        assertEquals(bars.right, params.rightMargin)
                        assertEquals(bars.bottom, params.bottomMargin)
                        assertEquals(top, params.topMargin)
                    } else {
                        assertEquals(bars.left, target.paddingLeft)
                        assertEquals(bars.right, target.paddingRight)
                        assertEquals(bars.bottom, target.paddingBottom)
                        assertEquals(top, target.paddingTop)
                    }
                }
            }
        }
    }

    private fun launch(
        style: PaywallPresentationStyle,
        name: String,
    ) {
        instrumentation.runOnMainSync {
            webView =
                WebView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    loadDataWithBaseURL(
                        null,
                        """
                        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                        <body style="margin:0;background:#edf4ff;font:18px sans-serif">
                        <div style="position:fixed;inset:0;border:5px solid #1565c0;box-sizing:border-box;padding:12px">
                        <h2>$name inset regression</h2><p>Both blue edges must remain clear of system navigation.</p>
                        <button style="position:absolute;bottom:12px;left:12px;right:12px;height:44px">Continue</button>
                        </div></body></html>
                        """.trimIndent(),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            val webUI = mockk<PaywallWebUI>(relaxed = true)
            every { webUI.onView(any()) } answers { firstArg<View.() -> Unit>().invoke(webView) }
            val factory = mockk<PaywallView.Factory>(relaxed = true)
            every { factory.makeSuperwallOptions() } returns SuperwallOptions()
            coEvery { factory.track(any()) } returns Result.success(mockk(relaxed = true))
            val state =
                PaywallViewState(
                    Paywall.stub().copy(backgroundColorHex = "#edf4ff"),
                    "en-US",
                    presentationDidFinishPrepare = true,
                    loadingState = PaywallLoadingState.Ready,
                )
            val view =
                PaywallView(
                    context,
                    deviceHelper = mockk(relaxed = true),
                    factory = factory,
                    storage = mockk(relaxed = true),
                    webView = webUI,
                    cache = null,
                    controller = PaywallView.PaywallController(state),
                    sendMessages = mockk(relaxed = true),
                    redeemer = mockk(relaxed = true),
                )
            view.addView(webView)
            // A relaxed chained registry mock creates a mock android.view.View and
            // instruments every platform View method, making device layout unusably slow.
            val registry =
                object : PaywallViewRegistry {
                    override fun retrieveView(key: String): View = view

                    override fun storeView(
                        key: String,
                        view: View,
                    ) = Unit

                    override fun removeView(key: String) = Unit

                    override fun acquireLoadingView(): PaywallPurchaseLoadingView = error("No loading overlay in this fixture")

                    override fun acquireShimmerView(): PaywallShimmerView = error("No shimmer in this fixture")
                }
            // Not relaxed: this becomes the process-wide graph, so an SDK configured by an earlier
            // test reaches it from background work. Relaxed answers there mock enums and
            // collections, making MockK hook Enum and AbstractCollection for the whole process,
            // which overflows the stack on the next Enum.toString(). Logger reads the last two.
            container =
                mockk<DependencyContainer>().also {
                    every { it.makeViewRegistry() } returns registry
                    every { it.configManager.options } returns SuperwallOptions()
                    every { it.delegateAdapter } returns SuperwallDelegateAdapter()
                    SuperwallGraph.attach(it)
                }
        }
        val intent =
            Intent(context, SuperwallPaywallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("viewKey", "insets-test")
                .putExtra("presentationStyleKey", style.toIntentString(JsonFactory.JSON_POLYMORPHIC))
        activity = instrumentation.startActivitySync(intent) as SuperwallPaywallActivity
        await("paywall layout") { webView.width > 0 && ViewCompat.getRootWindowInsets(webView) != null }
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $message")
    }
}
