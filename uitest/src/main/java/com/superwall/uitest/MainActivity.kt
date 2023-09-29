package com.superwall.uitest

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SuperwallDelegate

class MainActivity : AppCompatActivity(), SuperwallDelegate {
    override fun onCreate(savedInstanceState: Bundle?) {

        Superwall.configure(
            this,
            "pk_5f6d9ae96b889bc2c36ca0f2368de2c4c3d5f6119aacd3d2",
        )
        Superwall.instance.delegate = this


        super.onCreate(savedInstanceState)
        setContent {
            UITestTable()
        }
    }


}