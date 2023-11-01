package com.superwall.sdk.analytics.trigger_session

import android.view.View
import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo

class TriggerSessionManagerLogic {
    companion object {
        fun outcome(
            presentationInfo: PresentationInfo,
            triggerResult: TriggerResult?
        ): TriggerSession.PresentationOutcome? {
            when (presentationInfo) {
                is PresentationInfo.ImplicitTrigger,
                is PresentationInfo.ExplicitTrigger -> {
                    triggerResult ?: return null

                    when (triggerResult) {
                        is TriggerResult.Error,
                        is TriggerResult.EventNotFound -> return null

                        is TriggerResult.Holdout -> {
                            return TriggerSession.PresentationOutcome.HOLDOUT
                        }

                        is TriggerResult.NoRuleMatch -> {
                            return TriggerSession.PresentationOutcome.NO_RULE_MATCH
                        }

                        is TriggerResult.Paywall -> {
                            return TriggerSession.PresentationOutcome.PAYWALL
                        }
                    }
                }

                is PresentationInfo.FromIdentifier -> {
                    return TriggerSession.PresentationOutcome.PAYWALL
                }
            }
        }
    }
}