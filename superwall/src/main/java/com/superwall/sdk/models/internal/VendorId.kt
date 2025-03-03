package com.superwall.sdk.models.internal

@JvmInline
value class VendorId(
    val value: String,
) {
    override fun toString(): String = value
}

class DeviceVendorId(
    vendorId: VendorId,
) {
    val value = "\$SuperwallDevice:${vendorId.value}"

    override fun toString(): String = value
}
