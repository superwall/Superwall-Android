package com.superwall.sdk.network.device

import com.superwall.sdk.misc.primitives.StoreContext

/**
 * All dependencies available to device IP [DeviceIpState.Actions].
 */
interface DeviceIpContext : StoreContext<DeviceIpState, DeviceIpContext> {
    val lookups: List<DeviceIpCollector.Lookup>
    val now: () -> Long
}
