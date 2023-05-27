package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.models.triggers.Experiment

sealed class PaywallPresentationRequestStatus(val status: String) {
    object Presentation : PaywallPresentationRequestStatus("presentation")
    object NoPresentation : PaywallPresentationRequestStatus("no_presentation")
    object Timeout : PaywallPresentationRequestStatus("timeout")
}

sealed class PaywallPresentationRequestStatusReason(val description: String) : Throwable() {
    class DebuggerPresented : PaywallPresentationRequestStatusReason("debugger_presented")
    class PaywallAlreadyPresented : PaywallPresentationRequestStatusReason("paywall_already_presented")
    class UserIsSubscribed : PaywallPresentationRequestStatusReason("user_is_subscribed")
    data class Holdout(val experiment: Experiment) : PaywallPresentationRequestStatusReason("holdout")
    class NoRuleMatch : PaywallPresentationRequestStatusReason("no_rule_match")
    class EventNotFound : PaywallPresentationRequestStatusReason("event_not_found")
    class NoPaywallViewController : PaywallPresentationRequestStatusReason("no_paywall_view_controller")
    class NoPresenter : PaywallPresentationRequestStatusReason("no_presenter")
    class Unknown: PaywallPresentationRequestStatusReason("unknown")
}

typealias PresentationPipelineError = PaywallPresentationRequestStatusReason
