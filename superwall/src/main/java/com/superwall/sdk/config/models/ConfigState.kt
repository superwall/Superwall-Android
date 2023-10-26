package com.superwall.sdk.config.models

import com.superwall.sdk.models.config.Config

internal sealed class ConfigState {
    object Retrieving : ConfigState()
    object Retrying : ConfigState()
    data class Retrieved(val config: Config) : ConfigState()
    object Failed : ConfigState()
}

internal fun ConfigState.getConfig(): Config? {
    return when (this) {
        is ConfigState.Retrieved -> config
        else -> null
    }
}
