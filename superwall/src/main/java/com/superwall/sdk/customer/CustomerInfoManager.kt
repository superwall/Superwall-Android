package com.superwall.sdk.customer

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.customer.merge
import com.superwall.sdk.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Manages merging of device and web CustomerInfo sources.
 *
 * This class is responsible for:
 * - Reading device CustomerInfo (built from Google Play receipts)
 * - Reading web CustomerInfo (fetched from Superwall backend)
 * - Merging both sources using priority-based rules
 * - Updating the public CustomerInfo flow that the SDK exposes
 * - Persisting the merged result to storage
 *
 * The merge happens whenever:
 * - Device receipts are refreshed (via ReceiptManager)
 * - Web entitlements are fetched (via WebPaywallRedeemer)
 * - SDK initialization completes
 */
class CustomerInfoManager(
    private val storage: Storage,
    private val customerInfoFlow: MutableStateFlow<CustomerInfo>,
    private val ioScope: IOScope,
) {
    /**
     * Merges device and web CustomerInfo and updates the public CustomerInfo flow.
     *
     * This method:
     * 1. Reads the latest device CustomerInfo from storage (built from Google Play receipts)
     * 2. Reads the latest web CustomerInfo from storage (fetched from backend)
     * 3. Merges them using priority rules (see CustomerInfo.merge())
     * 4. Persists the merged result to storage
     * 5. Updates the public flow so listeners get the latest merged state
     *
     * The merge is performed asynchronously on the IO scope to avoid blocking the caller.
     */
    fun updateMergedCustomerInfo() {
        ioScope.launch {
            // Get device CustomerInfo (from Google Play receipts)
            val deviceInfo = storage.read(LatestDeviceCustomerInfo) ?: CustomerInfo.empty()

            // Get web CustomerInfo (from Superwall backend)
            val webInfo = storage.read(LatestWebCustomerInfo) ?: CustomerInfo.empty()

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message =
                    "Merging CustomerInfo - Device: ${deviceInfo.subscriptions.size} subs, " +
                        "Web: ${webInfo.subscriptions.size} subs",
            )

            // Merge with optimization: skip merge if one source is blank
            val merged =
                when {
                    deviceInfo.isBlank && webInfo.isBlank -> CustomerInfo.empty()
                    deviceInfo.isBlank -> webInfo
                    webInfo.isBlank -> deviceInfo
                    else -> deviceInfo.merge(webInfo) // Apply priority-based merging
                }

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message =
                    "Merged CustomerInfo - Total: ${merged.subscriptions.size} subs, " +
                        "${merged.entitlements.size} entitlements",
            )

            // Store merged result for caching/offline access
            storage.write(LatestCustomerInfo, merged)

            // Update public flow so listeners get the new merged state
            customerInfoFlow.value = merged
        }
    }
}
