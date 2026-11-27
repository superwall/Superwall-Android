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
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * TEMPORARY, to be deleted with .github/workflows/firebase-trial.yml.
 *
 * Buys the Stripe sandbox product from the paywall with Stripe's test card and records every
 * screen and SDK event up to the entitlement arriving, to learn what a web purchase test would
 * have to drive. It asserts nothing.
 */
@RunWith(AndroidJUnit4::class)
class WebCheckoutProbe {
    private val device get() = UiDevice.getInstance(getInstrumentation())
    private val dir by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "probe").apply { mkdirs() }
    }
    private val notes = CopyOnWriteArrayList<String>()
    private var written = 0
    private var shots = 0

    private fun note(line: String) {
        notes.add("${System.currentTimeMillis() % 1_000_000} $line")
    }

    private fun flush() {
        runCatching { File(dir, "log_${written++}.txt").writeText(notes.joinToString("\n") + "\n") }
    }

    private fun record(label: String) {
        val name = "%02d_%s".format(++shots, label)
        runCatching {
            File(dir, "$name.png").outputStream().use {
                Screenshot.capture().bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        runCatching { device.dumpWindowHierarchy(File(dir, "$name.xml")) }
        val texts = device.findObjects(By.textStartsWith("")).mapNotNull { it.text }.filter { it.isNotBlank() }.distinct()
        val fields = device.findObjects(By.clazz("android.widget.EditText")).map { "${it.resourceName}='${it.text}'" }
        note("SCREEN $name package=${device.currentPackageName} fields=$fields texts=${texts.take(60)}")
        flush()
    }

    /** Fills a web field by its id, by setting the text and, if that didn't take, by typing. */
    private fun fill(
        id: String,
        value: String,
    ): Boolean {
        val field: UiObject2 = device.wait(Until.findObject(By.res(id)), 4_000) ?: return false.also { note("field $id not found") }
        runCatching { field.text = value }
        Thread.sleep(600)
        var now = runCatching { device.findObject(By.res(id))?.text }.getOrNull().orEmpty()
        if (now.filter { it.isLetterOrDigit() }.isEmpty()) {
            runCatching {
                device.findObject(By.res(id))?.click()
                Thread.sleep(500)
                device.executeShellCommand("input text ${value.replace(" ", "%s")}")
                Thread.sleep(800)
            }
            now = runCatching { device.findObject(By.res(id))?.text }.getOrNull().orEmpty()
            note("field $id typed, now has ${now.length} chars")
        } else {
            note("field $id set, now has ${now.length} chars")
        }
        return true
    }

    @Test
    fun buyWithTestCard() {
        getInstrumentation().uiAutomation.grantRuntimePermission(
            getInstrumentation().targetContext.packageName,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        try {
            run()
        } catch (e: Throwable) {
            note("PROBE FAILED ${e::class.simpleName}: ${e.message}")
            record("failed")
        } finally {
            note("END status=${Superwall.instance.subscriptionStatus.value} entitlements=${Superwall.instance.entitlements.active.map { it.id }}")
            flush()
        }
    }

    private fun run() {
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
                        when (e) {
                            is SuperwallEvent.PaywallPresentationRequest ->
                                " status=${e.status.status} reason=${e.reason?.description}"
                            is SuperwallEvent.SubscriptionStatusDidChange ->
                                " -> ${Superwall.instance.subscriptionStatus.value::class.simpleName}"
                            else -> ""
                        }
                    note("EVENT ${e.rawName}$extra")
                }
            }
        ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(3_000)
        Superwall.instance.register(placement = "test_internal")
        runBlocking { awaitPaywallView() }
        Thread.sleep(4_000)
        device.wait(Until.findObject(By.text("Purchase!!!")), 10_000)?.click() ?: error("no purchase button")

        // Stripe's checkout, in a Chrome custom tab.
        device.wait(Until.hasObject(By.res("email")), 40_000)
        Thread.sleep(2_000)
        record("checkout")
        fill("email", "sw-ci-${System.currentTimeMillis()}@example.com")
        device.findObject(By.res("payment-method-label-card"))?.click() ?: note("no Card option")
        Thread.sleep(4_000)
        record("card_chosen")

        fill("cardNumber", "4242424242424242")
        fill("cardExpiry", "1234")
        fill("cardCvc", "123")
        fill("billingName", "Superwall Test")
        fill("billingPostalCode", "94107")
        record("filled")

        // The last field leaves the keyboard up, over the lower half of the page. Back closes
        // the keyboard while it is showing, and would close the tab if it weren't.
        val keyboardUp = device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")
        note("keyboard up=$keyboardUp")
        if (keyboardUp) {
            device.pressBack()
            Thread.sleep(1_500)
        }
        record("keyboard_closed")

        // Bring the Subscribe button on screen and press it.
        val height = device.displayHeight
        val width = device.displayWidth
        for (i in 0 until 8) {
            val bounds = device.findObject(By.clazz("android.widget.Button").text("Subscribe"))?.visibleBounds
            if (bounds != null && bounds.height() > 40 && bounds.bottom < height - 120) break
            device.swipe(width / 2, height * 6 / 10, width / 2, height * 3 / 10, 25)
            Thread.sleep(900)
        }
        record("before_subscribe")
        device.findObject(By.clazz("android.widget.Button").text("Subscribe"))?.click() ?: note("no Subscribe button")

        // The redirect back, and whatever is in between.
        val back = Pattern.compile(".*(open|return|continue|back to).*app.*|open", Pattern.CASE_INSENSITIVE)
        repeat(14) { i ->
            Thread.sleep(5_000)
            record("after_subscribe_${i * 5 + 5}s")
            if (device.currentPackageName != "com.superwall.superapp" && i in 4..10) {
                device.findObject(By.text(back))?.let {
                    note("tapping '${it.text}'")
                    it.click()
                }
            }
            if (Superwall.instance.entitlements.active.isNotEmpty()) {
                note("entitlements arrived: ${Superwall.instance.entitlements.active.map { it.id }}")
                Thread.sleep(3_000)
                record("entitled")
                return
            }
        }
    }
}
