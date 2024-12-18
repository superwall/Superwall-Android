package com.superwall.exampleapp

import android.app.Application
import com.superwall.sdk.Superwall

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Superwall.configure(
            this,
            Keys.EXAMPLE_KEY,
            options =
                SuperwallOptions().apply {
                    shouldObservePurchases = true
                },
        )
    }
}
