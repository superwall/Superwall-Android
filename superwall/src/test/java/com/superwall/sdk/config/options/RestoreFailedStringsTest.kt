package com.superwall.sdk.config.options

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestoreFailedStringsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults are the English strings in an English locale`() {
        Given("restore-failed options left at their defaults") {
            val alert = PaywallOptions.RestoreFailed().localized(context)

            Then("the alert reads as before") {
                assertEquals("No Subscription Found", alert.title)
                assertEquals("We couldn't find an active subscription for your account.", alert.message)
                assertEquals("Okay", alert.closeButtonTitle)
            }
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun `defaults are translated in another locale`() {
        Given("restore-failed options left at their defaults on a German device") {
            val alert = PaywallOptions.RestoreFailed().localized(context)

            Then("the alert is in German") {
                assertEquals("Kein Abonnement gefunden", alert.title)
                assertEquals("Wir konnten kein aktives Abonnement für Ihr Konto finden.", alert.message)
                assertEquals("OK", alert.closeButtonTitle)
            }
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun `text set by the app is shown as is`() {
        Given("an app that sets its own restore-failed title") {
            val options =
                PaywallOptions.RestoreFailed {
                    title = "Nichts gefunden"
                }

            When("the alert is built on a German device") {
                val alert = options.localized(context)

                Then("the app's title is kept and the rest is translated") {
                    assertEquals("Nichts gefunden", alert.title)
                    assertEquals("OK", alert.closeButtonTitle)
                }
            }
        }
    }

    @Test
    fun `every locale translates every paywall string`() {
        val resDir = listOf(File("src/main/res"), File("superwall/src/main/res")).first { it.isDirectory }
        fun keys(file: File) =
            Regex("<string name=\"(superwall_[a-z_]+)\">").findAll(file.readText()).map { it.groupValues[1] }.toSet()
        val english = keys(File(resDir, "values/superwall_paywall_strings.xml"))
        val localeDirs = resDir.listFiles { f -> f.name.startsWith("values-") && File(f, "superwall_customer_center_strings.xml").exists() }!!

        assertTrue(localeDirs.size >= 40)
        localeDirs.forEach { dir ->
            val file = File(dir, "superwall_paywall_strings.xml")
            assertTrue("${dir.name} has no paywall strings", file.exists())
            assertEquals(dir.name, english, keys(file))
            assertTrue(
                "${dir.name} has an unescaped apostrophe",
                !Regex("(?<!\\\\)'").containsMatchIn(Regex(">(.*?)<").findAll(file.readText()).joinToString { it.groupValues[1] }),
            )
        }
    }
}
