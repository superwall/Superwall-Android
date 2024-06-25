package com.superwall.sdk.utilities

import android.app.Application
import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.misc.ActivityProvider

class SuperwallBuilder {
    var purchaseController: PurchaseController? = null
    var options: SuperwallOptions? = null
        private set
    var activityProvider: ActivityProvider? = null
    var completion: (() -> Unit)? = null

    @SuperwallDSL
    fun options(action: SuperwallOptions.() -> Unit) {
        options = SuperwallOptions().apply(action)
    }
}

@SuperwallDSL
fun Application.configureSuperwall(
    apiKey: String,
    configure: SuperwallBuilder.() -> Unit,
) {
    val builder = SuperwallBuilder().apply(configure)
    Superwall.configure(
        this,
        apiKey,
        builder.purchaseController,
        builder.options,
        builder.activityProvider,
        builder.completion,
    )
}

@SuperwallDSL
fun SuperwallOptions.paywalls(action: PaywallOptions.() -> Unit) {
    paywalls = this.paywalls.apply(action)
}

@SuperwallDSL
fun SuperwallOptions.logging(action: SuperwallOptions.Logging.() -> Unit) {
    logging = this.logging.apply(action)
}
