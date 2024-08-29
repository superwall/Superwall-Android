package com.superwall.sdk.config.models

import com.superwall.sdk.models.config.Config

sealed class ConfigState {
    object Retrieving : ConfigState()

    object Retrying : ConfigState()

    data class Retrieved(
        val config: Config,
    ) : ConfigState()

    data class Failed(
        val throwable: Throwable,
    ) : ConfigState()
}

fun ConfigState.getConfig(): Config? =
    when (this) {
        is ConfigState.Retrieved -> config
        else -> null
    }
