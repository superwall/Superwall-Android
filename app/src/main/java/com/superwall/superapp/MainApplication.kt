package com.superwall.superapp

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.paywall.presentation.register
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch




class MainApplication : android.app.Application(), SuperwallDelegate {
    override fun onCreate() {
        super.onCreate()

        val purchaseController =  RevenueCatPurchaseController(this)

        /*
        Copy and paste the following API keys to switch between apps.
        App API Keys:
            Android Main screen: pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85
            UITest (default): pk_5f6d9ae96b889bc2c36ca0f2368de2c4c3d5f6119aacd3d2
            Deep Link Open: pk_3faea4c721179218a245475ea9d378d1ecb9bf059411a0c0
         */

        Superwall.configure(
            this,
            "pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85",
            purchaseController
        )
        Superwall.instance.delegate = this

        // Make sure we enable the game controller
        Superwall.instance.options.isGameControllerEnabled = true

        // TODO: Fix this so we don't need to make the user set this. Should we still have this given that RC is supposed to set the status??
        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
    }

    fun invokeRegister(
        event: String = "campaign_trigger",
        params: Map<String, Any>? = null
    ) {
        Superwall.instance.register(event, params)
    }

    override fun handleSuperwallEvent(withInfo: SuperwallEventInfo) {
        println("\n!! SuperwallDelegate !! \n" +
                "\tEvent name:" + withInfo.event.rawName + "" +
                ",\n\tParams:" + withInfo.params + "\n"
        )
    }
}
