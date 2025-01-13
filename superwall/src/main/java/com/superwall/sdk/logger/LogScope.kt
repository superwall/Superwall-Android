package com.superwall.sdk.logger

enum class LogScope {
    localizationManager,
    bounceButton,
    webEntitlements,
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
    storeKitManager,
    events,
    receipts,
    superwallCore,
    jsEvaluator,
    paywallPresentation,
    paywallTransactions,
    paywallView,
    nativePurchaseController,
    cache,
    all,
    ;

    override fun toString(): String = name
}
