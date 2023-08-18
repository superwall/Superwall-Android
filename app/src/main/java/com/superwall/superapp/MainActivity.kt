package com.superwall.superapp

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find the button using its ID
        val myButton: Button = findViewById(R.id.registerButton)

        // Attach a click listener to the button
        myButton.setOnClickListener {
            val app = application as? MainApplication
            app?.invokeRegister()
        }

        val cancelButton: Button = findViewById(R.id.cancelButton)
        cancelButton.setOnClickListener {
            val subscriptionUrl = "https://play.google.com/store/account/subscriptions?sku=pro_sub_test_year_2999&package=com.superwall.superapp"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(subscriptionUrl))
            startActivity(intent)
        }
    }
}