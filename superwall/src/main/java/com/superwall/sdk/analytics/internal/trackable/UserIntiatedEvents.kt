package com.superwall.sdk.analytics.internal.trackable


interface TrackableUserInitiatedEvent : Trackable

sealed class UserInitiatedEvent(
    override val rawName: String,
    override val canImplicitlyTriggerPaywall: Boolean,
    override var customParameters: HashMap<String, Any> = HashMap(),
    val isFeatureGatable: Boolean
) : TrackableUserInitiatedEvent {

    class Track(
        rawName: String,
        canImplicitlyTriggerPaywall: Boolean,
        isFeatureGatable: Boolean,
        customParameters: HashMap<String, Any> = HashMap()
    ) : UserInitiatedEvent(
        rawName,
        canImplicitlyTriggerPaywall,
        customParameters,
        isFeatureGatable
    ) {

        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf("is_feature_gatable" to isFeatureGatable)
        }
    }
}
