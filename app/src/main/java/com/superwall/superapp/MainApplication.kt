package com.superwall.superapp

import android.app.Activity
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import androidx.appcompat.app.AlertDialog
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.paywall.presentation.register
import kotlinx.coroutines.flow.MutableSharedFlow
import java.lang.ref.WeakReference

class MainApplication :
    android.app.Application(),
    SuperwallDelegate {
    companion object {
        const val CONSTANT_API_KEY = "pk_0ff90006c5c2078e1ce832bd2343ba2f806ca510a0a1696a"

        /*
        Copy and paste the following API keys to switch between apps.
        App API Keys:
            Android Main screen: pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85
            UITest (Android): pk_0ff90006c5c2078e1ce832bd2343ba2f806ca510a0a1696a
            DeepLink Open: pk_3faea4c721179218a245475ea9d378d1ecb9bf059411a0c0
            AppLaunch: pk_fb295f846b075fae6619eebb43d126ecddd1e3b18e7028b8
            AppInstall: pk_8db958db59cc8460969659822351d5e177d8d65cb295cff2
            SessionStart: pk_6c881299e2f8db59f697646e399397be76432fa0968ca254
            PaywallDecline: pk_a1071d541642719e2dc854da9ec717ec967b8908854ede74
            TransactionAbandon: pk_9c99186b023ae795e0189cf9cdcd3e2d2d174289e0800d66
            TransactionFail: pk_b6cd945401435766da627080a3fbe349adb2dcd69ab767f3
            SurveyResponse: pk_3698d9fe123f1e4aa8014ceca111096ca06fd68d31d9e662
         */
    }

    var activity: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(
            ThreadPolicy
                .Builder()
                .detectCustomSlowCalls()
                .detectResourceMismatches()
                .detectUnbufferedIo() // or .detectAll() for all detectable problems
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            VmPolicy
                .Builder()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )

        configureWithAutomaticInitialization()
//        configureWithRevenueCatInitialization()
    }

    val events = MutableSharedFlow<SuperwallEventInfo>()

    fun configureWithAutomaticInitialization() {
        Superwall.configure(
            this,
            CONSTANT_API_KEY,
            options =
                SuperwallOptions().apply {
                    paywalls =
                        PaywallOptions().apply {
                            shouldPreload = false
                        }
                },
        )
        Superwall.instance.delegate = this

        // Make sure we enable the game controller
        // Superwall.instance.options.isGameControllerEnabled = true
    }

    fun configureWithObserverMode() {
        Superwall.configure(
            this@MainApplication,
            CONSTANT_API_KEY,
            options =
                SuperwallOptions().apply {
                    shouldObservePurchases = true
                    paywalls =
                        PaywallOptions().apply {
                            shouldPreload = false
                        }
                },
        )
        Superwall.instance.delegate = this@MainApplication

        // Make sure we enable the game controller
        // Superwall.instance.options.isGameControllerEnabled = true
    }

    fun configureWithRevenueCatInitialization() {
        val purchaseController = RevenueCatPurchaseController(this)

        Superwall.configure(
            this,
            CONSTANT_API_KEY,
            purchaseController,
        )
        Superwall.instance.delegate = this

        // Make sure we enable the game controller
        // Superwall.instance.options.isGameControllerEnabled = true

        purchaseController.syncSubscriptionStatus()
    }

    fun invokeRegister(
        event: String = "campaign_trigger",
        params: Map<String, Any>? = null,
    ) {
        Superwall.instance.register(event, params)
    }

    override fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?,
    ) {
        val ctx = activity?.get() ?: return
        if (level == LogLevel.error.toString() &&
            scope == LogScope.productsManager.toString()
        ) {
            AlertDialog
                .Builder(ctx)
                .apply {
                    setTitle("Error")
                    setMessage(message)
                    setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                }.show()
        }
        super.handleLog(level, scope, message, info, error)
    }

    override fun handleSuperwallEvent(withInfo: SuperwallEventInfo) {
        println(
            "\n!! SuperwallDelegate !! \n" +
                "\tEvent name:" + withInfo.event.rawName + "" +
                ",\n\tParams:" + withInfo.params + "\n",
        )
    }
}
