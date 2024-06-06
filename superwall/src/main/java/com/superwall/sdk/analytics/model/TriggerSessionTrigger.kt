package com.superwall.sdk.analytics.model

import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.models.triggers.Experiment
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonElement
import java.util.Date

@Serializable
data class TriggerSessionTrigger(
    @SerialName("paywall_trigger_event_id")
    var eventId: String? = null,
    @SerialName("paywall_trigger_event_name")
    var eventName: String,
    @SerialName("paywall_trigger_event_params")
    var eventParameters: JsonElement? = null,
    @SerialName("paywall_trigger_event_ts")
    @Serializable(with = DateSerializer::class)
    var eventCreatedAt: Date? = null,
    @SerialName("paywall_trigger_trigger_type")
    var type: TriggerType? = null,
    @SerialName("paywall_trigger_presented_on_description")
    val presentedOn: String? = null,
    var experiment: Experiment? = null,
) {
    @Serializable
    enum class TriggerType {
        @SerialName("IMPLICIT")
        IMPLICIT,

        @SerialName("EXPLICIT")
        EXPLICIT,
    }
}
