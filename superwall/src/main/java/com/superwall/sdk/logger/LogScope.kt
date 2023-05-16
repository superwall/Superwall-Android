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
    cache,
    all;

    override fun toString(): String {
        return name
    }
}
