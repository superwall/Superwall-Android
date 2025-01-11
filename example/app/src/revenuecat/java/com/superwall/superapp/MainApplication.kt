package com.superwall.superapp

import android.app.Application
import com.superwall.sdk.Superwall

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Superwall.configure(
            this,
            Keys.EXAMPLE_KEY,
            purchaseController = RevenueCatPurchaseController(this),
        )
    }
}
