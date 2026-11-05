package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.getConfig
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import kotlinx.coroutines.flow.StateFlow

/**
 * All dependencies available to MMP install-attribution [MMPAttributionState.Actions].
 */
internal interface MMPAttributionContext : BaseContext<MMPAttributionState, MMPAttributionContext> {
    val configState: StateFlow<ConfigState>

    /** The current user's attributes, to skip merging values they already have. */
    val userAttributes: () -> Map<String, Any>

    val setUserAttributes: (Map<String, Any?>) -> Unit

    /** Sends the match request with the Play install referrer's click id, if any. */
    val sendMatchRequest: suspend (installReferrerClickId: Long?) -> Either<MmpMatchResponse, NetworkError>

    /** Reads the `sw_mmp_click_id` from the Play install referrer, or null. */
    val readInstallReferrerClickId: suspend () -> Long?

    val eventTrackingBehavior: () -> EventTrackingBehavior

    /** Whether config has turned the MMP on for this app. It's off by default. */
    val isMmpEnabled: Boolean
        get() = configState.value.getConfig()?.isMmpEnabled == true
}
