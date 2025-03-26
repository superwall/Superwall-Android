package com.superwall.sdk.network

import com.superwall.sdk.config.options.SuperwallOptions

data class Api(
    val hostDomain: String,
    val base: Base,
    val collector: Collector,
    val geo: Geo,
    val subscription: Subscriptions,
) {
    companion object {
        const val version1 = "/api/v1/"
        const val subscriptionsv1 = "/subscriptions-api/public/v1/"
        const val scheme = "https"
    }

    constructor(networkEnvironment: SuperwallOptions.NetworkEnvironment) : this(
        hostDomain = networkEnvironment.hostDomain,
        base = Base(networkEnvironment),
        collector = Collector(networkEnvironment),
        geo = Geo(networkEnvironment),
        subscription = Subscriptions(networkEnvironment),
    )

    data class Base(
        private val networkEnvironment: SuperwallOptions.NetworkEnvironment,
    ) {
        val host: String
            get() = networkEnvironment.baseHost
//            get() = "10.0.2.2:9909"
    }

    data class Subscriptions(
        private val networkEnvironment: SuperwallOptions.NetworkEnvironment,
    ) {
        val host: String
            get() = networkEnvironment.subscriptionHost
//            get() = "10.0.2.2:9909"
    }

    data class Collector(
        private val networkEnvironment: SuperwallOptions.NetworkEnvironment,
    ) {
        val host: String
            //            get() = "10.0.2.2:9909"
            get() = networkEnvironment.collectorHost
    }

    data class Geo(
        private val networkEnvironment: SuperwallOptions.NetworkEnvironment,
    ) {
        val host: String
            //            get() = "10.0.2.2:9909"
            get() = "geo-api.superwall.com"
    }
}
