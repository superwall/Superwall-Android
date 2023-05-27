package com.superwall.sdk.analytics.model

import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.models.triggers.Trigger
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.*

@Serializable
data class TriggerSession(
    @SerialName("trigger_session_id") var id: String = UUID.randomUUID().toString(),
    @SerialName("config_request_id") val configRequestId: String,
    @SerialName("trigger_session_start_ts")
    @Serializable(with = DateSerializer::class)
    var startAt: Date = Date(),

    @SerialName("trigger_session_end_ts")
    @Serializable(with = DateSerializer::class)
    var endAt: Date? = null,
    @SerialName("user_attributes") var userAttributes: JsonElement? = null,
    @SerialName("user_is_subscribed") var isSubscribed: Boolean,
    val presentationOutcome: PresentationOutcome? = null,
    val trigger: Trigger,
    val paywall: Paywall? = null,
    // TODO: Re-enable when we have a transaction model
//    val products: Products,
//    val transaction: Transaction? = null,
    val appSession: AppSession
) {
    @Serializable
    enum class PresentationOutcome {
        @SerialName("PAYWALL")
        PAYWALL,
        @SerialName("HOLDOUT")
        HOLDOUT,
        @SerialName("NO_RULE_MATCH")
        NO_RULE_MATCH
    }
}