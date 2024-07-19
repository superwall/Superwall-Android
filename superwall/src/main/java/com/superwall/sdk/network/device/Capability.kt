package com.superwall.sdk.network.device

import com.superwall.sdk.analytics.superwall.SuperwallEvents
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
internal sealed class Capability(
    @SerialName("name")
    val name: String,
) {
    @Serializable
    @SerialName("paywall_event_receiver")
    class PaywallEventReceiver : Capability("paywall_event_receiver") {
        @SerialName("event_names")
        val eventNames =
            listOf(
                SuperwallEvents.TransactionStart,
                SuperwallEvents.TransactionRestore,
                SuperwallEvents.TransactionComplete,
                SuperwallEvents.RestoreStart,
                SuperwallEvents.RestoreFail,
                SuperwallEvents.RestoreComplete,
                SuperwallEvents.TransactionFail,
                SuperwallEvents.TransactionAbandon,
                SuperwallEvents.TransactionTimeout,
                SuperwallEvents.PaywallOpen,
                SuperwallEvents.PaywallClose,
            ).map { it.rawName }
    }
}

internal fun List<Capability>.toJson(json: Json): JsonElement = json.encodeToJsonElement(this)

internal fun List<Capability>.namesCommaSeparated(): String = this.joinToString(",") { it.name }
