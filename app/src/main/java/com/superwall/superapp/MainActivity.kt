package com.superwall.superapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // campaign_trigger
        val campaignTriggerButton: Button = findViewById(R.id.campaignTriggerButton)

        // Attach a click listener to the button
        campaignTriggerButton.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister("campaign_trigger")
        }

        // campaign_trigger_v3
        val campaignTriggerV3Button: Button = findViewById(R.id.campaignTriggerV3Button)

        // Attach a click listener to the button
        campaignTriggerV3Button.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister("campaign_trigger_v3")
        }

        // another_paywall
        val anotherPaywallButton: Button = findViewById(R.id.anotherPaywallButton)

        // Attach a click listener to the button
        anotherPaywallButton.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister("another_paywall")
        }
    }
}