package com.superwall.sdk.billing

/**
 * Whether Google Play Billing can be used on this device, as learned from the billing
 * client's connection attempts.
 */
internal sealed interface BillingAvailability {
    /** No connection attempt has resolved yet. */
    object Unknown : BillingAvailability

    object Available : BillingAvailability

    /**
     * The device can't use Play Billing (no Play Store, no signed in account, ...).
     * Requests fail straight away with [error] instead of reconnecting.
     */
    data class Unavailable(
        val error: BillingError.BillingNotAvailable,
    ) : BillingAvailability
}
