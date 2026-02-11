package com.superwall.sdk.store.testmode

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.IsTestModeActiveSubscription
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallEntitlementRef
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.TestStoreUserType

class TestModeManager(
    private val storage: Storage,
) {
    var isTestMode: Boolean = false
        private set

    var testModeReason: TestModeReason? = null
        private set

    var products: List<SuperwallProduct> = emptyList()
        private set

    var testEntitlementIds: MutableSet<String> = mutableSetOf()
        private set

    var freeTrialOverride: FreeTrialOverride = FreeTrialOverride.UseDefault

    var overriddenSubscriptionStatus: SubscriptionStatus? = null
        private set

    internal var testProductsByFullId: Map<String, StoreProduct> = emptyMap()
        private set

    fun evaluateTestMode(
        config: Config,
        bundleId: String,
        appUserId: String?,
        aliasId: String?,
    ) {
        val testUsers = config.testModeUserIds
        val bundleIdConfig = config.bundleIdConfig

        // Check bundle ID mismatch
        if (bundleIdConfig != null && bundleIdConfig.isNotEmpty() && bundleIdConfig != bundleId) {
            isTestMode = true
            testModeReason = TestModeReason.ApplicationIdMismatch(expected = bundleIdConfig, actual = bundleId)
            Logger.debug(
                LogLevel.info,
                LogScope.superwallCore,
                "Test mode activated: ${testModeReason?.description}",
            )
            return
        }

        // Check user ID match
        if (testUsers != null) {
            for (testUser in testUsers) {
                val match =
                    when (testUser.type) {
                        TestStoreUserType.UserId -> appUserId == testUser.value
                        TestStoreUserType.AliasId -> aliasId == testUser.value
                    }
                if (match) {
                    isTestMode = true
                    testModeReason = TestModeReason.ConfigMatch(matchedId = testUser.value)
                    Logger.debug(
                        LogLevel.info,
                        LogScope.superwallCore,
                        "Test mode activated: ${testModeReason?.description}",
                    )
                    return
                }
            }
        }

        // No match - not test mode
        isTestMode = false
        testModeReason = null
    }

    fun setProducts(products: List<SuperwallProduct>) {
        this.products = products
    }

    fun setTestProducts(productsByFullId: Map<String, StoreProduct>) {
        this.testProductsByFullId = productsByFullId
    }

    fun fakePurchase(entitlementRefs: List<SuperwallEntitlementRef>) {
        val ids = entitlementRefs.map { it.identifier }
        testEntitlementIds.addAll(ids)
        storage.write(IsTestModeActiveSubscription, testEntitlementIds.isNotEmpty())
    }

    fun setEntitlements(ids: Set<String>) {
        testEntitlementIds.clear()
        testEntitlementIds.addAll(ids)
        storage.write(IsTestModeActiveSubscription, testEntitlementIds.isNotEmpty())
    }

    fun resetEntitlements() {
        testEntitlementIds.clear()
        storage.write(IsTestModeActiveSubscription, false)
    }

    fun shouldShowFreeTrial(hasFreeTrial: Boolean): Boolean =
        when (freeTrialOverride) {
            FreeTrialOverride.UseDefault -> hasFreeTrial
            FreeTrialOverride.ForceAvailable -> true
            FreeTrialOverride.ForceUnavailable -> false
        }

    fun clearTestModeState() {
        isTestMode = false
        testModeReason = null
        products = emptyList()
        testProductsByFullId = emptyMap()
        testEntitlementIds.clear()
        freeTrialOverride = FreeTrialOverride.UseDefault
        overriddenSubscriptionStatus = null
        storage.delete(IsTestModeActiveSubscription)
    }

    fun buildSubscriptionStatus(): SubscriptionStatus =
        if (testEntitlementIds.isEmpty()) {
            SubscriptionStatus.Inactive
        } else {
            SubscriptionStatus.Active(
                testEntitlementIds
                    .map { Entitlement(it) }
                    .toSet(),
            )
        }

    fun setOverriddenSubscriptionStatus(status: SubscriptionStatus?) {
        overriddenSubscriptionStatus = status
    }

    fun entitlementsForProduct(product: SuperwallProduct): List<SuperwallEntitlementRef> = product.entitlements

    fun allEntitlements(): Set<String> = products.flatMap { it.entitlements.map { e -> e.identifier } }.toSet()
}
