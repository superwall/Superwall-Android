package com.superwall.sdk.store

enum class ReplacementMode(
    val rawName: String,
    val playBillingMode: Int,
) {
    DEFAULT("default", 1),
    CHARGE_LATER("charge_later", 3),
    CHARGE_NOW("charge_now", 5),
    CHARGE_DIFFERENCE("charge_difference", 2),
    CHARGE_ON_EXPIRE("charge_on_expire", 6),
    ;

    companion object {
        fun fromRaw(value: String): ReplacementMode? = entries.firstOrNull { it.rawName == value }
    }
}
