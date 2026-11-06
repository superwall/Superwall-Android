package com.superwall.sdk.web

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Redemptions
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Redemptions.RedemptionState
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.customer.CustomerInfoManager
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.asEither
import com.superwall.sdk.misc.fold
import com.superwall.sdk.models.config.WebToAppConfig
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.entitlements.TransactionReceipt
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.ErrorInfo
import com.superwall.sdk.models.internal.RedemptionOwnership
import com.superwall.sdk.models.internal.RedemptionOwnershipType
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.storage.LastWebEntitlementsFetchDate
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.LatestWebCustomerInfo
import com.superwall.sdk.storage.RedeemedInstallReferrerCode
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.TrackedWebTrialCodes
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val WEB_TRIAL_NOTIFICATION_TIMEOUT_MILLIS = 30_000L

/**
 * How often entitlements are fetched when the config has no web checkout settings
 * (`web2app_config`) to say. Apps whose purchases are reported by their own server, such as custom
 * store products, rely on it for refunds, expiries and renewals to reach a running app: a shorter
 * interval shows them sooner and costs a request per active user each time.
 */
internal const val DEFAULT_ENTITLEMENTS_MAX_AGE_MS = 15 * 60 * 1000L

/**
 * How long fetched entitlements stay fresh: the web checkout settings' max age when the config has
 * one, otherwise [DEFAULT_ENTITLEMENTS_MAX_AGE_MS].
 */
internal fun entitlementsMaxAgeMs(webToAppConfig: WebToAppConfig?): Long =
    webToAppConfig?.entitlementsMaxAgeMs?.takeIf { it > 0 } ?: DEFAULT_ENTITLEMENTS_MAX_AGE_MS

@Suppress("EXPOSED_PARAMETER_TYPE")
class WebPaywallRedeemer(
    private val context: Context,
    private val ioScope: IOScope,
    internal val deepLinkReferrer: CheckForReferral,
    private val network: Network,
    private val storage: Storage,
    internal val customerInfoManager: CustomerInfoManager,
    private val factory: Factory,
) {
    interface Factory {
        fun willRedeemLink()

        fun didRedeemLink(redemptionResult: RedemptionResult)

        fun maxAge(): Long

        fun getActiveDeviceEntitlements(): Set<Entitlement>

        fun getUserId(): UserId?

        fun getDeviceId(): DeviceVendorId

        fun getAliasId(): String?

        suspend fun track(event: Trackable)

        fun internallySetSubscriptionStatus(status: SubscriptionStatus)

        fun setWebEntitlements(entitlements: Set<Entitlement>)

        suspend fun isPaywallVisible(): Boolean

        suspend fun triggerRestoreInPaywall()

        fun currentPaywallEntitlements(): Set<Entitlement>

        fun getPaywallInfo(): PaywallInfo

        fun trackRestorationFailed(message: String)

        fun isWebToAppEnabled(): Boolean

        suspend fun receipts(): List<TransactionReceipt>

        fun getExternalAccountId(): String

        fun getIntegrationProps(): Map<String, Any>

        fun closePaywallIfExists()

        fun isPaymentSheetOpen(): Boolean

        suspend fun scheduleTrialNotifications(notifications: List<LocalNotification>) {}

        fun currentTimeMillis(): Long = System.currentTimeMillis()

        /** Whether the app is in the foreground. Polls skip their fetch while it isn't. */
        fun isInForeground(): Boolean = true
    }

    private var pollingJob: Job? = null
    private var redemptionJob: Job? = null
    private val isPolling = AtomicBoolean(false)

    // Responses that carry entitlements are applied in order, so an older one never replaces a
    // newer one's. A fetch or background redeem takes its place when it's sent: a slow post-purchase
    // redeem can't overwrite a refresh requested after it. A code redemption takes its place when
    // it arrives, because redeeming the code changed the entitlements a fetch in flight may have read.
    private val responseOrder = AtomicLong()
    private val lastAppliedResponse = AtomicLong()
    private val applyMutex = Mutex()
    // Code redemptions launch independently on IOScope; hold this across track() so two
    // overlapping same-code calls cannot both observe an empty set and emit twice.
    private val trialTrackingMutex = Mutex()

    private suspend fun track(event: Trackable) = factory.track(event)

    private fun convertToJsonElement(value: Any?): JsonElement? =
        when (value) {
            null -> null
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> ->
                buildJsonObject {
                    value.forEach { (k, v) ->
                        if (k is String && v != null) {
                            val jsonElement = convertToJsonElement(v)
                            if (jsonElement != null) {
                                put(k, jsonElement)
                            }
                        }
                    }
                }

            is Collection<*> ->
                JsonArray(
                    value.mapNotNull { convertToJsonElement(it) },
                )

            else -> JsonPrimitive(value.toString())
        }

    init {
        ioScope.launch {
            if (factory.isWebToAppEnabled()) {
                checkForRefferal()
            }
        }
    }

    suspend fun checkForRefferal() =
        asEither {
            deepLinkReferrer
                .checkForReferral()
                .fold(
                    onSuccess = { code ->
                        // Play keeps the install referrer for the life of the install, so
                        // only redeem its code on the first launch that reads it.
                        if (storage.read(RedeemedInstallReferrerCode) == code) {
                            return@fold
                        }
                        redeem(RedeemType.Code(code))
                        // Only a redemption the server answered is stored with the code, so a
                        // failed request is retried on the next launch.
                        val redeemed =
                            storage.read(LatestRedemptionResponse)?.allCodes?.any { it.code == code } == true
                        if (redeemed) {
                            storage.write(RedeemedInstallReferrerCode, code)
                        }
                    },
                    onFailure = { throw it },
                )
        }

    sealed interface RedeemType {
        val description: String
        val code: String?

        data class Code(
            override val code: String,
        ) : RedeemType {
            override val description: String = "CODE"
        }

        object Existing : RedeemType {
            override val description: String = "EXISTING_CODES"
            override val code: String? = null
        }

        object IntegrationAttributes : RedeemType {
            override val code: String?
                get() = null
            override val description: String = "INTEGRATION_ATTRIBUTES"
        }
    }

    suspend fun redeem(redemption: RedeemType) {
        //     delay(10.seconds)
        Logger.debug(
            LogLevel.debug,
            LogScope.webEntitlements,
            "Starting redemption of type ${
                when (redemption) {
                    is RedeemType.Existing -> "Existing"
                    is RedeemType.Code -> "Code: ${redemption.code}"
                    is RedeemType.IntegrationAttributes -> "Integration attributes"
                }
            }",
        )
        // We want to keep track of the codes that have been retrieved by the user
        val latestResponse = storage.read(LatestRedemptionResponse)
        val allCodes = latestResponse?.allCodes?.toMutableList() ?: mutableListOf()
        var isFirstRedemption = true
        if (redemption is RedeemType.Code) {
            factory.willRedeemLink()

            if (allCodes.isNotEmpty()) {
                isFirstRedemption = !allCodes.map { it.code }.contains(redemption.code)
            }
            allCodes.add(Redeemable(redemption.code, isFirstRedemption))

            if (factory.isPaywallVisible() && !factory.isPaymentSheetOpen()) {
                track(
                    InternalSuperwallEvent.Restore(
                        InternalSuperwallEvent.Restore.State.Start,
                        factory.getPaywallInfo(),
                    ),
                )
            }
        }
        track(Redemptions(RedemptionState.Start, redemption))
        val requestedFor = currentUser()
        val sentAt = if (redemption is RedeemType.Code) null else responseOrder.incrementAndGet()
        network
            .redeemToken(
                allCodes,
                factory.getUserId(),
                factory.getAliasId(),
                factory.getDeviceId(),
                factory.receipts(),
                factory.getExternalAccountId(),
                factory.getIntegrationProps().takeIf { it.isNotEmpty() }?.let { props ->
                    props
                        .mapValues { (_, value) -> convertToJsonElement(value) }
                        .filterValues { it != null }
                        .mapValues { (_, value) -> value!! }
                },
            ).fold(
                onSuccess = {
                    // A background redeem answers for the user it was sent for. If the user changed
                    // meanwhile (identify or reset), its entitlements aren't theirs, so drop it; the
                    // identity change starts a redeem of its own. A code redemption is kept, since the
                    // code was redeemed regardless.
                    if (redemption !is RedeemType.Code && currentUser() != requestedFor) {
                        Logger.debug(
                            logLevel = LogLevel.debug,
                            scope = LogScope.webEntitlements,
                            message = "Dropping a redemption response for a user who is no longer current",
                        )
                        return@fold
                    }
                    // Apply access before trial handling can wait for notification permission.
                    saveRedemption(sentAt ?: responseOrder.incrementAndGet(), it)
                    track(
                        Redemptions(
                            RedemptionState.Complete,
                            redemption,
                        ),
                    )
                    val codeResult =
                        if (redemption is RedeemType.Code) {
                            it.codes.firstOrNull { result -> result.code == redemption.code }
                                ?: RedemptionResult.Error(redemption.code, ErrorInfo("Redemption failed, code not returned"))
                        } else {
                            null
                        }
                    when (redemption) {
                        is RedeemType.Code -> {
                            Logger.debug(
                                logLevel = LogLevel.debug,
                                scope = LogScope.webEntitlements,
                                message = "Entitlement redemption done",
                                info =
                                    mapOf(
                                        "code" to (
                                            (redemption as? RedeemType.Code)?.code
                                                ?: ""
                                        ),
                                    ),
                            )

                            if (codeResult != null) {
                                // Restoration can dismiss the paywall too, so finish trial work first.
                                handleTrialRedemption(codeResult)
                                if (factory.isPaywallVisible() && !factory.isPaymentSheetOpen()) {
                                    if (it.customerInfo?.entitlements?.map { it.id }?.containsAll(
                                            factory.currentPaywallEntitlements().map { it.id },
                                        ) ?: false
                                    ) {
                                        factory.triggerRestoreInPaywall()
                                    } else {
                                        factory.trackRestorationFailed("Failed to restore subscriptions from the web")
                                    }
                                }
                            }
                        }

                        RedeemType.Existing, RedeemType.IntegrationAttributes -> {
                            // NO-OP
                        }
                    }
                    if (codeResult != null) {
                        factory.closePaywallIfExists()
                        factory.didRedeemLink(codeResult)
                    }

                    // Notify the delegate that the redemption succeeded, unless the code has not been redeemed
                },
                onFailure = {
                    track(
                        Redemptions(
                            RedemptionState.Fail,
                            redemption,
                        ),
                    )
                    if (redemption is RedeemType.Code) {
                        val errorMessage =
                            it.localizedMessage ?: it.message
                                ?: "Redemption failed, error unknown"
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.webEntitlements,
                            message = "Failed to redeem purchase token",
                            info = mapOf("code" to redemption.code),
                            error = it,
                        )
                        // Notify the delegate that the redemption failed
                        factory.didRedeemLink(
                            RedemptionResult.Error(
                                code = redemption.code ?: "",
                                error =
                                    ErrorInfo(
                                        errorMessage,
                                    ),
                            ),
                        )
                        if (factory.isPaywallVisible() && !factory.isPaymentSheetOpen()) {
                            factory.trackRestorationFailed(errorMessage)
                        }
                    }
                },
            )
        startPolling()
    }

    private fun currentUser(): Pair<UserId?, String?> = factory.getUserId() to factory.getAliasId()

    /**
     * Saves a redemption response and applies its entitlements. When a response requested after
     * [order] has already been applied, the redemption is still saved, since its codes are needed and
     * on a fresh install it creates the cache later fetches update, but it keeps the newer customer info.
     */
    private suspend fun saveRedemption(
        order: Long,
        response: WebRedemptionResponse,
    ) = applyMutex.withLock {
        val isNewest = order >= lastAppliedResponse.get()
        val saved =
            if (isNewest) {
                lastAppliedResponse.accumulateAndGet(order, ::maxOf)
                response
            } else {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webEntitlements,
                    message = "Kept the newer entitlements over those of an older redemption response",
                )
                val newerInfo = storage.read(LatestRedemptionResponse)?.customerInfo ?: storage.read(LatestWebCustomerInfo)
                response.copy(customerInfo = newerInfo ?: response.customerInfo)
            }
        storage.write(LatestRedemptionResponse, saved)
        val active =
            saved.customerInfo
                ?.entitlements
                ?.filter { it.isActive }
                ?.toSet()
                .orEmpty()
        factory.setWebEntitlements(active)
        factory.internallySetSubscriptionStatus(SubscriptionStatus.Active(active + factory.getActiveDeviceEntitlements()))
    }

    /** Runs [apply] unless a response requested after [order] has already been applied. */
    private suspend fun applyInOrder(
        order: Long,
        apply: suspend () -> Unit,
    ): Boolean =
        applyMutex.withLock {
            if (order < lastAppliedResponse.get()) return@withLock false
            lastAppliedResponse.accumulateAndGet(order, ::maxOf)
            apply()
            true
        }

    private suspend fun handleTrialRedemption(result: RedemptionResult) {
        val product = (result as? RedemptionResult.Success)?.redemptionInfo?.paywallInfo?.product ?: return
        if (product.trialPeriodDays <= 0 || !factory.isPaywallVisible()) return
        // Match iOS: eligibility and attribution use the active paywall's presentation snapshot;
        // purchased product details come from the web checkout response.
        val paywallInfo = factory.getPaywallInfo()
        if (!paywallInfo.isFreeTrialAvailable) return

        attemptTrialSideEffect("track web free trial start") {
            trialTrackingMutex.withLock {
                val trackedCodes = storage.read(TrackedWebTrialCodes).orEmpty()
                if (result.code !in trackedCodes) {
                    track(InternalSuperwallEvent.FreeTrialStart(paywallInfo, StoreProduct(RedemptionStoreProduct(product))))
                    storage.write(TrackedWebTrialCodes, trackedCodes + result.code)
                }
            }
        }
        val reminders = trialReminders(paywallInfo, product)
        if (reminders.isEmpty()) return
        attemptTrialSideEffect("schedule web trial notifications") {
            withTimeoutOrNull(WEB_TRIAL_NOTIFICATION_TIMEOUT_MILLIS) {
                factory.scheduleTrialNotifications(reminders)
            }
        }
    }

    private fun trialReminders(
        paywallInfo: PaywallInfo,
        product: PaywallProduct,
    ): List<LocalNotification> =
        paywallInfo.localNotifications.mapNotNull { notification ->
            if (notification.type != LocalNotificationType.TrialStarted) return@mapNotNull null
            webTrialReminderDelay(product, notification.delay, factory.currentTimeMillis())?.let { delay ->
                notification.copy(id = "${paywallInfo.identifier}_${notification.type.raw}", delay = delay)
            }
        }

    private suspend fun attemptTrialSideEffect(
        description: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.webEntitlements,
                message = "Failed to $description",
                error = e,
            )
        }
    }

    suspend fun checkForWebEntitlements(
        userId: UserId?,
        deviceId: DeviceVendorId,
    ) = withErrorTracking {
        val webCustomerInfo = fetchWebCustomerInfo(userId, deviceId) ?: entitlementsOnlyCustomerInfo(userId)
        saveFetchedCustomerInfo(webCustomerInfo)
        webCustomerInfo.entitlements.toSet()
    }

    /**
     * Fetches the user's web customer info without saving it, or `null` when the backend returned
     * none. A failed request throws, so it is never mistaken for a user without entitlements.
     */
    private suspend fun fetchWebCustomerInfo(
        userId: UserId?,
        deviceId: DeviceVendorId,
    ): CustomerInfo? {
        val webEntitlementsResult =
            if (userId == null) {
                network.webEntitlementsByDeviceID(deviceId)
            } else {
                network.webEntitlementsByUserId(userId, deviceId)
            }
        val webEntitlementsResponse =
            when (webEntitlementsResult) {
                is Either.Success -> webEntitlementsResult.value
                is Either.Failure -> throw webEntitlementsResult.error
            }

        val webCustomerInfo = webEntitlementsResponse.customerInfo
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.customerInfo,
            message =
                if (webCustomerInfo == null) {
                    "Backend didn't return customerInfo, constructing from entitlements"
                } else {
                    "Fetched web CustomerInfo: ${webCustomerInfo.subscriptions.size} subs, " +
                        "${webCustomerInfo.nonSubscriptions.size} non-subs"
                },
        )
        return webCustomerInfo
    }

    /** The customer info of a backend that returns none, kept for backward compatibility. */
    private fun entitlementsOnlyCustomerInfo(userId: UserId?) =
        CustomerInfo(
            subscriptions = emptyList(),
            nonSubscriptions = emptyList(),
            userId = userId?.value ?: "",
            entitlements = emptyList(),
            isPlaceholder = false,
        )

    private fun saveFetchedCustomerInfo(webCustomerInfo: CustomerInfo) {
        storage.write(LatestWebCustomerInfo, webCustomerInfo)
        storage.write(LastWebEntitlementsFetchDate, System.currentTimeMillis())
    }

    fun clear(ownership: RedemptionOwnershipType) {
        val latestResponse = storage.read(LatestRedemptionResponse)
        // User has not redeemed anything yet
        // Find success codes that belong to the user
        val userCodesToRemove =
            latestResponse
                ?.codes
                ?.filterIsInstance<RedemptionResult.Success>()
                ?.filter {
                    when (ownership) {
                        RedemptionOwnershipType.AppUser -> it.redemptionInfo.ownership is RedemptionOwnership.AppUser
                        RedemptionOwnershipType.Device -> it.redemptionInfo.ownership is RedemptionOwnership.Device
                    }
                }

        // Create a new response with the filtered codes and entitlements
        // (we do not need to check source here as they are all web)

        val withUserCodesRemoved =
            latestResponse?.copy(
                codes = latestResponse.codes.filterNot { it in userCodesToRemove.orEmpty() },
            )

        // Get active entitlements that remain after removing web sources or ones from the web
        if (withUserCodesRemoved != null) {
            storage.write(LatestRedemptionResponse, withUserCodesRemoved)
            factory.setWebEntitlements(
                withUserCodesRemoved.customerInfo
                    ?.entitlements
                    ?.filter { it.isActive }
                    ?.toSet() ?: emptySet(),
            )
        } else {
            factory.setWebEntitlements(emptySet())
        }
        factory.internallySetSubscriptionStatus(
            SubscriptionStatus.Active(
                factory.getActiveDeviceEntitlements(),
            ),
        )
        // Responses already in flight were requested for what was just cleared.
        lastAppliedResponse.accumulateAndGet(responseOrder.incrementAndGet(), ::maxOf)
        redemptionJob?.cancel()
        redemptionJob =
            ioScope.launch {
                redeem(RedeemType.Existing)
            }
    }

    /**
     * Fetches the customer's web entitlements once, as each poll does, and applies them: the
     * merged customer info and subscription status are updated when they've changed. For when
     * something outside the app, such as a web management page, may have changed them and the
     * next poll is too far off. Makes a network request, so call it off the main thread.
     */
    suspend fun refreshWebEntitlements() {
        val sentAt = responseOrder.incrementAndGet()
        val requestedFor = currentUser()
        withErrorTracking { fetchWebCustomerInfo(factory.getUserId(), factory.getDeviceId()) }
            .fold(
                onFailure = {
                    it.printStackTrace()
                },
                onSuccess = { fetched ->
                    if (currentUser() != requestedFor) {
                        Logger.debug(
                            logLevel = LogLevel.debug,
                            scope = LogScope.webEntitlements,
                            message = "Dropping fetched entitlements for a user who is no longer current",
                        )
                        return@fold
                    }
                    val webCustomerInfo = fetched ?: entitlementsOnlyCustomerInfo(requestedFor.first)
                    val newEntitlements = webCustomerInfo.entitlements.toSet()
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.webEntitlements,
                        message = "Discovered web entitlements",
                        info =
                            mapOf("entitlements" to newEntitlements.map { it.id }.joinToString(",")),
                    )

                    val applied =
                        applyInOrder(sentAt) {
                            saveFetchedCustomerInfo(webCustomerInfo)
                            applyFetchedEntitlements(newEntitlements, fetched)
                        }
                    if (!applied) {
                        Logger.debug(
                            logLevel = LogLevel.debug,
                            scope = LogScope.webEntitlements,
                            message = "Skipped fetched entitlements older than the entitlements already applied",
                        )
                    }
                },
            )
    }

    /**
     * Fetches entitlements unless they were fetched within the max age. Run when the app comes to
     * the foreground and by the poll timer. A poll that starts while another is fetching is skipped.
     */
    internal suspend fun pollWebEntitlements() {
        val lastFetch = storage.read(LastWebEntitlementsFetchDate)
        if (lastFetch != null && factory.currentTimeMillis() - lastFetch < factory.maxAge()) return
        if (!isPolling.compareAndSet(false, true)) return
        try {
            refreshWebEntitlements()
        } finally {
            isPolling.set(false)
        }
    }

    /**
     * Saves fetched entitlements into the redemption cache and publishes them. When the backend
     * returned the whole [fetchedCustomerInfo], it replaces the cached one, so its subscriptions
     * and purchases are current too: an external purchase controller's customer info is built from
     * that cache. The cached redemption codes are kept either way.
     */
    private fun applyFetchedEntitlements(
        newEntitlements: Set<Entitlement>,
        fetchedCustomerInfo: CustomerInfo?,
    ) {
        val latestRedeemResponse =
            storage.read(LatestRedemptionResponse)
        val existingWebEntitlements =
            latestRedeemResponse?.customerInfo?.entitlements?.toSet() ?: emptySet()

        // Update customerInfo with new entitlements if response exists
        if (latestRedeemResponse != null) {
            val updatedCustomerInfo =
                fetchedCustomerInfo ?: latestRedeemResponse.customerInfo?.copy(
                    entitlements = newEntitlements.toList(),
                ) ?: CustomerInfo(
                    subscriptions = emptyList(),
                    nonSubscriptions = emptyList(),
                    userId = "",
                    entitlements = newEntitlements.toList(),
                    isPlaceholder = false,
                )
            val updatedResponse =
                latestRedeemResponse.copy(
                    customerInfo = updatedCustomerInfo,
                )
            storage.write(
                LatestRedemptionResponse,
                updatedResponse,
            )
            // Publish only what was persisted, so the cached web
            // entitlements always match what a cold start restores.
            factory.setWebEntitlements(
                newEntitlements.filter { it.isActive }.toSet(),
            )
        }

        // Trigger CustomerInfo merge
        customerInfoManager.updateMergedCustomerInfo()

        if (existingWebEntitlements.filter { it.isActive } != newEntitlements.filter { it.isActive }) {
            factory.internallySetSubscriptionStatus(
                SubscriptionStatus.Active(
                    newEntitlements.filter { it.isActive }.toSet() + factory.getActiveDeviceEntitlements(),
                ),
            )
        }
    }

    private fun startPolling(maxAge: Long = factory.maxAge()) {
        pollingJob?.cancel()
        pollingJob =
            (ioScope + Dispatchers.IO).launch {
                // A redemption just ran, so fetch now; later rounds skip while the app is in the
                // background, where a fetch only costs a request nobody sees.
                refreshWebEntitlements()
                while (true) {
                    delay(maxAge)
                    if (factory.isInForeground()) pollWebEntitlements()
                }
            }
    }
}
