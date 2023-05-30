package com.superwall.sdk.delegate

sealed class SubscriptionStatus {
    object Active : SubscriptionStatus()
    object Inactive : SubscriptionStatus()
    object Unknown : SubscriptionStatus()
}