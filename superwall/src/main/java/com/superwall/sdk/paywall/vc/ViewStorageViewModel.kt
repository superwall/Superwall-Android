package com.superwall.sdk.paywall.vc

import android.view.View
import androidx.lifecycle.ViewModel

/*
* Stores already loaded or preloaded paywalls
* */
internal class ViewStorageViewModel :
    ViewModel(),
    ViewStorage {
    override val views = mutableMapOf<String, View>()

    override fun storeView(
        key: String,
        view: View,
    ) {
        views[key] = view
    }

    override fun retrieveView(key: String): View? = views[key]
}
