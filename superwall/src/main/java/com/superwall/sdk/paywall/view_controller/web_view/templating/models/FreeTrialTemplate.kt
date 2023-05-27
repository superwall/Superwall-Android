package com.superwall.sdk.paywall.view_controller.web_view.templating.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FreeTrialTemplate(
    @SerialName("event_name")
    val eventName: String,
    val prefix: String? = null
)
