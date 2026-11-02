package com.superwall.sdk.store

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus

/**
 * Sets the subscription status, for the app ([setSubscriptionStatus]) and for the SDK's own
 * purchase handling ([internallySetSubscriptionStatus]).
 *
 * Both are ignored while test mode is active. The SDK's own updates are also ignored when the
 * app brings its own purchase controller, and merge in the entitlements bought on the web.
 */
internal class SubscriptionStatusManager(
    private val entitlements: () -> Entitlements,
    private val isTestMode: () -> Boolean,
    private val hasExternalPurchaseController: () -> Boolean,
) {
    /** Sets the status the app gives, unless test mode is active. */
    fun setSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        if (isTestMode()) {
            Logger.debug(
                LogLevel.warn,
                LogScope.superwallCore,
                "setSubscriptionStatus ignored: test mode is active",
            )
            return
        }
        entitlements().setSubscriptionStatus(subscriptionStatus)
    }

    /** Sets an Active status with these entitlement ids, or Inactive when there are none. */
    fun setSubscriptionStatus(entitlementIds: Array<out String>) {
        if (entitlementIds.isEmpty()) {
            setSubscriptionStatus(SubscriptionStatus.Inactive)
        } else {
            setSubscriptionStatus(
                SubscriptionStatus.Active(entitlementIds.map { Entitlement(it) }.toSet()),
            )
        }
    }

    /**
     * Sets the status the SDK found on device, merged with the web entitlements. Does nothing
     * when the app brings its own purchase controller, or while test mode is active.
     */
    fun internallySetSubscriptionStatus(toStatus: SubscriptionStatus) {
        if (hasExternalPurchaseController()) {
            return
        }
        if (isTestMode()) {
            return
        }
        val entitlements = entitlements()
        val webEntitlements = entitlements.web
        when (toStatus) {
            is SubscriptionStatus.Active -> {
                val allEntitlements = toStatus.entitlements.plus(webEntitlements)
                if (allEntitlements.isEmpty()) {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
                } else {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(allEntitlements))
                }
            }

            SubscriptionStatus.Inactive ->
                if (webEntitlements.isEmpty()) {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
                } else {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(webEntitlements))
                }

            SubscriptionStatus.Unknown -> {
                entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)
            }
        }
    }
}
