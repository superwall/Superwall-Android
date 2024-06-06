package com.superwall.sdk.models.paywall

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlin.random.Random

@Serializable
class LocalNotification(
    val id: Int = Random.nextInt(),
    val type: LocalNotificationType,
    val title: String,
    val body: String,
    val delay: Long,
)

@Serializable(with = LocalNotificationTypeSerializer::class)
sealed class LocalNotificationType {
    @SerialName("TRIAL_STARTED")
    object TrialStarted : LocalNotificationType()

    object Unsupported : LocalNotificationType()
}

@Serializer(forClass = LocalNotificationType::class)
object LocalNotificationTypeSerializer : KSerializer<LocalNotificationType> {
    override fun deserialize(decoder: Decoder): LocalNotificationType =
        when (decoder.decodeString()) {
            "TRIAL_STARTED" -> LocalNotificationType.TrialStarted
            else -> LocalNotificationType.Unsupported
        }
}
