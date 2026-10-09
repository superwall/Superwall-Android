package com.superwall.sdk.store.testmode

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.IsTestModeActiveSubscription
import com.superwall.sdk.storage.StoredTestModeSettings
import com.superwall.sdk.storage.TestModeSettings
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import com.superwall.sdk.store.testmode.ui.EntitlementSelection
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.seconds

sealed class TestModeState {
    data object Inactive : TestModeState()

    data class Active(
        val reason: TestModeReason,
        val session: TestModeSessionData = TestModeSessionData(),
    ) : TestModeState()

    /** Per-activation working set. Immutable — every mutation produces a copy. */
    data class TestModeSessionData(
        val products: List<SuperwallProduct> = emptyList(),
        val testProductsByFullId: Map<String, StoreProduct> = emptyMap(),
        val entitlementIds: Set<String> = emptySet(),
        val entitlementSelections: List<EntitlementSelection> = emptyList(),
        val freeTrialOverride: FreeTrialOverride = FreeTrialOverride.UseDefault,
        val overriddenSubscriptionStatus: SubscriptionStatus? = null,
        /** Completed once the product catalog refresh finishes (success or failure). Carried through `copy()`. */
        val productsLoaded: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val sessionOrNull: TestModeSessionData? get() = (this as? Active)?.session

    internal sealed class Updates(
        override val reduce: (TestModeState) -> TestModeState,
    ) : Reducer<TestModeState> {
        /**
         * Activate with [reason]. An already-active session is preserved (products,
         * free-trial override); when the reason changes, entitlement selections and
         * the overridden status are cleared so the new reason starts clean.
         */
        data class SetActive(val reason: TestModeReason) : Updates({ state ->
            when {
                state !is Active -> Active(reason)
                state.reason == reason -> state
                else ->
                    state.copy(
                        reason = reason,
                        session =
                            state.session.copy(
                                entitlementIds = emptySet(),
                                entitlementSelections = emptyList(),
                                overriddenSubscriptionStatus = null,
                            ),
                    )
            }
        })

        object SetInactive : Updates({ Inactive })

        /** Mutate the active session. No-op when state is Inactive. */
        data class UpdateSession(
            val transform: (TestModeSessionData) -> TestModeSessionData,
        ) : Updates({ state ->
            when (state) {
                is Active -> state.copy(session = transform(state.session))
                Inactive -> state
            }
        })
    }

    internal sealed class Actions(
        override val execute: suspend TestModeContext.() -> Unit,
    ) : TypedAction<TestModeContext> {
        /** Refresh test-product catalog from the network. */
        object RefreshProducts : Actions({
            try {
                getSuperwallProducts().fold(
                    onSuccess = { response ->
                        val androidProducts =
                            response.data.filter {
                                it.platform == SuperwallProductPlatform.ANDROID && it.price != null
                            }
                        val productsByFullId =
                            androidProducts.associate { superwallProduct ->
                                val testProduct = TestStoreProduct(superwallProduct)
                                superwallProduct.identifier to StoreProduct(testProduct)
                            }
                        update(
                            Updates.UpdateSession {
                                it.copy(
                                    products = androidProducts,
                                    testProductsByFullId = productsByFullId,
                                )
                            },
                        )
                        Logger.debug(
                            LogLevel.info,
                            LogScope.superwallCore,
                            "Test mode: loaded ${androidProducts.size} products",
                        )
                    },
                    onFailure = { error ->
                        Logger.debug(
                            LogLevel.error,
                            LogScope.superwallCore,
                            "Test mode: failed to fetch products - ${error.message}",
                        )
                    },
                )
            } finally {
                state.value.sessionOrNull?.productsLoaded?.complete(Unit)
            }
        })

        /** Refresh products + (when newly activated) present the modal. */
        data class Activate(
            val config: Config,
            val justActivated: Boolean,
        ) : Actions(exec@{
            immediate(RefreshProducts)

            if (!justActivated) return@exec

            // ---- Present modal ------------------------------------------
            val activity =
                activityTracker()?.getCurrentActivity()
                    ?: activityProvider()?.getCurrentActivity()
                    ?: activityTracker()?.awaitActivity(10.seconds)

            if (activity == null) {
                Logger.debug(
                    LogLevel.warn,
                    LogScope.superwallCore,
                    "Test mode modal could not be presented: no activity available. Setting default subscription status.",
                )
                val status = buildSubscriptionStatus(state.value)
                update(Updates.UpdateSession { it.copy(overriddenSubscriptionStatus = status) })
                entitlements?.setSubscriptionStatus(status)
                return@exec
            }

            track(InternalSuperwallEvent.TestModeModal(InternalSuperwallEvent.TestModeModal.State.Open))

            val reason =
                (state.value as? Active)?.reason?.description ?: "Test mode activated"
            val allEntitlements =
                config.productsV3
                    ?.flatMap { it.entitlements.map { e -> e.id } }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()

            val savedSettings = storage.read(StoredTestModeSettings)

            val result =
                showModal(
                    activity,
                    reason,
                    hasExternalPurchaseController(),
                    allEntitlements,
                    apiKey(),
                    dashboardBaseUrl(),
                    savedSettings,
                )

            val newSelections = result.entitlements
            val newIds =
                newSelections
                    .filter { it.state.isActive }
                    .map { it.identifier }
                    .toSet()

            update(
                Updates.UpdateSession { session ->
                    session.copy(
                        freeTrialOverride = result.freeTrialOverride,
                        entitlementSelections = newSelections,
                        entitlementIds = newIds,
                    )
                },
            )
            storage.write(IsTestModeActiveSubscription, newIds.isNotEmpty())
            storage.write(
                StoredTestModeSettings,
                TestModeSettings(
                    entitlementSelections = newSelections,
                    freeTrialOverride = result.freeTrialOverride,
                ),
            )

            val status = buildSubscriptionStatus(state.value)
            update(Updates.UpdateSession { it.copy(overriddenSubscriptionStatus = status) })
            entitlements?.setSubscriptionStatus(status)

            track(InternalSuperwallEvent.TestModeModal(InternalSuperwallEvent.TestModeModal.State.Close))
        })
    }
}

// Convenience alias — used widely from external call sites.
typealias TestModeSessionData = TestModeState.TestModeSessionData

/** Pure derivation of subscription status from a [TestModeState] snapshot. */
internal fun buildSubscriptionStatus(state: TestModeState): SubscriptionStatus {
    val session = state.sessionOrNull ?: return SubscriptionStatus.Inactive
    if (session.entitlementIds.isEmpty()) return SubscriptionStatus.Inactive
    val activeSelections = session.entitlementSelections.filter { it.state.isActive }
    return if (activeSelections.isNotEmpty()) {
        SubscriptionStatus.Active(activeSelections.map { it.toEntitlement() }.toSet())
    } else {
        SubscriptionStatus.Active(session.entitlementIds.map { Entitlement(it) }.toSet())
    }
}
