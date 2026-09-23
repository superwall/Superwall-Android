package com.superwall.sdk.customercenter

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.product.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class CustomerCenterScreenState { LOADING, MANAGEMENT, NO_PURCHASES }

internal enum class CustomerCenterRestoreState { IDLE, RESTORING, RESTORED, NOT_FOUND }

/** Something the Customer Center shows over its own screen. */
internal sealed class CustomerCenterSheet {
    data class Survey(
        val pathId: String,
    ) : CustomerCenterSheet()

    data class NoMailApp(
        val email: String,
    ) : CustomerCenterSheet()

    object WebManageUnavailable : CustomerCenterSheet()
}

internal data class CustomerCenterUiState(
    val screen: CustomerCenterScreenState = CustomerCenterScreenState.LOADING,
    val purchases: List<PurchasePresentation> = emptyList(),
    val sheet: CustomerCenterSheet? = null,
    val restoreState: CustomerCenterRestoreState = CustomerCenterRestoreState.IDLE,
    val refundResult: Pair<String, CustomerCenterRefundStatus>? = null,
    val showsUpdateBanner: Boolean = false,
    val showsDuplicateBanner: Boolean = false,
    /** The path whose action is running, while one is. Other rows are disabled meanwhile. */
    val busyPathId: String? = null,
)

/** What the view model tells its host about. All are called on the view model's scope. */
internal class CustomerCenterCallbacks(
    val shouldRestore: (suspend () -> Boolean)? = null,
    val didSelectAction: ((CustomerCenterAction, String, CustomerCenterPurchase?) -> Unit)? = null,
    val didCompleteSurvey: ((surveyId: String, optionId: String, CustomerCenterAction, pathId: String) -> Unit)? = null,
    val didCompleteRefund: ((productId: String, CustomerCenterRefundStatus) -> Unit)? = null,
    val didDismiss: (() -> Unit)? = null,
) {
    companion object {
        fun from(delegate: CustomerCenterDelegate?): CustomerCenterCallbacks {
            if (delegate == null) return CustomerCenterCallbacks()
            return CustomerCenterCallbacks(
                shouldRestore = {
                    suspendCancellableCoroutine { continuation ->
                        // A host calling its completion twice shouldn't be able to crash the app:
                        // only the first answer counts.
                        val answered = AtomicBoolean(false)
                        delegate.customerCenterShouldRestorePurchases { proceed ->
                            if (answered.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(proceed)
                            }
                        }
                    }
                },
                didSelectAction = delegate::customerCenterDidSelectAction,
                didCompleteSurvey = delegate::customerCenterDidCompleteSurvey,
                didCompleteRefund = delegate::customerCenterDidCompleteRefundRequest,
                didDismiss = delegate::customerCenterDidDismiss,
            )
        }
    }
}

/** Drives the Customer Center UI: loads customer info and products, resolves paths, and performs actions. */
internal class CustomerCenterViewModel(
    val configuration: CustomerCenterConfiguration,
    private val dependencies: CustomerCenterDependencies,
    val strings: CustomerCenterStrings,
    private val scope: CoroutineScope,
    var callbacks: CustomerCenterCallbacks = CustomerCenterCallbacks(),
    /** How long the restoring overlay stays up at least, so it doesn't flash. */
    private val minimumRestoreDurationMs: Long = 500,
) {
    private val _state = MutableStateFlow(CustomerCenterUiState())
    val state: StateFlow<CustomerCenterUiState> = _state.asStateFlow()

    private var products: Map<String, ProductDisplayInfo> = emptyMap()

    /** Lets an `apply` that a newer one has overtaken drop its older snapshot. */
    private var applyGeneration = 0

    /** Products whose display info is still coming from the Superwall catalogue. */
    private var awaitingCatalogue: Set<String> = emptySet()
    private var catalogueJob: Job? = null
    private var hasTrackedOpen = false
    private var hasLoaded = false
    private var didDismiss = false
    private var updateWarningDismissed = false

    /**
     * Set when the customer is sent to a store page. Google Play doesn't tell the app about
     * changes made there, so purchases are reloaded when they come back.
     */
    private var refreshesOnResume = false

    /** Active entitlement identifiers from the latest [CustomerInfo], for support diagnostics. */
    private var activeEntitlementIds: List<String> = emptyList()

    var pendingSurvey: Pair<CustomerCenterConfiguration.Path, CustomerCenterConfiguration.FeedbackSurvey>? = null
        private set
    private var pendingAction: Pair<ResolvedPath, PurchasePresentation?>? = null

    val userId: String get() = dependencies.environment.userId
    val originalDownloadDate get() = dependencies.environment.originalDownloadDate
    val locale get() = dependencies.environment.locale
    val appListingUrl: String get() = PlayStoreLinks.appListing(dependencies.environment.packageName)

    init {
        configuration.warnAboutConfigurationProblems()
        scope.launch {
            dependencies.customerInfo.updates.collect { apply(it, refetchProducts = true) }
        }
    }

    // region Loading

    /** Loads the screen. Only the first call does anything, so it's safe to call on every show. */
    fun start() {
        if (hasLoaded) return
        hasLoaded = true
        scope.launch { load() }
    }

    suspend fun load() {
        val info = dependencies.customerInfo.fetchCustomerInfo()
        apply(info, refetchProducts = true)
        if (!hasTrackedOpen) {
            hasTrackedOpen = true
            dependencies.tracker.track(
                InternalSuperwallEvent.CustomerCenterOpen(
                    screen = if (hasAnyPurchases(info)) CustomerCenterScreenType.MANAGEMENT else CustomerCenterScreenType.NO_PURCHASES,
                ),
            )
        }
    }

    private suspend fun apply(
        customerInfo: CustomerInfo,
        refetchProducts: Boolean,
    ) {
        applyGeneration += 1
        val generation = applyGeneration
        if (refetchProducts) {
            val ids =
                (customerInfo.subscriptions.map { it.productId } + customerInfo.nonSubscriptions.map { it.productId }).toSet()
            val fetched = dependencies.products.products(ids)
            if (generation != applyGeneration) return
            products = fetched
            awaitingCatalogue = catalogueProductIds(customerInfo) - fetched.keys
        }
        activeEntitlementIds = customerInfo.entitlements.filter { it.isActive }.map { it.id }
        val activeStores = customerInfo.subscriptions.filter { it.isActive }.map { it.store }.toSet()
        _state.update {
            it.copy(
                screen = if (hasAnyPurchases(customerInfo)) CustomerCenterScreenState.MANAGEMENT else CustomerCenterScreenState.NO_PURCHASES,
                purchases = buildPurchases(customerInfo),
                showsUpdateBanner = computeUpdateBanner(),
                showsDuplicateBanner =
                    configuration.warnsAboutDuplicateSubscriptions &&
                        Store.PLAY_STORE in activeStores &&
                        activeStores.any { store -> store in webStores },
            )
        }
        if (refetchProducts) fillFromCatalogue(customerInfo, generation)
    }

    private fun buildPurchases(customerInfo: CustomerInfo): List<PurchasePresentation> =
        PurchasePresentationBuilder(strings, locale).build(customerInfo, products, awaitingCatalogue)

    /**
     * Fetches what Google Play couldn't supply from the Superwall catalogue, after the screen has
     * drawn. Until it answers, the cards it affects show placeholders rather than holding the
     * whole screen behind the spinner; when it fails, they show what they can without it.
     */
    private fun fillFromCatalogue(
        customerInfo: CustomerInfo,
        generation: Int,
    ) {
        val ids = awaitingCatalogue
        if (ids.isEmpty()) return
        catalogueJob?.cancel()
        catalogueJob =
            scope.launch {
                val filled = dependencies.products.catalogueProducts(ids)
                if (generation != applyGeneration) return@launch
                products = products + filled
                awaitingCatalogue = emptySet()
                _state.update { it.copy(purchases = buildPurchases(customerInfo)) }
            }
    }

    /**
     * Whether [info] represents any purchase the Customer Center should show as "management" — a
     * subscription, a non-subscription transaction, or an active entitlement (which covers
     * manually granted and cross-store entitlements that have no local transaction).
     */
    private fun hasAnyPurchases(info: CustomerInfo): Boolean =
        info.subscriptions.isNotEmpty() || info.nonSubscriptions.isNotEmpty() || info.entitlements.any { it.isActive }

    // endregion

    // region Paths

    /**
     * Resolves the paths to show.
     * @param purchase The purchase the paths apply to, if any.
     * @param isScreenLevel `true` for a screen's main action list, where restore is always
     *   available; `false` for a drilled-in purchase detail screen.
     */
    fun paths(
        purchase: PurchasePresentation?,
        isScreenLevel: Boolean = true,
    ): List<ResolvedPath> {
        val screen =
            if (_state.value.screen == CustomerCenterScreenState.NO_PURCHASES) {
                configuration.noPurchasesScreen
            } else {
                configuration.managementScreen
            }
        val context =
            PathResolutionContext(
                purchase = purchase,
                product = purchase?.productId?.let { products[it] },
                supportEmailAvailable = supportEmailAvailable,
                webManagementUrl = dependencies.environment.webManagementUrl,
                canOpenUrls = dependencies.urlOpener.canOpenUrls,
                isScreenLevel = isScreenLevel,
            )
        return CustomerCenterPathResolver.resolve(screen.paths, context)
    }

    /** `null` when the detail screen for [purchase] has actions to show; otherwise which sentence to show instead. */
    fun detailEmptyState(purchase: PurchasePresentation): DetailEmptyState? =
        DetailEmptyStateResolver.resolve(purchase, hasActions = paths(purchase, isScreenLevel = false).isNotEmpty())

    /** Runs the tapped path. Ignored while another path's action is still running. */
    fun onPathTapped(
        resolved: ResolvedPath,
        purchase: PurchasePresentation?,
    ) {
        if (_state.value.busyPathId != null) return
        _state.update { it.copy(busyPathId = resolved.id) }
        scope.launch {
            try {
                select(resolved, purchase)
            } finally {
                _state.update { it.copy(busyPathId = null) }
            }
        }
    }

    suspend fun select(
        resolved: ResolvedPath,
        purchase: PurchasePresentation?,
    ) {
        val action = CustomerCenterAction.from(resolved.path.type)
        callbacks.didSelectAction?.invoke(action, resolved.path.id, purchase?.publicPurchase)
        dependencies.tracker.track(
            InternalSuperwallEvent.CustomerCenterAction(action, resolved.path.id, purchase?.productId),
        )
        val survey = resolved.path.survey
        if (survey != null && survey.options.isNotEmpty() && !resolved.destination.isWebManagement) {
            pendingSurvey = resolved.path to survey
            pendingAction = resolved to purchase
            _state.update { it.copy(sheet = CustomerCenterSheet.Survey(resolved.path.id)) }
            return
        }
        perform(resolved, purchase)
    }

    fun onSurveyAnswered(optionId: String) {
        scope.launch { answerSurvey(optionId) }
    }

    suspend fun answerSurvey(optionId: String) {
        val survey = pendingSurvey ?: return
        val action = pendingAction ?: return
        val (resolved, purchase) = action
        val customerCenterAction = CustomerCenterAction.from(resolved.path.type)
        callbacks.didCompleteSurvey?.invoke(survey.second.id, optionId, customerCenterAction, resolved.path.id)
        dependencies.tracker.track(
            InternalSuperwallEvent.CustomerCenterSurveyResponse(
                surveyId = survey.second.id,
                optionId = optionId,
                action = customerCenterAction,
                pathId = resolved.path.id,
                productId = purchase?.productId,
            ),
        )
        pendingSurvey = null
        pendingAction = null
        _state.update { it.copy(sheet = null) }
        perform(resolved, purchase)
    }

    fun cancelSurvey() {
        pendingSurvey = null
        pendingAction = null
        _state.update { if (it.sheet is CustomerCenterSheet.Survey) it.copy(sheet = null) else it }
    }

    /** Call when a sheet other than the survey is dismissed. */
    fun sheetDismissed() {
        if (_state.value.sheet is CustomerCenterSheet.Survey) {
            cancelSurvey()
        } else {
            _state.update { it.copy(sheet = null) }
        }
    }

    private suspend fun perform(
        resolved: ResolvedPath,
        purchase: PurchasePresentation?,
    ) {
        val opener = dependencies.urlOpener
        val packageName = dependencies.environment.packageName
        when (val destination = resolved.destination) {
            ResolvedPathDestination.Restore -> performRestore()
            is ResolvedPathDestination.PlayStoreManage -> {
                refreshesOnResume = opener.open(PlayStoreLinks.subscription(destination.productId, packageName))
            }
            is ResolvedPathDestination.ChangePlan -> {
                refreshesOnResume = opener.open(PlayStoreLinks.subscription(destination.productId, packageName))
            }
            is ResolvedPathDestination.WebManage -> {
                refreshesOnResume = opener.open(destination.url, inApp = true)
            }
            ResolvedPathDestination.WebManageUnavailable ->
                _state.update { it.copy(sheet = CustomerCenterSheet.WebManageUnavailable) }
            is ResolvedPathDestination.Refund -> {
                val opened = opener.open(PlayStoreLinks.ORDER_HISTORY)
                refreshesOnResume = opened
                refundDidFinish(
                    destination.productId,
                    if (opened) CustomerCenterRefundStatus.SUCCESS else CustomerCenterRefundStatus.ERROR,
                )
            }
            ResolvedPathDestination.ContactSupport -> contactSupport()
            is ResolvedPathDestination.Url -> opener.open(destination.url, inApp = destination.inApp)
            is ResolvedPathDestination.Custom -> Unit
        }
    }

    // endregion

    // region Restore

    suspend fun performRestore() {
        callbacks.shouldRestore?.let { gate ->
            if (!gate()) return
        }
        _state.update { it.copy(restoreState = CustomerCenterRestoreState.RESTORING) }
        val minimumDuration = scope.async { delay(minimumRestoreDurationMs) }
        val result = dependencies.restore.restorePurchases()
        minimumDuration.await()
        val info = dependencies.customerInfo.fetchCustomerInfo()
        apply(info, refetchProducts = true)
        val restored = result is RestorationResult.Restored && hasAnyPurchases(info)
        _state.update {
            it.copy(restoreState = if (restored) CustomerCenterRestoreState.RESTORED else CustomerCenterRestoreState.NOT_FOUND)
        }
    }

    fun restoreAlertDismissed() {
        _state.update { it.copy(restoreState = CustomerCenterRestoreState.IDLE) }
    }

    // endregion

    // region Refund

    private suspend fun refundDidFinish(
        productId: String,
        status: CustomerCenterRefundStatus,
    ) {
        _state.update { it.copy(refundResult = productId to status) }
        callbacks.didCompleteRefund?.invoke(productId, status)
        dependencies.tracker.track(InternalSuperwallEvent.CustomerCenterRefundRequest(productId, status))
    }

    // endregion

    // region Returning from a store page

    /** Call when the Customer Center comes back to the foreground. */
    fun onResume() {
        if (!refreshesOnResume) return
        refreshesOnResume = false
        scope.launch { apply(dependencies.customerInfo.refreshPurchases(), refetchProducts = true) }
    }

    // endregion

    // region Update banner

    private fun computeUpdateBanner(): Boolean =
        !updateWarningDismissed &&
            configuration.support.warnsAboutUpdates &&
            AppVersionComparator.isInstalledVersionOlder(
                dependencies.environment.appVersion,
                configuration.support.latestAppVersion,
            )

    fun continueAfterUpdateWarning() {
        updateWarningDismissed = true
        _state.update { it.copy(showsUpdateBanner = false) }
    }

    fun openAppListing() {
        dependencies.urlOpener.open(appListingUrl)
    }

    // endregion

    // region Support

    val supportMailtoUrl: String?
        get() {
            val env = dependencies.environment
            return SupportEmailComposer.mailtoUrl(
                email = configuration.support.email,
                subject = strings.string("customer_center_support_subject"),
                body = strings.string("customer_center_support_body"),
                diagnostics =
                    SupportEmailDiagnostics(
                        userId = env.userId,
                        appVersion = env.appVersion,
                        osVersion = env.osVersion,
                        deviceModel = env.deviceModel,
                        sdkVersion = env.sdkVersion,
                        activeEntitlementIds = activeEntitlementIds,
                        isSandbox = env.isSandbox,
                    ),
            )
        }

    /**
     * Whether to show the contact-support path. Gated only on a support email being configured:
     * the tap handler falls back to showing the address when no mail app can take it.
     */
    private val supportEmailAvailable: Boolean get() = supportMailtoUrl != null

    /**
     * Opens the mail composer. The row isn't gated on a mail app being installed, so the fallback
     * happens here at tap time: when nothing can take the email, the address is shown instead so
     * the user can still reach support manually.
     */
    fun contactSupport() {
        val url = supportMailtoUrl ?: return
        if (!dependencies.urlOpener.open(url)) {
            _state.update { it.copy(sheet = CustomerCenterSheet.NoMailApp(configuration.support.email.orEmpty().trim())) }
        }
    }

    // endregion

    // region Dismissal

    /** Call once the Customer Center is gone for good. Only the first call has any effect. */
    fun dismiss() {
        if (didDismiss) return
        didDismiss = true
        callbacks.didDismiss?.invoke()
        scope.launch {
            dependencies.tracker.track(InternalSuperwallEvent.CustomerCenterClose())
            scope.cancel()
        }
    }

    // endregion

    companion object {
        /** Products the Superwall catalogue may know but Google Play never will: those bought elsewhere. */
        fun catalogueProductIds(customerInfo: CustomerInfo): Set<String> =
            (
                customerInfo.subscriptions.filter { it.store != Store.PLAY_STORE }.map { it.productId } +
                    customerInfo.nonSubscriptions.filter { it.store != Store.PLAY_STORE }.map { it.productId }
            ).toSet()
    }
}
