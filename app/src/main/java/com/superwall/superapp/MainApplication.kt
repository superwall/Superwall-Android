package com.superwall.superapp

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.events.EventData

class MainApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Superwall.configure(this, "pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85")
        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)

        // Delay the presentation of the paywall by 5 seconds
        android.os.Handler().postDelayed({
            Superwall.instance.register("campaign_trigger")
//            Superwall.instance.track(EventData("test_event", mapOf("test_key" to "test_value")))
        }, 200)
    }
}