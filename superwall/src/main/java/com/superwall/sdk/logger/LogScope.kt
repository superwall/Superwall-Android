package com.superwall.sdk.logger

enum class LogScope {
    localizationManager,
    bounceButton,
    coreData,
    configManager,
    identityManager,
    debugManager,
    debugViewController,
    localizationViewController,
    gameControllerManager,
    device,
    network,
    paywallEvents,
    productsManager,
    storeKitManager,
    events,
    receipts,
    superwallCore,
    paywallPresentation,
    paywallTransactions,
    paywallViewController,
    nativePurchaseController,
    cache,
    all,
    ;

    override fun toString(): String = name
}
