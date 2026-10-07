package com.superwall.sdk.customercenter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Given
import com.superwall.sdk.R
import com.superwall.sdk.Then
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerCenterTintTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a configured accent wins, then the host's colour, then system blue`() =
        Given("each combination") {
            Then("the configured accent wins over everything") {
                assertEquals(1, CustomerCenterTint.resolve(configured = 1, host = 2, isDark = false))
            }
            Then("the host's colour is used when nothing is configured") {
                assertEquals(2, CustomerCenterTint.resolve(configured = null, host = 2, isDark = true))
            }
            Then("system blue fills in for light and dark") {
                assertEquals(CustomerCenterTint.FALLBACK_LIGHT, CustomerCenterTint.resolve(null, null, isDark = false))
                assertEquals(CustomerCenterTint.FALLBACK_DARK, CustomerCenterTint.resolve(null, null, isDark = true))
            }
        }

    @Test
    fun `library defaults aren't mistaken for the app's colour`() =
        Given("colour resource names") {
            Then("Material and AppCompat defaults are recognised") {
                assertTrue(CustomerCenterTint.isLibraryColorName("m3_sys_color_light_primary"))
                assertTrue(CustomerCenterTint.isLibraryColorName("design_default_color_primary"))
                assertTrue(CustomerCenterTint.isLibraryColorName("primary_material_light"))
                assertTrue(CustomerCenterTint.isLibraryColorName("abc_primary_text_material_light"))
            }
            Then("an app's own colour isn't") {
                assertFalse(CustomerCenterTint.isLibraryColorName("brand_primary"))
                assertFalse(CustomerCenterTint.isLibraryColorName("colorPrimary"))
            }
        }

    @Test
    fun `a theme that only inherits defaults gives no host colour`() =
        Given("themes that don't choose a primary colour") {
            Then("a framework theme gives none") {
                assertNull(CustomerCenterTint.hostColor(context, android.R.style.Theme_Material_Light))
            }
            Then("a Material 3 theme left at its defaults gives none") {
                assertNull(CustomerCenterTint.hostColor(context, R.style.Theme_Superwall_CustomerCenter))
            }
            Then("no theme gives none") { assertNull(CustomerCenterTint.hostColor(context, 0)) }
        }
}
