package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.getConfig
import com.superwall.sdk.misc.primitives.BaseContext
import kotlinx.coroutines.flow.StateFlow

/**
 * All dependencies available to MMP install-attribution [MMPAttributionState.Actions].
 */
internal interface MMPAttributionContext : BaseContext<MMPAttributionState, MMPAttributionContext> {
    val configState: StateFlow<ConfigState>

    /** Whether config has turned the MMP on for this app. It's off by default. */
    val isMmpEnabled: Boolean
        get() = configState.value.getConfig()?.isMmpEnabled == true
}
