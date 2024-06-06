package com.superwall.sdk.paywall.presentation.internal.state

import com.superwall.sdk.models.triggers.Experiment

sealed class PaywallSkippedReason : Throwable() {
    data class Holdout(
        val experiment: Experiment,
    ) : PaywallSkippedReason()

    class NoRuleMatch : PaywallSkippedReason()

    class EventNotFound : PaywallSkippedReason()

    class UserIsSubscribed : PaywallSkippedReason()

    override fun equals(other: Any?): Boolean =
        when {
            this is Holdout && other is Holdout -> this.experiment == other.experiment
            this is NoRuleMatch && other is NoRuleMatch -> true
            this is EventNotFound && other is EventNotFound -> true
            this is UserIsSubscribed && other is UserIsSubscribed -> true
            else -> false
        }
}
