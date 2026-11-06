package com.superwall.sdk

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.SuperwallScope
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Starts the SDK once the dependency container exists, in this order:
 *
 * 1. Restores the cached subscription status and customer info, and merges customer info.
 * 2. Starts the listeners ([startListeners]).
 * 3. In the background: checks install-match eligibility, records the app install, then fetches
 *    config and configures identity in parallel, then matches the install when eligible.
 * 4. Calls the completion on [mainDispatcher] with the result.
 */
internal class SdkBootstrapper(
    private val scope: SuperwallScope,
    private val readCachedSubscriptionStatus: () -> SubscriptionStatus?,
    private val restoreCachedCustomerInfo: () -> Unit,
    private val setSubscriptionStatus: (SubscriptionStatus) -> Unit,
    private val mergeCustomerInfo: () -> Unit,
    private val startListeners: () -> Unit,
    private val observeAdConsent: () -> Unit,
    private val shouldMatchInstall: () -> Boolean,
    private val markInstallMatchPending: () -> Unit,
    private val recordAppInstall: () -> Unit,
    private val fetchConfiguration: suspend () -> Unit,
    private val configureIdentity: suspend () -> Unit,
    private val matchInstall: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun start(completion: ((Result<Unit>) -> Unit)?) {
        val cachedSubscriptionStatus = readCachedSubscriptionStatus() ?: SubscriptionStatus.Unknown
        restoreCachedCustomerInfo()

        setSubscriptionStatus(cachedSubscriptionStatus)

        // Trigger initial CustomerInfo merge on startup
        mergeCustomerInfo()

        startListeners()

        // Off the main thread: the first read loads the app's default preferences.
        scope.launch { observeAdConsent() }

        scope.launch {
            withErrorTracking {
                // The eligibility check runs before anything else can stall, whatever
                // the config or tracking setting says: it records that this install may
                // be matched, which a later launch relies on if this one is killed before
                // config arrives. Only the request waits for config to enable the MMP.
                val shouldMatch = shouldMatchInstall()
                if (shouldMatch) {
                    // Marked before config is fetched so a paywall can't get ahead of it.
                    markInstallMatchPending()
                }

                recordAppInstall()
                // Identity awaits config internally where it needs it, so both
                // can run concurrently. Implicitly wait for both.
                coroutineScope {
                    listOf(
                        async { fetchConfiguration() },
                        async { configureIdentity() },
                    ).awaitAll()
                }

                if (shouldMatch) {
                    matchInstall()
                }
            }.toResult().fold({
                CoroutineScope(mainDispatcher).launch {
                    completion?.invoke(Result.success(Unit))
                }
            }, {
                CoroutineScope(mainDispatcher).launch {
                    completion?.invoke(Result.failure(it))
                }
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "Superwall SDK failed to initialize - ${it.message}",
                    error = it,
                )
            })
        }
    }
}
