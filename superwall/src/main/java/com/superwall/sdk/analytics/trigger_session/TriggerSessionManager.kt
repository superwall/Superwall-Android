package com.superwall.sdk.analytics.trigger_session

import android.app.Activity
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo

class TriggerSessionManager {
    fun activateSession(
        presentationInfo: PresentationInfo,
        presentingViewController: Activity? = null,
        paywall: Paywall? = null,
        triggerResult: TriggerResult?
    ){

    }
}