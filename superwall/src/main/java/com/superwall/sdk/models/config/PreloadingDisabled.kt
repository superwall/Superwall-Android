package com.superwall.sdk.models.config

import kotlinx.serialization.Serializable

@Serializable
internal data class PreloadingDisabled(
    val all: Boolean,
    val triggers: Set<String>
) {
    companion object {
        fun stub() = PreloadingDisabled(
            all = false,
            triggers = emptySet()
        )
    }
}
