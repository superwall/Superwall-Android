package com.example.superapp.test

import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.screenshot.Screenshot
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.example.superapp.utils.awaitPaywallView
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.paywall.presentation.register
import com.superwall.superapp.Keys
import com.superwall.superapp.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * TEMPORARY, to be deleted with .github/workflows/firebase-trial.yml.
 *
 * Presents the paywall with the Stripe button and taps its purchase button, recording what is
 * on screen at each point, to learn what a web purchase test would have to drive. It asserts
 * nothing and doesn't pay.
 */
@RunWith(AndroidJUnit4::class)
class WebCheckoutProbe {
    private val device get() = UiDevice.getInstance(getInstrumentation())
    private val dir by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "probe").apply { mkdirs() }
    }
    private val log by lazy { File(dir, "log.txt") }

    private fun note(line: String) = log.appendText("${System.currentTimeMillis() % 1_000_000} $line\n")

    private fun record(name: String) {
        runCatching {
            File(dir, "$name.png").outputStream().use {
                Screenshot.capture().bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        runCatching { device.dumpWindowHierarchy(File(dir, "$name.xml")) }
        val texts = device.findObjects(By.textStartsWith("")).mapNotNull { it.text }.filter { it.isNotBlank() }.distinct()
        note("SCREEN $name package=${device.currentPackageName} texts=${texts.take(80)}")
    }

    @Test
    fun presentAndTapPurchase() {
        getInstrumentation().uiAutomation.grantRuntimePermission(
            getInstrumentation().targetContext.packageName,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        Superwall.configure(
            getInstrumentation().targetContext.applicationContext as Application,
            Keys.WEB_2_APP_API_KEY,
            options = SuperwallOptions().apply { paywalls.shouldPreload = false },
        )
        Superwall.instance.delegate =
            object : SuperwallDelegate {
                override fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
                    val e = eventInfo.event
                    val extra =
                        if (e is SuperwallEvent.PaywallPresentationRequest) {
                            " status=${e.status.status} reason=${e.reason?.description} ${e.reason?.info}"
                        } else {
                            ""
                        }
                    note("EVENT ${e.rawName}$extra")
                }
            }
        ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(3_000)
        Superwall.instance.register(placement = "test_internal")
        val shown = runCatching { runBlocking { awaitPaywallView() } }.isSuccess
        note("paywall on screen=$shown")
        Thread.sleep(5_000)
        record("1_paywall")
        if (!shown) return

        val purchase = Pattern.compile(".*(continue|subscribe|purchase|buy|start|checkout|pay|trial|unlock).*", Pattern.CASE_INSENSITIVE)
        val button = device.findObjects(By.text(purchase)).firstOrNull()
        note("purchase button=${button?.text}")
        button?.click() ?: return
        for ((i, wait) in listOf(4_000L, 8_000L, 15_000L).withIndex()) {
            Thread.sleep(wait)
            record("${i + 2}_after_tap")
        }
        note("END status=${Superwall.instance.subscriptionStatus.value} entitlements=${Superwall.instance.entitlements.active.map { it.id }}")
    }
}
