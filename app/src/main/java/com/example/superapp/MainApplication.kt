package com.example.superapp

import com.superwall.sdk.Superwall

class MainApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Superwall.configure(this, "pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85")

        // Delay the presentation of the paywall by 5 seconds
        android.os.Handler().postDelayed({
            Superwall.register("campaign_trigger")
        }, 2000)
    }
}