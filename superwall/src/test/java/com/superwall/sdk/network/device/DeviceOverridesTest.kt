package com.superwall.sdk.network.device

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.IOScope
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOverridesTest {
    private val deviceHelper = mockk<DeviceHelper>(relaxed = true)
    private val steps = mutableListOf<String>()
    private val overrides =
        DeviceOverrides(
            deviceHelper = deviceHelper,
            scope = IOScope(Dispatchers.Unconfined),
            trackDeviceAttributes = { steps += "deviceAttributes" },
            recheckPreload = { steps += "recheckPreload" },
        )

    @Test
    fun `setting the platform wrapper records it and re-sends device attributes`() =
        Given("a wrapper SDK") {
            When("it sets its name and version") {
                overrides.setPlatformWrapper("Flutter", "2.0.0")
                Then("both are set on the device helper") {
                    verify { deviceHelper.platformWrapper = "Flutter" }
                    verify { deviceHelper.platformWrapperVersion = "2.0.0" }
                }
                And("device attributes are sent, without a preload recheck") {
                    assertEquals(listOf("deviceAttributes"), steps)
                }
            }
        }

    @Test
    fun `setting the interface style re-sends attributes, then rechecks preload`() =
        Given("an app that forces dark mode") {
            When("it sets the interface style") {
                overrides.setInterfaceStyle(InterfaceStyle.DARK)
                Then("the override is set") {
                    verify { deviceHelper.interfaceStyleOverride = InterfaceStyle.DARK }
                }
                And("device attributes are sent before the preload recheck") {
                    assertEquals(listOf("deviceAttributes", "recheckPreload"), steps)
                }
            }
        }

    @Test
    fun `clearing the interface style reverts to the system`() =
        Given("an app that had forced a style") {
            When("it sets the interface style to null") {
                overrides.setInterfaceStyle(null)
                Then("the override is cleared") {
                    verify { deviceHelper.interfaceStyleOverride = null }
                }
            }
        }
}
