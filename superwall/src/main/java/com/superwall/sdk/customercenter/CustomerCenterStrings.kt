package com.superwall.sdk.customercenter

import android.content.Context
import com.superwall.sdk.R
import java.util.Locale

/**
 * Looks Customer Center strings up by key, so the logic that picks a string can be tested
 * without Android resources.
 */
internal class CustomerCenterStrings(
    private val locale: Locale = Locale.getDefault(),
    private val lookup: (String) -> String,
) {
    fun string(
        key: String,
        vararg args: Any,
    ): String {
        val format = lookup(key)
        return if (args.isEmpty()) format else String.format(locale, format, *args)
    }

    companion object {
        /** English literals, matching `values/superwall_customer_center_strings.xml`. */
        val english = CustomerCenterStrings(Locale.US) { key -> englishStrings[key] ?: key }

        /**
         * Strings backed by the SDK's localized resources, which a host app can override by
         * declaring a string of the same name. Falls back to English, then the key.
         */
        fun bundled(context: Context): CustomerCenterStrings {
            val locale =
                context.resources.configuration.locales
                    .get(0) ?: Locale.getDefault()
            return CustomerCenterStrings(locale) { key ->
                val id = resourceIds[key]
                val value = id?.let { runCatching { context.getString(it) }.getOrNull() }
                if (!value.isNullOrEmpty()) value else englishStrings[key] ?: key
            }
        }

        /**
         * Referenced explicitly rather than looked up by name, so resource shrinking in a host
         * app can see that every string is used.
         */
        private val resourceIds: Map<String, Int> =
            mapOf(
            "customer_center_management_title" to R.string.superwall_customer_center_management_title,
            "customer_center_no_purchases_title" to R.string.superwall_customer_center_no_purchases_title,
            "customer_center_no_purchases_subtitle" to R.string.superwall_customer_center_no_purchases_subtitle,
            "customer_center_close" to R.string.superwall_customer_center_close,
            "customer_center_done" to R.string.superwall_customer_center_done,
            "customer_center_cancel" to R.string.superwall_customer_center_cancel,
            "customer_center_path_restore" to R.string.superwall_customer_center_path_restore,
            "customer_center_path_manage_subscription" to R.string.superwall_customer_center_path_manage_subscription,
            "customer_center_path_manage_subscription_web" to R.string.superwall_customer_center_path_manage_subscription_web,
            "customer_center_web_manage_unavailable" to R.string.superwall_customer_center_web_manage_unavailable,
            "customer_center_path_refund" to R.string.superwall_customer_center_path_refund,
            "customer_center_path_change_plan" to R.string.superwall_customer_center_path_change_plan,
            "customer_center_path_contact_support" to R.string.superwall_customer_center_path_contact_support,
            "customer_center_survey_cancel_title" to R.string.superwall_customer_center_survey_cancel_title,
            "customer_center_survey_too_expensive" to R.string.superwall_customer_center_survey_too_expensive,
            "customer_center_survey_dont_use" to R.string.superwall_customer_center_survey_dont_use,
            "customer_center_survey_bought_by_mistake" to R.string.superwall_customer_center_survey_bought_by_mistake,
            "customer_center_renews_on_for" to R.string.superwall_customer_center_renews_on_for,
            "customer_center_renews_on" to R.string.superwall_customer_center_renews_on,
            "customer_center_expires_on" to R.string.superwall_customer_center_expires_on,
            "customer_center_expired_on" to R.string.superwall_customer_center_expired_on,
            "customer_center_free_trial_until" to R.string.superwall_customer_center_free_trial_until,
            "customer_center_billing_issue" to R.string.superwall_customer_center_billing_issue,
            "customer_center_lifetime" to R.string.superwall_customer_center_lifetime,
            "customer_center_revoked" to R.string.superwall_customer_center_revoked,
            "customer_center_purchased_on" to R.string.superwall_customer_center_purchased_on,
            "customer_center_active_via_superwall" to R.string.superwall_customer_center_active_via_superwall,
            "customer_center_price_per_period" to R.string.superwall_customer_center_price_per_period,
            "customer_center_expired" to R.string.superwall_customer_center_expired,
            "customer_center_badge_active" to R.string.superwall_customer_center_badge_active,
            "customer_center_badge_free_trial" to R.string.superwall_customer_center_badge_free_trial,
            "customer_center_badge_cancelled" to R.string.superwall_customer_center_badge_cancelled,
            "customer_center_badge_billing_issue" to R.string.superwall_customer_center_badge_billing_issue,
            "customer_center_badge_expired" to R.string.superwall_customer_center_badge_expired,
            "customer_center_badge_revoked" to R.string.superwall_customer_center_badge_revoked,
            "customer_center_badge_lifetime" to R.string.superwall_customer_center_badge_lifetime,
            "customer_center_store_web" to R.string.superwall_customer_center_store_web,
            "customer_center_store_superwall" to R.string.superwall_customer_center_store_superwall,
            "customer_center_store_other" to R.string.superwall_customer_center_store_other,
            "customer_center_section_subscriptions" to R.string.superwall_customer_center_section_subscriptions,
            "customer_center_section_purchases" to R.string.superwall_customer_center_section_purchases,
            "customer_center_section_actions" to R.string.superwall_customer_center_section_actions,
            "customer_center_account_details" to R.string.superwall_customer_center_account_details,
            "customer_center_user_id" to R.string.superwall_customer_center_user_id,
            "customer_center_copy" to R.string.superwall_customer_center_copy,
            "customer_center_copied" to R.string.superwall_customer_center_copied,
            "customer_center_original_download_date" to R.string.superwall_customer_center_original_download_date,
            "customer_center_restoring" to R.string.superwall_customer_center_restoring,
            "customer_center_restore_success_title" to R.string.superwall_customer_center_restore_success_title,
            "customer_center_restore_success_message" to R.string.superwall_customer_center_restore_success_message,
            "customer_center_restore_none_title" to R.string.superwall_customer_center_restore_none_title,
            "customer_center_restore_none_message" to R.string.superwall_customer_center_restore_none_message,
            "customer_center_refund_error" to R.string.superwall_customer_center_refund_error,
            "customer_center_update_title" to R.string.superwall_customer_center_update_title,
            "customer_center_update_message" to R.string.superwall_customer_center_update_message,
            "customer_center_update_action" to R.string.superwall_customer_center_update_action,
            "customer_center_update_continue" to R.string.superwall_customer_center_update_continue,
            "customer_center_duplicate_title" to R.string.superwall_customer_center_duplicate_title,
            "customer_center_duplicate_message" to R.string.superwall_customer_center_duplicate_message,
            "customer_center_support_subject" to R.string.superwall_customer_center_support_subject,
            "customer_center_support_body" to R.string.superwall_customer_center_support_body,
            "customer_center_no_mail_app" to R.string.superwall_customer_center_no_mail_app,
            "customer_center_detail_nothing_to_manage" to R.string.superwall_customer_center_detail_nothing_to_manage,
            "customer_center_detail_managed_through" to R.string.superwall_customer_center_detail_managed_through,
            "customer_center_detail_managed_where_bought" to R.string.superwall_customer_center_detail_managed_where_bought,
            "customer_center_store_app_store" to R.string.superwall_customer_center_store_app_store,
            )

        val englishStrings: Map<String, String> =
            mapOf(
            "customer_center_management_title" to "Manage your subscription",
            "customer_center_no_purchases_title" to "No subscriptions found",
            "customer_center_no_purchases_subtitle" to "Bought before? Restore your purchases to get them back.",
            "customer_center_close" to "Close",
            "customer_center_done" to "Done",
            "customer_center_cancel" to "Cancel",
            "customer_center_path_restore" to "Restore purchases",
            "customer_center_path_manage_subscription" to "Cancel subscription",
            "customer_center_path_manage_subscription_web" to "Manage subscription",
            "customer_center_web_manage_unavailable" to "Manage your subscription using the link in your emailed receipt.",
            "customer_center_path_refund" to "Request a refund",
            "customer_center_path_change_plan" to "Change plan",
            "customer_center_path_contact_support" to "Contact support",
            "customer_center_survey_cancel_title" to "Why are you cancelling?",
            "customer_center_survey_too_expensive" to "Too expensive",
            "customer_center_survey_dont_use" to "Don't use the app",
            "customer_center_survey_bought_by_mistake" to "Bought by mistake",
            "customer_center_renews_on_for" to "Renews on %1\$s for %2\$s",
            "customer_center_renews_on" to "Renews on %1\$s",
            "customer_center_expires_on" to "Expires on %1\$s",
            "customer_center_expired_on" to "Expired on %1\$s",
            "customer_center_free_trial_until" to "Free trial until %1\$s",
            "customer_center_billing_issue" to "Billing issue – update your payment method to keep access",
            "customer_center_lifetime" to "Lifetime access",
            "customer_center_revoked" to "Refunded",
            "customer_center_purchased_on" to "Purchased on %1\$s",
            "customer_center_active_via_superwall" to "Active",
            "customer_center_price_per_period" to "%1\$s / %2\$s",
            "customer_center_expired" to "Expired",
            "customer_center_badge_active" to "Active",
            "customer_center_badge_free_trial" to "Free trial",
            "customer_center_badge_cancelled" to "Cancelled",
            "customer_center_badge_billing_issue" to "Billing issue",
            "customer_center_badge_expired" to "Expired",
            "customer_center_badge_revoked" to "Refunded",
            "customer_center_badge_lifetime" to "Lifetime",
            "customer_center_store_web" to "Web",
            "customer_center_store_superwall" to "Superwall",
            "customer_center_store_other" to "Other",
            "customer_center_section_subscriptions" to "Subscriptions",
            "customer_center_section_purchases" to "Purchases",
            "customer_center_section_actions" to "Actions",
            "customer_center_account_details" to "Account details",
            "customer_center_user_id" to "User ID",
            "customer_center_copy" to "Copy",
            "customer_center_copied" to "Copied",
            "customer_center_original_download_date" to "Original download date",
            "customer_center_restoring" to "Restoring…",
            "customer_center_restore_success_title" to "Purchases restored",
            "customer_center_restore_success_message" to "We restored your past purchases and applied them to your account.",
            "customer_center_restore_none_title" to "No past purchases",
            "customer_center_restore_none_message" to "We couldn't find any purchases for your account. If you think this is an error, please contact support.",
            "customer_center_refund_error" to "Something went wrong requesting a refund. Please try again.",
            "customer_center_update_title" to "Update available",
            "customer_center_update_message" to "Downloading the latest version of the app may help solve the problem.",
            "customer_center_update_action" to "Update",
            "customer_center_update_continue" to "Continue",
            "customer_center_duplicate_title" to "You may have duplicate subscriptions",
            "customer_center_duplicate_message" to "You might be subscribed both on the web and through Google Play. To avoid being charged twice, cancel one of them.",
            "customer_center_support_subject" to "Support request",
            "customer_center_support_body" to "Please describe your issue or question.",
            "customer_center_no_mail_app" to "No mail app is configured on this device. You can reach us at %1\$s.",
            "customer_center_detail_nothing_to_manage" to "There's nothing to manage for this purchase.",
            "customer_center_detail_managed_through" to "Manage this subscription through %1\$s.",
            "customer_center_detail_managed_where_bought" to "Manage this subscription where you bought it.",
            "customer_center_store_app_store" to "App Store",
            )
    }
}
