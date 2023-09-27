package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.models.paywall.PresentationCondition

object InternalPresentationLogic {
    data class UserSubscriptionOverrides(
        val isDebuggerLaunched: Boolean,
        val shouldIgnoreSubscriptionStatus: Boolean?,
        var presentationCondition: PresentationCondition?
    )

    fun userSubscribedAndNotOverridden(
        isUserSubscribed: Boolean,
        overrides: UserSubscriptionOverrides
    ): Boolean {
        if (overrides.isDebuggerLaunched) {
            return false
        }

        fun checkSubscriptionStatus(): Boolean {
            if (!isUserSubscribed) {
                return false
            }
            if (overrides.shouldIgnoreSubscriptionStatus == true) {
                return false
            }
            return true
        }

        val presentationCondition = overrides.presentationCondition ?: return checkSubscriptionStatus()

        if (presentationCondition == PresentationCondition.ALWAYS) {
            return false
        }

        return checkSubscriptionStatus()
    }

    fun presentationError(
        domain: String,
        code: Int,
        title: String,
        value: String
    ): Throwable {
        // In Kotlin, we usually throw exceptions rather than errors
        // Kotlin does not have a built-in equivalent to NSError
        return RuntimeException("$domain: $code, $title - $value")
    }
}
