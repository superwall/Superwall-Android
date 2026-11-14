package com.superwall.sdk.store.abstractions.product.receipt

import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.BillingBackedStore
import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.billing.Store
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.google.PlayPurchases
import com.superwall.sdk.customer.CustomerInfoManager
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.storage.LatestDeviceCustomerInfo
import com.superwall.sdk.storage.PlayEntitlementHoldDates
import com.superwall.sdk.storage.PurchasedProductIdsByToken
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredTransactionHistory
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class InAppPurchase(
    var productIdentifier: String,
    var fullIdentifier: String,
)

data class TransactionReceipt(
    val originalTransactionId: String,
    val purchaseToken: String,
)

enum class LatestSubscriptionOfferType {
    TRIAL,
    CODE,
    PROMOTIONAL,
    WINBACK,
}

/**
 * Builds the device's purchases, entitlements and customer info from the [Store]'s receipts.
 * It reads only neutral store types; the SDK builds it with its own store.
 */
class ReceiptManager internal constructor(
    private var delegate: ProductsFetcher?,
    private val store: Store,
    private val ioScope: IOScope = IOScope(),
    private val storage: Storage,
    internal val customerInfoManager: () -> CustomerInfoManager,
    private val entitlementProcessor: EntitlementProcessor = EntitlementProcessor(),
    private val subscriptionStatus: StoreSubscriptionStatus = DefaultSubscriptionStatus,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Kept for its public signature. [billing] is read as a [Store]; a custom
     * [subscriptionStatusProvider] is still handed the original Play purchase.
     */
    constructor(
        delegate: ProductsFetcher?,
        billing: Billing,
        ioScope: IOScope = IOScope(),
        storage: Storage,
        customerInfoManager: () -> CustomerInfoManager,
        entitlementProcessor: EntitlementProcessor = EntitlementProcessor(),
        subscriptionStatusProvider: SubscriptionStatusProvider = PlayBillingSubscriptionStatusProvider(billing),
        currentTimeMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        delegate,
        BillingBackedStore.storeOf(billing),
        ioScope,
        storage,
        customerInfoManager,
        entitlementProcessor,
        subscriptionStatusProvider,
        currentTimeMillis,
    )

    private constructor(
        delegate: ProductsFetcher?,
        source: Pair<Store, PlayPurchases>,
        ioScope: IOScope,
        storage: Storage,
        customerInfoManager: () -> CustomerInfoManager,
        entitlementProcessor: EntitlementProcessor,
        subscriptionStatusProvider: SubscriptionStatusProvider,
        currentTimeMillis: () -> Long,
    ) : this(
        delegate = delegate,
        store = source.first,
        ioScope = ioScope,
        storage = storage,
        customerInfoManager = customerInfoManager,
        entitlementProcessor = entitlementProcessor,
        subscriptionStatus = ProviderSubscriptionStatus.of(subscriptionStatusProvider) { source.second.native(it.token) },
        currentTimeMillis = currentTimeMillis,
    )

    var latestSubscriptionWillAutoRenew: Boolean? = null
    var latestSubscriptionPeriodType: LatestPeriodType? = null
    var transactionReceipts: MutableList<TransactionReceipt> = mutableListOf()

    data class PurchaseSnapshot(
        val purchases: Set<InAppPurchase>,
        val entitlementsByProductId: Map<String, List<Entitlement>>,
        val nonSubscriptions: List<NonSubscriptionTransaction>,
        val subscriptions: List<SubscriptionTransaction>,
    )

    private var _purchases: MutableSet<InAppPurchase> = mutableSetOf()
    private val latestSubscriptionState: MutableStateFlow<LatestSubscriptionState> =
        MutableStateFlow(LatestSubscriptionState.UNKNOWN)

    private data class UnmatchedPurchase(
        val fullProductId: String,
        val tokensBeforePurchase: Set<String>,
    )

    // Products bought, by raw product ID, until the store returns their purchase token.
    private val unmatchedPurchases = ConcurrentHashMap<String, UnmatchedPurchase>()

    /** Every purchase token the store has returned in this session. */
    @Volatile
    internal var knownPurchaseTokens: Set<String> = emptySet()
        private set

    val purchases: Set<String>
        get() = _purchases.map { it.productIdentifier }.toSet()

    /** The full product ids of [purchases], with the base plan when it's known. */
    val purchasedFullProductIds: Set<String>
        get() = _purchases.map { it.fullIdentifier }.toSet()

    /**
     * Loads purchased products from device receipts and builds enriched entitlements.
     *
     * This method:
     * 1. Queries all purchases from Google Play
     * 2. Builds enriched entitlements using server entitlement definitions
     * 3. Creates device CustomerInfo from the receipts
     * 4. Stores the device CustomerInfo and triggers merge with web info
     *
     * @param serverEntitlementsByProductId Map of product ID to entitlements from config
     */
    suspend fun loadPurchasedProducts(serverEntitlementsByProductId: Map<String, Set<Entitlement>>) {
        // Load purchases and build enriched entitlements
        loadPurchases(serverEntitlementsByProductId)
    }

    /**
     * Remembers the product that was just bought, since this is the only time its base plan is
     * known: the store's purchase only names the subscription.
     *
     * @param tokensBeforePurchase The [knownPurchaseTokens] from before the purchase started
     */
    internal suspend fun recordPurchasedProduct(
        fullProductId: String,
        tokensBeforePurchase: Set<String>,
    ) {
        val ids = DecomposedProductIds.from(fullProductId)
        if (ids.basePlanId == null) return
        unmatchedPurchases[ids.subscriptionId] = UnmatchedPurchase(fullProductId, tokensBeforePurchase)
        matchPurchasedProducts(store.ownedPurchases())
    }

    /**
     * Saves any recorded products against their purchase tokens.
     *
     * @return The full product ID bought with each known purchase token
     */
    private fun matchPurchasedProducts(allPurchases: List<StorePurchase>): Map<String, String> {
        val stored = storage.read(PurchasedProductIdsByToken) ?: emptyMap()
        val matched = stored.toMutableMap()
        unmatchedPurchases.forEach { (rawId, purchase) ->
            val token = newestPurchase(rawId, allPurchases)?.token ?: return@forEach
            // A token from before the purchase is one the store hasn't replaced yet, as with a
            // deferred plan change. The product bought belongs to the token that replaces it.
            if (token !in matched && token !in purchase.tokensBeforePurchase) {
                matched[token] = purchase.fullProductId
                unmatchedPurchases.remove(rawId, purchase)
            }
        }
        knownPurchaseTokens = knownPurchaseTokens + allPurchases.map { it.token }
        if (matched != stored) {
            storage.write(PurchasedProductIdsByToken, matched)
        }
        return matched
    }

    private fun newestPurchase(
        rawId: String,
        allPurchases: List<StorePurchase>,
    ): StorePurchase? = allPurchases.filter { rawId in it.productIds }.maxByOrNull { it.purchaseTime }

    private fun Map<String, Set<Entitlement>>.hasMultipleBasePlans(rawId: String): Boolean =
        keys
            .map { DecomposedProductIds.from(it) }
            .filter { it.subscriptionId == rawId }
            .distinctBy { it.basePlanType }
            .size > 1

    /**
     * Finds a product's entitlements, falling back to the closest product under the same
     * subscription when it isn't in the config, or when its base plan is unknown.
     */
    private fun Map<String, Set<Entitlement>>.entitlementsFor(productId: String): Set<Entitlement>? {
        this[productId]?.let { return it }
        val ids = DecomposedProductIds.from(productId)
        val siblings = keys.filter { DecomposedProductIds.from(it).subscriptionId == ids.subscriptionId }
        val closest =
            siblings.firstOrNull { DecomposedProductIds.from(it).basePlanType == ids.basePlanType }
                ?: siblings.firstOrNull()
        return closest?.let { this[it] }
    }

    /**
     * Removes the plan and expiry from stored transactions that may have been given the wrong
     * plan: those under a subscription with several base plans, where nothing recorded their
     * product being bought.
     */
    private fun UserTransactionHistory.withoutUnverifiedPlans(
        serverEntitlementsByProductId: Map<String, Set<Entitlement>>,
        purchasedProductIds: Collection<String>,
    ): UserTransactionHistory =
        copy(
            transactions =
                transactions.mapValues { (_, transaction) ->
                    val rawId = DecomposedProductIds.from(transaction.productId).subscriptionId
                    if (transaction.productId !in purchasedProductIds &&
                        serverEntitlementsByProductId.hasMultipleBasePlans(rawId)
                    ) {
                        transaction.copy(productId = rawId, expirationDate = null)
                    } else {
                        transaction
                    }
                },
        )

    suspend fun loadPurchases(serverEntitlementsByProductId: Map<String, Set<Entitlement>>): PurchaseSnapshot {
        // A read that failed tells us nothing, so keep the last known purchases, history and
        // customer info instead of overwriting them as if the user had no purchases.
        val allPurchases =
            store.activePurchases().getOrElse { error ->
                Logger.debug(
                    logLevel = LogLevel.warn,
                    scope = LogScope.receipts,
                    message = "Couldn't read purchases from Google Play, keeping the last known state.",
                    error = error,
                )
                return PurchaseSnapshot(
                    purchases = _purchases.toSet(),
                    entitlementsByProductId = emptyMap(),
                    nonSubscriptions = emptyList(),
                    subscriptions = emptyList(),
                )
            }

        val purchases: MutableSet<InAppPurchase> = mutableSetOf()
        val originalTransactionIds: MutableSet<String> = mutableSetOf()
        transactionReceipts.clear()

        // Note: Need to access Superwall instance through proper dependency injection
        val enableExperimentalDeviceVariables = false // TODO: Get from proper source

        // Build product ID to entitlement ID mapping
        val productIdsByEntitlementId: MutableMap<String, MutableSet<String>> = mutableMapOf()
        serverEntitlementsByProductId.forEach { (productId, entitlements) ->
            entitlements.forEach { entitlement ->
                productIdsByEntitlementId.getOrPut(entitlement.id) { mutableSetOf() }.add(productId)
            }
        }

        // Load existing transaction history to preserve data from expired purchases
        val storedHistory = storage.read(StoredTransactionHistory) ?: UserTransactionHistory()

        // 1️⃣ FIRST PASS: Collect purchases, create transaction adapters, and collect receipts
        val transactionsByEntitlement: MutableMap<String, MutableList<EntitlementTransaction>> = mutableMapOf()
        val currentTransactions: MutableList<EntitlementTransaction> = mutableListOf()

        // Build mapping from raw product ID to full product ID using server entitlements
        // This allows us to fetch products with proper base plan/offer info
        val rawProductIds = allPurchases.flatMap { it.productIds }.toSet()
        val rawToFullProductId = mutableMapOf<String, String>()
        val idsWithUnknownBasePlan = mutableSetOf<String>()
        val purchasedProductIds = matchPurchasedProducts(allPurchases)
        rawProductIds.forEach { rawId ->
            val purchasedId = newestPurchase(rawId, allPurchases)?.let { purchasedProductIds[it.token] }
            // Use the product that was bought if we know it. Otherwise find the full product ID from
            // server config that matches this raw ID, unless it could be one of several base plans.
            // Check exact match first, then fall back to prefix match
            val fullId =
                when {
                    purchasedId != null -> purchasedId
                    serverEntitlementsByProductId.hasMultipleBasePlans(rawId) -> {
                        idsWithUnknownBasePlan.add(rawId)
                        rawId
                    }
                    else ->
                        serverEntitlementsByProductId.keys.firstOrNull { it == rawId }
                            ?: serverEntitlementsByProductId.keys.firstOrNull { it.startsWith("$rawId:") }
                }
            rawToFullProductId[rawId] = fullId ?: rawId
        }
        val existingHistory =
            storedHistory.withoutUnverifiedPlans(serverEntitlementsByProductId, purchasedProductIds.values)

        // Fetch products using full product IDs so they have proper base plan/offer info
        val fullProductIds = rawToFullProductId.values.toSet()
        val productsById = store.products(fullProductIds).found.associateBy { it.productIdentifier }

        allPurchases.forEach { purchase ->
            // Create adapters for each product in the purchase
            val adapters = StorePurchaseTransaction.fromPurchase(purchase, productsById, idsWithUnknownBasePlan)

            adapters.forEach { adapter ->
                currentTransactions.add(adapter)
                purchases.add(
                    InAppPurchase(
                        productIdentifier = productsById[adapter.productId]?.productIdentifier ?: adapter.productId,
                        fullIdentifier = adapter.productId,
                    ),
                )

                // Map to entitlements - adapter.productId is now the full product ID
                val serverEntitlements = serverEntitlementsByProductId[adapter.productId]
                serverEntitlements?.forEach { entitlement ->
                    transactionsByEntitlement
                        .getOrPut(entitlement.id) { mutableListOf() }
                        .add(adapter)
                }
            }

            // Collect receipt for original transaction
            val txnId = purchase.orderId ?: purchase.token
            if (!originalTransactionIds.contains(txnId)) {
                transactionReceipts.add(
                    TransactionReceipt(
                        originalTransactionId = txnId,
                        purchaseToken = purchase.token,
                    ),
                )
                originalTransactionIds.add(txnId)
            }
        }

        storage.write(PlayEntitlementHoldDates, holdDates(transactionsByEntitlement, productsById.values))

        // 2️⃣ Merge current transactions with history and persist
        // This preserves data from expired purchases that Google Play no longer returns
        val updatedHistory = existingHistory.mergeWith(currentTransactions)
        storage.write(StoredTransactionHistory, updatedHistory)

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message =
                "Transaction history: ${currentTransactions.size} current, " +
                    "${updatedHistory.transactions.size} total stored, " +
                    "${updatedHistory.activeTransactions().size} active",
        )

        // 3️⃣ Use all historical transactions for processing (includes inactive ones for history)
        val allTransactions: List<EntitlementTransaction> = updatedHistory.allTransactions()

        // 4️⃣ Use EntitlementProcessor to process transactions
        val (nonSubscriptions, subscriptions) = entitlementProcessor.processTransactions(allTransactions)

        // 5️⃣ Build enriched entitlements using historical transactions mapped to entitlements
        // Re-map historical transactions to entitlements
        val historicalTransactionsByEntitlement: MutableMap<String, MutableList<EntitlementTransaction>> = mutableMapOf()
        allTransactions.forEach { transaction ->
            val serverEntitlements = serverEntitlementsByProductId.entitlementsFor(transaction.productId)
            serverEntitlements?.forEach { entitlement ->
                historicalTransactionsByEntitlement
                    .getOrPut(entitlement.id) { mutableListOf() }
                    .add(transaction)
            }
        }

        val entitlementsByProductId =
            entitlementProcessor.buildEntitlementsFromTransactions(
                transactionsByEntitlement = historicalTransactionsByEntitlement,
                rawEntitlementsByProductId = serverEntitlementsByProductId,
                productIdsByEntitlementId = productIdsByEntitlementId,
            )

        // 6️⃣ Update experimental device variables if enabled
        if (enableExperimentalDeviceVariables) {
            updateExperimentalDeviceVariables(allPurchases, productsById)
        }

        _purchases = purchases

        // Build device CustomerInfo from local receipts (including historical)
        val deviceCustomerInfo =
            CustomerInfo(
                subscriptions = subscriptions.reversed(),
                nonSubscriptions = nonSubscriptions.reversed(),
                userId = "", // Will be filled by merge with web info
                entitlements =
                    entitlementsByProductId.values
                        .flatten()
                        .distinctBy { it.id }
                        .toList(),
                isPlaceholder = subscriptions.isEmpty() && nonSubscriptions.isEmpty(),
            )

        // Store device CustomerInfo
        storage.write(LatestDeviceCustomerInfo, deviceCustomerInfo)

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message =
                "Built device CustomerInfo: ${deviceCustomerInfo.subscriptions.size} subs, " +
                    "${deviceCustomerInfo.nonSubscriptions.size} non-subs, " +
                    "${deviceCustomerInfo.entitlements.size} entitlements",
        )

        // Trigger merge
        customerInfoManager().updateMergedCustomerInfo()

        return PurchaseSnapshot(
            purchases = purchases,
            entitlementsByProductId = entitlementsByProductId,
            nonSubscriptions = nonSubscriptions.reversed(),
            subscriptions = subscriptions.reversed(),
        )
    }

    /**
     * Updates experimental device variables from the most recent purchase.
     */
    private suspend fun updateExperimentalDeviceVariables(
        allPurchases: List<StorePurchase>,
        productsById: Map<String, StoreProduct>,
    ) {
        val mostRecentPurchase = allPurchases.maxByOrNull { it.purchaseTime }
        if (mostRecentPurchase == null) {
            latestSubscriptionState.value = stateWithoutCurrentPurchase()
            return
        }
        val product = productsById[mostRecentPurchase.productIds.firstOrNull()] ?: return

        latestSubscriptionWillAutoRenew = subscriptionStatus.willAutoRenew(mostRecentPurchase)
        latestSubscriptionState.value = subscriptionStatus.subscriptionState(mostRecentPurchase, product)
        latestSubscriptionPeriodType = subscriptionStatus.offerType(mostRecentPurchase, product)
    }

    fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message = "Refreshing receipts",
        )
        ioScope.launch {
            latestSubscriptionState.value = lastSubscriptionProperties()
        }
    }

    init {
        refreshReceipt()
    }

    suspend fun lastSubscriptionProperties(): LatestSubscriptionState {
        // A failed read isn't "no purchases", so leave the state as it was.
        val purchases = store.activePurchases().getOrElse { return latestSubscriptionState.value }
        val purchase = purchases.maxByOrNull { it.purchaseTime } ?: return stateWithoutCurrentPurchase()

        // The state comes from the purchase alone; the product lookup is best-effort so a
        // lookup failure can't turn a paying subscriber into UNKNOWN.
        val product =
            try {
                store.products(purchase.productIds.toSet()).found.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        return subscriptionStatus.subscriptionState(purchase, product)
    }

    /**
     * Play no longer returns any purchase. If this user had a subscription before (it's in the
     * stored history), it has ended; otherwise we have nothing to go on.
     */
    private fun stateWithoutCurrentPurchase(): LatestSubscriptionState {
        val hadSubscription =
            storage.read(StoredTransactionHistory)?.transactions?.values?.any {
                it.productType == EntitlementTransactionType.AUTO_RENEWABLE ||
                    it.productType == EntitlementTransactionType.NON_RENEWABLE
            } == true
        return if (hadSubscription) LatestSubscriptionState.EXPIRED else LatestSubscriptionState.UNKNOWN
    }

    /**
     * How long each entitlement Play just reported as active can be held active through a
     * failed read: until now plus the longest subscription period that unlocks it. A renewing
     * subscription's current period can't end later than that. Lifetime unlocks and products
     * whose period is unknown get no date, so they can't hold a status up.
     */
    private fun holdDates(
        transactionsByEntitlement: Map<String, List<EntitlementTransaction>>,
        products: Collection<StoreProduct>,
    ): Map<String, Long> {
        val now = currentTimeMillis()
        val productsByFullId = products.associateBy { it.fullIdentifier }
        return transactionsByEntitlement
            .mapNotNull { (entitlementId, transactions) ->
                transactions
                    .filter {
                        it.isActive &&
                            (
                                it.productType == EntitlementTransactionType.AUTO_RENEWABLE ||
                                    it.productType == EntitlementTransactionType.NON_RENEWABLE
                            )
                    }.mapNotNull { productsByFullId[it.productId]?.subscriptionPeriod?.toMillis() }
                    .maxOrNull()
                    ?.let { period -> entitlementId to now + period }
            }.toMap()
    }

    /**
     * Rebuilds the receipt-derived state after the user scope was wiped (reset or a switch to
     * another user). Play purchases belong to the device's Play account, not the app user, so
     * they're read again and stored for the new user instead of staying empty until the next
     * purchase or restore. The old user's in-memory values are dropped first, so a failed read
     * leaves the new user with no receipt state until the next successful load.
     */
    suspend fun reloadForNewUser(serverEntitlementsByProductId: Map<String, Set<Entitlement>>) {
        _purchases = mutableSetOf()
        latestSubscriptionWillAutoRenew = null
        latestSubscriptionPeriodType = null
        latestSubscriptionState.value = LatestSubscriptionState.UNKNOWN
        loadPurchases(serverEntitlementsByProductId)
        latestSubscriptionState.value = lastSubscriptionProperties()
    }

    fun hasPurchasedProduct(productId: String): Boolean = _purchases.firstOrNull { it.productIdentifier == productId } != null

    suspend fun isEligibleForIntroOffer(storeProduct: StoreProduct): Boolean {
        // Check if user has never purchased this subscription before
        val hasExistingPurchase =
            _purchases.any { it.productIdentifier == storeProduct.productIdentifier }
        return !hasExistingPurchase
    }

    /**
     * Returns experimental device variables for backwards compatibility.
     * These are used in device variables for paywall templating.
     */
    fun experimentalProperties(): Map<String, Any> =
        buildMap {
            determinePeriodType()?.let { put("latestSubscriptionPeriodType", it) }
            latestSubscriptionWillAutoRenew?.let { put("latestSubscriptionWillAutoRenew", it) }
            put("latestSubscriptionState", latestSubscriptionState.value.name)
        }

    /**
     * Determines the period type string from the latest subscription period type.
     */
    private fun determinePeriodType(): String? =
        when (latestSubscriptionPeriodType) {
            LatestPeriodType.TRIAL -> "trial"
            LatestPeriodType.CODE -> "code"
            LatestPeriodType.SUBSCRIPTION -> "normal"
            LatestPeriodType.PROMOTIONAL -> "intro"
            LatestPeriodType.WINBACK -> "winback"
            LatestPeriodType.REVOKED -> "revoked"
            null -> null
        }
}
