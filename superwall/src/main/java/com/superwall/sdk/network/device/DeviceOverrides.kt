package com.superwall.sdk.network.device

import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Applies the device values an app or a cross-platform wrapper sets on [DeviceHelper], then
 * re-sends the device attributes so Superwall sees them.
 */
internal class DeviceOverrides(
    private val deviceHelper: DeviceHelper,
    private val scope: CoroutineScope,
    private val trackDeviceAttributes: suspend () -> Unit,
    private val recheckPreload: suspend () -> Unit,
) {
    /** Records the wrapper SDK (Flutter, React Native…) and its version. */
    fun setPlatformWrapper(
        wrapper: String,
        version: String,
    ) {
        withErrorTracking {
            deviceHelper.platformWrapper = wrapper
            deviceHelper.platformWrapperVersion = version
            scope.launch {
                trackDeviceAttributes()
            }
        }
    }

    /**
     * Overrides the system light or dark mode, or reverts to it with `null`. Paywalls preloaded
     * for the other style are rechecked.
     */
    fun setInterfaceStyle(interfaceStyle: InterfaceStyle?) {
        withErrorTracking {
            deviceHelper.interfaceStyleOverride = interfaceStyle
            scope.launch {
                trackDeviceAttributes()
                recheckPreload()
            }
        }
    }
}
