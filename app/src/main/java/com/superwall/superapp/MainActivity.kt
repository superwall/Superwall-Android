package com.superwall.superapp

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import com.superwall.sdk.Superwall

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup deep linking handling
        respondToDeepLinks()

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

        // wide_paywall
        val widePaywallButton: Button = findViewById(R.id.widePaywallButton)

        // Attach a click listener to the button
        widePaywallButton.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister("wide_paywall")
        }

        // jetpack compose activity
        val jetpackComposeButton: Button = findViewById(R.id.jetpackComposeButton)

        // Attach a click listener to the button
        jetpackComposeButton.setOnClickListener {
            val intent = Intent(this, ComposeActivity::class.java)
            startActivity(intent)
        }

        // UITest button
        val uiTestButton: Button = findViewById(R.id.uiTest)

        // Attach a click listener to the button
        uiTestButton.setOnClickListener {
            val intent = Intent(this, UITestActivity::class.java)
            startActivity(intent)
        }
        
        val devicePropertiesTestButton: Button = findViewById(R.id.devicePropertiesTest)
        devicePropertiesTestButton.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister("device_product_test")
        }
    }

    //region Deep Links

    private fun respondToDeepLinks() {
        intent?.data?.let { uri ->
            Superwall.instance.handleDeepLink(uri)
        }
    }

    //endregion
}