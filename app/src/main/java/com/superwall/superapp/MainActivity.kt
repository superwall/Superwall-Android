package com.superwall.superapp

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
    }
}