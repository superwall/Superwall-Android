package com.superwall.sdk.paywall.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallCacheState
import com.superwall.sdk.paywall.manager.PaywallViewCache
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/**
 * PaywallViewCache builds the shared loading and shimmer views on its actor's
 * IO thread, which has no Looper. These tests run on a real device to prove
 * that construction is safe there and that the views still attach, draw and
 * animate once handed to the main thread.
 */
@RunWith(AndroidJUnit4::class)
class OffMainViewConstructionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private fun exerciseOnMain(vararg views: View) {
        instrumentation.runOnMainSync {
            views.forEach { view ->
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 400, 800)
                (view as? PaywallShimmerView)?.showShimmer()
                (view as? PaywallPurchaseLoadingView)?.showLoading()
                view.draw(Canvas(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)))
                (view as? PaywallShimmerView)?.hideShimmer()
            }
        }
    }

    @Test
    fun loadingAndShimmerCanBeBuiltOnAThreadWithoutALooper() {
        var error: Throwable? = null
        var hadLooper = true
        var loading: LoadingView? = null
        var shimmer: ShimmerView? = null
        val thread =
            Thread {
                hadLooper = Looper.myLooper() != null
                try {
                    loading = LoadingView(ctx, loadingColor = android.R.color.black)
                    shimmer = ShimmerView(ctx)
                } catch (t: Throwable) {
                    error = t
                }
            }
        thread.start()
        thread.join()

        assertTrue("background thread must not have a Looper", !hadLooper)
        assertNull("construction off main threw: $error", error)
        exerciseOnMain(loading!!, shimmer!!)
    }

    @Test
    fun cacheAcquireFromMainBuildsViewsOnTheActorThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val cache =
                PaywallViewCache(
                    ctx,
                    object : ViewStorage {
                        override val views = ConcurrentHashMap<String, View>()
                    },
                    mockk<ActivityProvider> { every { getCurrentActivity() } returns null },
                    mockk<DeviceHelper> { every { locale } returns "en_US" },
                    actor = SequentialActor(PaywallCacheState(), scope),
                )
            var loading: PaywallPurchaseLoadingView? = null
            var shimmer: PaywallShimmerView? = null
            instrumentation.runOnMainSync {
                loading = cache.acquireLoadingView()
                shimmer = cache.acquireShimmerView()
            }
            exerciseOnMain(loading as View, shimmer as View)
        } finally {
            scope.cancel()
        }
    }
}
