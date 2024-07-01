package com.superwall.sdk.paywall.vc

import android.view.View

interface ViewStorage {
    val views: MutableMap<String, View>

    fun storeView(
        key: String,
        view: View,
    ) {
        views[key] = view
    }

    fun retrieveView(key: String): View? = views.get(key)
}
