package com.superwall.sdk.logger

enum class LogScope {
    localizationManager,
    bounceButton,
    webEntitlements,
    customerInfo,
    coreData,
    configManager,
    identityManager,
    debugManager,
    debugView,
    localizationView,
    gameControllerManager,
    device,
    network,
    paywallEvents,
    productsManager,
    @Deprecated(
        message = "Renamed to billing. Setting storeKitManager in SuperwallOptions.logging.scopes still enables billing logs.",
        replaceWith = ReplaceWith("LogScope.billing"),
    )
    storeKitManager,
    placements,
    receipts,
    superwallCore,
    transactions,
    jsEvaluator,
    paywallPresentation,
    paywallTransactions,
    paywallView,
    nativePurchaseController,
    cache,
    deepLinks,
    customerCenter,
    all,

    // Added after `all` so the ordinals of the existing values do not change.
    billing,
    ;

    override fun toString(): String = name
}

/**
 * Whether logs in this scope are enabled by [scopes]. [LogScope.billing] is also enabled
 * by the deprecated [LogScope.storeKitManager], which it replaces.
 */
@Suppress("DEPRECATION")
internal fun LogScope.isEnabledBy(scopes: Set<LogScope>): Boolean =
    scopes.contains(this) ||
        scopes.contains(LogScope.all) ||
        (this == LogScope.billing && scopes.contains(LogScope.storeKitManager))

