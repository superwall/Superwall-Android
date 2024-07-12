package com.superwall.sdk.paywall.vc

import android.view.View
import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap

/*
* Stores already loaded or preloaded paywalls
* */
internal class ViewStorageViewModel :
    ViewModel(),
    ViewStorage {
    override val views = ConcurrentHashMap<String, View>()
}
