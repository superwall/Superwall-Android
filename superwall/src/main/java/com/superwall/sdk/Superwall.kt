package com.superwall.sdk

import android.app.Application
import android.content.Context
import android.util.Log
import com.superwall.sdk.api.Config
import com.superwall.sdk.api.Network
import com.superwall.sdk.misc.ActivityLifecycleTracker
import com.superwall.sdk.view.PaywallViewManager


public class Superwall(context: Context, apiKey: String) {
    var apiKey: String = apiKey
    var contex: Context = context
    companion object {
        lateinit var instance: Superwall
        public fun configure(applicationContext: Context,  apiKey: String) {

            if (apiKey == null) {
                throw Exception("API Key is required")
            }

            // If instance is already set fail,
            // the SDK can only be configured once
//            if (instance != null) {
//                throw Exception("Superwall is already configured")
//            }

            // setup the SDK using that API Key
            instance =  Superwall(applicationContext, apiKey)
            instance.setup()
        }


        public fun register(eventName: String) {
            // Register an event that could trigger a paywall
            Log.println(Log.INFO, "Superwall", "Superwall register event: " + eventName)

            // TODO: Make sure config has been fetched already
            // Look through the config for a trigger matching the event

            if (instance.config is Config) {
                Log.println(Log.INFO, "Superwall", "Superwall config is not null")
                val trigger = instance.config!!.triggers.find { trigger ->
                    trigger.eventName == eventName }

                if (trigger != null)  {
                    Log.println(Log.INFO, "Superwall", "Superwall trigger is not null")
                    // If there is a trigger, check if it is time to show the paywall


                    // Find the paywall
                    val treatment = instance.config!!.triggers.first().rules.first().experiment.variants.find { variant -> variant.type == Experiment.Variant.VariantType.TREATMENT  }
                    if (treatment != null) {
                        val paywall = instance.config!!.paywalls.find { paywall ->
                            paywall.identifier == treatment.paywallId
                        }

                        if (paywall != null) {
                            PaywallViewManager.showPaywall(paywall)
                        } else {
                            Log.println(Log.INFO, "Superwall", "Superwall paywall is null")
                        }
                    } else {
                        Log.println(Log.INFO, "Superwall", "Superwall treatment is null")
                    }


                } else {
                    Log.println(Log.INFO, "Superwall", "Superwall trigger is null")
                }

            }
        }


        public fun setUserAttributes(attributes: Map<String, String?>) {
            // Register an event that could trigger a paywall
            Log.println(Log.INFO, "Superwall", "Superwall setUserAttributes: " + attributes)


        }
    }

    fun setup() {
        // Called after the constructor to begin setting up the sdk
        Log.println(Log.INFO, "Superwall", "Superwall setup")

        // Make sure we start tacking the contexts so we know where to present the paywall
        // onto
        (contex.applicationContext as Application).registerActivityLifecycleCallbacks(
            ActivityLifecycleTracker.instance)

        // Fetch the static configuration from the server
        Network.getStaticConfig {
            config ->
            Log.println(Log.INFO, "Superwall", "Superwall config: " + config)
            if (config != null) {
                // Save the config
                this.config = config
                Log.println(Log.INFO, "Superwall", "Superwall config" + config)
            }
        }
    }

    protected var config: Config? = null


    fun present() {
        // Present the Superwall
        Log.println(Log.INFO, "Superwall", "Superwall present")

        // Find the first paywall in the config
        val paywall = config?.paywalls?.firstOrNull()
        if (paywall != null) {
            // Show the paywall
            Log.println(Log.INFO, "Superwall", "Superwall show paywall")


        }

    }

}


//companion object {
//        private var instance: Superwall? = null
//
//        fun getInstance(): Superwall {
//            if (instance == null) {
//                instance = Superwall()
//            }
//            return instance as Superwall
//        }
//
//        fun init() {
//            println("Superwall init")
//
//            // show alert
//            Superwall.getInstance().showAlert(Superwall.getInstance(), "Superwall", "Superwall init")
//
//
//
//        }
//
//    }
//
//    fun showAlert(context: Context, title: String, message: String) {
//        val alertDialog = AlertDialog.Builder(context).create()
//        alertDialog.setTitle(title)
//        alertDialog.setMessage(message)
//        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { dialog, which ->
//            // do something when the OK button is clicked
//        }
//        alertDialog.show()
//    }
//
//}
