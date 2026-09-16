package com.superwall.sdk.store.testmode

import android.app.Activity
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.IsTestModeActiveSubscription
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredTestModeSettings
import com.superwall.sdk.storage.TestModeSettings
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallEntitlementRef
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductsResponse
import com.superwall.sdk.store.testmode.ui.EntitlementSelection
import com.superwall.sdk.store.testmode.ui.EntitlementStateOption
import com.superwall.sdk.store.testmode.ui.TestModeModal
import com.superwall.sdk.store.testmode.ui.TestModeModalResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Test-mode manager.
 *
 * Implements [TestModeContext] directly so [TestModeState.Actions] receive
 * `this` as their receiver — same pattern as
 * [com.superwall.sdk.identity.IdentityManager] / [com.superwall.sdk.identity.IdentityContext].
 *
 * State is held in a [SequentialActor]; pure mutations go through
 * `update(Updates.X)` (CAS-atomic), async work (network + modal) is
 * dispatched as [TestModeState.Actions].
 */
class TestMode(
    override val storage: Storage,
    override val isTestEnvironment: Boolean = Companion.isTestEnvironment,
    override val getSuperwallProducts: suspend () -> Either<SuperwallProductsResponse, NetworkError> = {
        Either.Failure(NetworkError.Unknown())
    },
    override val entitlements: Entitlements? = null,
    override val activityProvider: () -> ActivityProvider? = { null },
    override val activityTracker: () -> CurrentActivityTracker? = { null },
    override val hasExternalPurchaseController: () -> Boolean = { false },
    override val apiKey: () -> String = { "" },
    override val dashboardBaseUrl: () -> String = { "" },
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit = { },
    override val showModal: suspend (
        activity: Activity,
        reason: String,
        hasPurchaseController: Boolean,
        availableEntitlements: List<String>,
        apiKey: String,
        dashboardBaseUrl: String,
        savedSettings: TestModeSettings?,
    ) -> TestModeModalResult = { activity, reason, hasPC, available, ak, db, saved ->
        TestModeModal.show(activity, reason, hasPC, available, ak, db, saved)
    },
    private val ioScope: CoroutineScope = IOScope(),
    override val actor: StateActor<TestModeContext, TestModeState> =
        SequentialActor(TestModeState.Inactive, ioScope),
) : TestModeContext {
    companion object {
        val isTestEnvironment: Boolean by lazy {
            try {
                runCatching {
                    Class.forName("org.junit.Test")
                    true
                }.getOrNull() ?: false ||
                    runCatching {
                        Class.forName("androidx.test.espresso.Espresso")
                        true
                    }.getOrNull() ?: false ||
                    runCatching {
                        Class.forName("android.support.test.espresso.Espresso")
                        true
                    }.getOrNull() ?: false
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }

    override val scope: CoroutineScope get() = ioScope

    // ---- Read accessors (snapshot of state.value) -------------------------

    val isTestMode: Boolean get() = state.value is TestModeState.Active
    val testModeReason: TestModeReason? get() = (state.value as? TestModeState.Active)?.reason
    private val session: TestModeSessionData? get() = state.value.sessionOrNull

    val products: List<SuperwallProduct> get() = session?.products ?: emptyList()
    internal val testProductsByFullId: Map<String, StoreProduct> get() = session?.testProductsByFullId ?: emptyMap()
    val testEntitlementIds: Set<String> get() = session?.entitlementIds ?: emptySet()
    val testEntitlementSelections: List<EntitlementSelection> get() = session?.entitlementSelections ?: emptyList()
    val freeTrialOverride: FreeTrialOverride get() = session?.freeTrialOverride ?: FreeTrialOverride.UseDefault
    val overriddenSubscriptionStatus: SubscriptionStatus? get() = session?.overriddenSubscriptionStatus

    // ---- Pure-state mutators (synchronous, CAS-atomic) --------------------

    fun evaluateTestMode(
        config: Config,
        bundleId: String,
        appUserId: String?,
        aliasId: String?,
        testModeBehavior: TestModeBehavior = TestModeBehavior.AUTOMATIC,
    ) {
        val newReason =
            TestModeLogic.evaluate(
                config = config,
                bundleId = bundleId,
                appUserId = appUserId,
                aliasId = aliasId,
                behavior = testModeBehavior,
                isTestEnvironment = isTestEnvironment,
            )
        if (newReason == null) {
            if (isTestMode) clearTestModeState()
            return
        }
        val previousReason = testModeReason
        update(TestModeState.Updates.SetActive(newReason))
        if (previousReason != null && previousReason != newReason) {
            storage.write(IsTestModeActiveSubscription, false)
        }
        Logger.debug(
            LogLevel.info,
            LogScope.superwallCore,
            "Test mode activated: ${newReason.description}",
        )
    }

    fun setProducts(products: List<SuperwallProduct>) {
        update(TestModeState.Updates.UpdateSession { it.copy(products = products) })
    }

    fun setTestProducts(productsByFullId: Map<String, StoreProduct>) {
        update(
            TestModeState.Updates.UpdateSession {
                it.copy(testProductsByFullId = productsByFullId)
            },
        )
        session?.productsLoaded?.complete(Unit)
    }

    /** Suspend until the test product catalog has been loaded (or [timeout] elapses). No-op when inactive. */
    suspend fun awaitTestProducts(timeout: Duration = 5.seconds) {
        val s = session ?: return
        withTimeoutOrNull(timeout) { s.productsLoaded.await() }
    }

    fun fakePurchase(entitlementRefs: List<SuperwallEntitlementRef>) {
        val ids = entitlementRefs.map { it.identifier }.toSet()
        update(
            TestModeState.Updates.UpdateSession {
                it.copy(entitlementIds = it.entitlementIds + ids)
            },
        )
        storage.write(IsTestModeActiveSubscription, testEntitlementIds.isNotEmpty())
    }

    fun setEntitlements(selections: List<EntitlementSelection>) {
        val newIds =
            selections.filter { it.state.isActive }.map { it.identifier }.toSet()
        update(
            TestModeState.Updates.UpdateSession {
                it.copy(entitlementSelections = selections, entitlementIds = newIds)
            },
        )
        storage.write(IsTestModeActiveSubscription, newIds.isNotEmpty())
    }

    fun setEntitlements(ids: Set<String>) =
        setEntitlements(
            ids.map {
                EntitlementSelection(identifier = it, state = EntitlementStateOption.Subscribed)
            },
        )

    fun resetEntitlements() {
        update(
            TestModeState.Updates.UpdateSession {
                it.copy(entitlementIds = emptySet(), entitlementSelections = emptyList())
            },
        )
        storage.write(IsTestModeActiveSubscription, false)
    }

    fun setFreeTrialOverride(override: FreeTrialOverride) {
        update(TestModeState.Updates.UpdateSession { it.copy(freeTrialOverride = override) })
    }

    fun setOverriddenSubscriptionStatus(status: SubscriptionStatus?) {
        update(TestModeState.Updates.UpdateSession { it.copy(overriddenSubscriptionStatus = status) })
    }

    fun clearTestModeState() {
        update(TestModeState.Updates.SetInactive)
        storage.delete(IsTestModeActiveSubscription)
        clearSettings()
    }

    // ---- Derived helpers --------------------------------------------------

    fun shouldShowFreeTrial(hasFreeTrial: Boolean): Boolean =
        when (freeTrialOverride) {
            FreeTrialOverride.UseDefault -> hasFreeTrial
            FreeTrialOverride.ForceAvailable -> true
            FreeTrialOverride.ForceUnavailable -> false
        }

    fun buildSubscriptionStatus(): SubscriptionStatus = buildSubscriptionStatus(state.value)

    fun entitlementsForProduct(product: SuperwallProduct): List<SuperwallEntitlementRef> = product.entitlements

    fun allEntitlements(): Set<String> =
        products.flatMap { it.entitlements.map { e -> e.identifier } }.toSet()

    // ---- Settings persistence --------------------------------------------

    fun saveSettings() {
        val settings =
            TestModeSettings(
                entitlementSelections = testEntitlementSelections,
                freeTrialOverride = freeTrialOverride,
            )
        storage.write(StoredTestModeSettings, settings)
    }

    fun loadSettings(): TestModeSettings? = storage.read(StoredTestModeSettings)

    fun clearSettings() {
        storage.delete(StoredTestModeSettings)
    }

    // ---- Async activation flow -------------------------------------------

    /**
     * Refresh the test product catalog and (when [justActivated]) present
     * the modal. Runs as a [TestModeState.Actions.Activate] action and suspends
     * until it completes, so callers that must not wait on the modal's blocking
     * UI (e.g. ConfigState) launch it in their own scope.
     */
    suspend fun activate(
        config: Config,
        justActivated: Boolean,
    ) {
        immediate(TestModeState.Actions.Activate(config, justActivated))
    }
}
