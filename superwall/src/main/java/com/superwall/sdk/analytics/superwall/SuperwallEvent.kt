package com.superwall.sdk.analytics.superwall

import android.net.Uri
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.config.models.SurveyOption
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransactionType
import com.superwall.sdk.store.transactions.RestoreType
import com.superwall.sdk.store.transactions.TransactionError
import java.net.URL


/// Analytical events that are automatically tracked by Superwall.
///
/// These events are tracked internally by the SDK and sent to the delegate method ``SuperwallDelegate/handleSuperwallEvent(withInfo:)-pm3v``.
sealed class SuperwallEvent {


    /// When the user is first seen in the app, regardless of whether the user is logged in or not.
    class FirstSeen() : SuperwallEvent() {
        override val rawName: String
            get() = "first_seen"
    }

    /// Anytime the app enters the foreground
    class AppOpen() : SuperwallEvent() {
        override val rawName: String
            get() = "app_open"
    }

    /// When the app is launched from a cold start
    ///
    /// The raw value of this event can be added to a campaign to trigger a paywall.
    class AppLaunch() : SuperwallEvent() {
        override val rawName: String
            get() = "app_launch"
    }

    /// When the user's identity aliases after calling identify
    class IdentityAlias() : SuperwallEvent() {
        override val rawName: String
            get() = "identity_alias"
    }

    /// When the SDK is configured for the first time, or directly after calling ``Superwall/reset()``.
    ///
    /// The raw value of this event can be added to a campaign to trigger a paywall.
    class AppInstall() : SuperwallEvent() {
        override val rawName: String
            get() = "app_install"
    }

    /// When the app is opened at least an hour since last  ``appClose``.
    ///
    /// The raw value of this event can be added to a campaign to trigger a paywall.
    class SessionStart() : SuperwallEvent() {
        override val rawName: String
            get() = "session_start"
    }

    /// When device attributes are sent to the backend.
    data class DeviceAttributes(val attributes: Map<String, Any>) : SuperwallEvent() {
        override val rawName: String
            get() = "device_attributes"
    }

    /// When the user's subscription status changes.
    class SubscriptionStatusDidChange() : SuperwallEvent() {
        override val rawName: String
            get() = "subscriptionStatus_didChange"
    }

    /// Anytime the app leaves the foreground.
    class AppClose() : SuperwallEvent() {
        override val rawName: String
            get() = "app_close"
    }

    /// When a user opens the app via a deep link.
    ///
    /// The raw value of this event can be added to a campaign to trigger a paywall.
    data class DeepLink(val uri: Uri) : SuperwallEvent() {
        override val rawName: String
            get() = "deepLink_open"
    }

    /// When the tracked event matches an event added as a paywall trigger in a campaign.
    ///
    /// The result of firing the trigger is accessible in the `result` associated value.
    data class TriggerFire(val eventName: String, val result: TriggerResult) : SuperwallEvent() {
        override val rawName: String
            get() = "trigger_fire"
    }

    /// When a paywall is opened.
    data class PaywallOpen(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywall_open"
    }

    /// When a paywall is closed.
    data class PaywallClose(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywall_close"
    }

    /// When a user dismisses a paywall instead of purchasing.
    data class PaywallDecline(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywall_decline"
    }

    /// When the payment sheet is displayed to the user.
    data class TransactionStart(val product: StoreProduct, val paywallInfo: PaywallInfo) :
        SuperwallEvent() {
        override val rawName: String
            get() = "transaction_start"
    }

    /// When the payment sheet fails to complete a transaction (ignores user canceling the transaction).
    // TODO: Re-enable when this is defined
//    data class TransactionFail(val error: TransactionError, val paywallInfo: PaywallInfo) : SuperwallEvent()
    data class TransactionFail(val error: TransactionError, val paywallInfo: PaywallInfo) :
        SuperwallEvent() {
        override val rawName: String
            get() = "transaction_fail"
    }

    /// When the user cancels a transaction.
    data class TransactionAbandon(val product: StoreProduct, val paywallInfo: PaywallInfo) :
        SuperwallEvent() {
        override val rawName: String
            get() = "transaction_abandon"
    }

    /// When the user completes checkout in the payment sheet and any product was purchased.
    ///
    /// - Note: The `transaction` is an optional ``StoreTransaction`` object. Most of the time
    /// this won't be `null`. However, it could be `null` if you are using a ``PurchaseController``
    /// and the transaction object couldn't be detected after you return `.purchased` in ``PurchaseController/purchase(product:)``.
//    data class TransactionComplete(val transaction: StoreTransaction?, val product: StoreProduct, val paywallInfo: PaywallInfo) : SuperwallEvent()
    data class TransactionComplete(
        val transaction: StoreTransactionType?,
        val product: StoreProduct,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "transaction_complete"
    }

    /// When the user successfully completes a transaction for a subscription product with no introductory offers.
    data class SubscriptionStart(val product: StoreProduct, val paywallInfo: PaywallInfo) :
        SuperwallEvent() {
        override val rawName: String
            get() = "subscription_start"
    }

    /// When the user successfully completes a transaction for a subscription product with an introductory offer.
    data class FreeTrialStart(val product: StoreProduct, val paywallInfo: PaywallInfo) :
        SuperwallEvent() {
        override val rawName: String
            get() = "freeTrial_start"
    }

    /// When the user successfully restores their purchases.
    data class TransactionRestore(
        val restoreType: RestoreType,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "transaction_restore"
    }

    /// When the transaction took > 5 seconds to show the payment sheet.
    data class TransactionTimeout(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "transaction_timeout"
    }

    /// When the user attributes are set.
    data class UserAttributes(val attributes: Map<String, Any>) : SuperwallEvent() {
        override val rawName: String
            get() = "user_attributes"
    }

    data class NonRecurringProductPurchase(
        val product: TransactionProduct,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "nonRecurringProduct_purchase"
    }

    /// When a paywall's request to Superwall's servers has started.
    data class PaywallResponseLoadStart(val triggeredEventName: String?) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallResponseLoad_start"
    }

    /// When a paywall's request to Superwall's servers returned a 404 error.
    data class PaywallResponseLoadNotFound(val triggeredEventName: String?) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallResponseLoad_notFound"
    }

    /// When a paywall's request to Superwall's servers produced an error.
    data class PaywallResponseLoadFail(val triggeredEventName: String?) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallResponseLoad_fail"
    }

    /// When a paywall's request to Superwall's servers is complete.
    data class PaywallResponseLoadComplete(
        val triggeredEventName: String?,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallResponseLoad_complete"
    }

    /// When a paywall's website begins to load.
    data class PaywallWebviewLoadStart(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallWebviewLoad_start"
    }

    /// When a paywall's website fails to load.
    data class PaywallWebviewLoadFail(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallWebviewLoad_fail"
    }

    /// When a paywall's website completes loading.
    data class PaywallWebviewLoadComplete(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallWebviewLoad_complete"
    }

    /// When the loading of a paywall's website times out.
    data class PaywallWebviewLoadTimeout(val paywallInfo: PaywallInfo) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallWebviewLoad_timeout"
    }

    /// When the request to load the paywall's products started.
    data class PaywallProductsLoadStart(
        val triggeredEventName: String?,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallProductsLoad_start"
    }

    /// When the request to load the paywall's products failed.
    data class PaywallProductsLoadFail(
        val errorMessage: String?,
        val triggeredEventName: String?,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallProductsLoad_fail"
    }

    /// When the request to load the paywall's products completed.
    data class PaywallProductsLoadComplete(
        val triggeredEventName: String?,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallProductsLoad_complete"
    }

    /// Information about the paywall presentation request
    data class PaywallPresentationRequest(
        val status: PaywallPresentationRequestStatus,
        val reason: PaywallPresentationRequestStatusReason?
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "paywallPresentationRequest"
    }

    /// When the response to a paywall survey is recorded.
    data class SurveyResponse(
        val survey: Survey,
        val selectedOption: SurveyOption,
        val customResponse: String?,
        val paywallInfo: PaywallInfo
    ) : SuperwallEvent() {
        override val rawName: String
            get() = "survey_response"
    }

    /// When the user chose the close button on a survey instead of responding.
    class SurveyClose() : SuperwallEvent() {
        override val rawName: String
            get() = "survey_close"
    }

    open val rawName: String
        get() = this.toString()


    open val objcEvent: SuperwallEventObjc
        get() {
            return SuperwallEventObjc.values().find { it.rawName == rawName }!!
        }

    val canImplicitlyTriggerPaywall: Boolean
        get() = when (this) {
            is AppInstall,
            is SessionStart,
            is AppLaunch,
            is DeepLink,
            is TransactionFail,
            is PaywallDecline,
            is TransactionAbandon,
            is SurveyResponse -> true
            else -> false
        }
}