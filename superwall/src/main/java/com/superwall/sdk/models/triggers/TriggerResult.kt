package com.superwall.sdk.models.triggers

import com.superwall.sdk.models.serialization.ExceptionSerializer
import kotlinx.serialization.Serializable

// The result of a paywall trigger.
//
// Triggers can conditionally show paywalls. Contains the possible cases resulting from the trigger.
@Serializable
sealed class TriggerResult {

    // This event was not found on the dashboard.
    //
    // Please make sure you have added the event to a campaign on the dashboard and
    // double check its spelling.
    @Serializable
    object EventNotFound : TriggerResult()

    // No matching rule was found for this trigger so no paywall will be shown.
    @Serializable
    object NoRuleMatch : TriggerResult()

    // A matching rule was found and this user will be shown a paywall.
    //
    // - Parameters:
    //   - experiment: The experiment associated with the trigger.
    @Serializable
    data class Paywall(val experiment: Experiment) : TriggerResult()

    // A matching rule was found and this user was assigned to a holdout group so will not be shown a paywall.
    //
    // - Parameters:
    //   - experiment: The experiment  associated with the trigger.
    @Serializable
    data class Holdout(val experiment: Experiment) : TriggerResult()

    // An error occurred and the user will not be shown a paywall.
    //
    // If the error code is `101`, it means that no view controller could be found to present on. Otherwise a network failure may have occurred.
    //
    // In these instances, consider falling back to a native paywall.
    @Serializable
    data class Error(val error: @Serializable(with = ExceptionSerializer::class) Exception) :
        TriggerResult()
}
