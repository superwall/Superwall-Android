package com.superwall.sdk.customercenter

/**
 * Receives Customer Center events. All methods have default implementations, and all are called
 * on the main thread.
 *
 * The Customer Center retains the delegate passed to
 * [com.superwall.sdk.Superwall.presentCustomerCenter] only while it is presented.
 */
interface CustomerCenterDelegate {
    /**
     * Called before purchases are restored. Call `proceed(true)` to continue or `proceed(false)`
     * to cancel, for example after the user declines to sign in. Call it exactly once: the restore
     * waits until you do, and any call after the first is ignored.
     */
    fun customerCenterShouldRestorePurchases(proceed: (Boolean) -> Unit) {
        proceed(true)
    }

    /**
     * Called whenever the user taps a path, including custom and URL paths, before the action
     * runs. [pathId] is the tapped path's [CustomerCenterConfiguration.Path.id], and [purchase]
     * the purchase it applies to, or `null` for a screen-level action such as restore.
     */
    fun customerCenterDidSelectAction(
        action: CustomerCenterAction,
        pathId: String,
        purchase: CustomerCenterPurchase?,
    ) {}

    /** Called when the user answers a survey attached to a path. */
    fun customerCenterDidCompleteSurvey(
        surveyId: String,
        optionId: String,
        action: CustomerCenterAction,
        pathId: String,
    ) {}

    /** Called when a refund request finishes. See [CustomerCenterRefundStatus]. */
    fun customerCenterDidCompleteRefundRequest(
        productId: String,
        status: CustomerCenterRefundStatus,
    ) {}

    /** Called when the Customer Center is dismissed. */
    fun customerCenterDidDismiss() {}
}
