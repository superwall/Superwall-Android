package com.superwall.sdk.debug

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.ViewFactory
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.storage.LocalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebugManager(
    private val context: Context,
    private val storage: LocalStorage,
    private val factory: ViewFactory,
) {
    var view: DebugView? = null
        @MainThread set
    var isDebuggerLaunched = false

    fun handle(deepLinkUrl: Uri): Boolean {
        val launchDebugger =
            SWDebugManagerLogic.getQueryItemValue(
                deepLinkUrl,
                SWDebugManagerLogic.Parameter.SUPERWALL_DEBUG,
            ) ?: return false
        if (!launchDebugger.toBoolean()) return false

        val debugKey =
            SWDebugManagerLogic.getQueryItemValue(
                deepLinkUrl,
                SWDebugManagerLogic.Parameter.TOKEN,
            ) ?: return false

        storage.debugKey = debugKey

        val paywallId =
            SWDebugManagerLogic.getQueryItemValue(
                deepLinkUrl,
                SWDebugManagerLogic.Parameter.PAYWALL_ID,
            )

        CoroutineScope(Dispatchers.IO).launch {
            launchDebugger(paywallId)
        }
        return true
    }

    @MainThread
    suspend fun launchDebugger(paywallDatabaseId: String? = null) {
        if (Superwall.instance.isPaywallPresented) {
            Superwall.instance.dismiss()
            launchDebugger(paywallDatabaseId)
        } else {
            if (view == null) {
                delay(200)
                presentDebugger(paywallDatabaseId)
            } else {
                closeDebugger(true)
                launchDebugger(paywallDatabaseId)
            }
        }
    }

    @MainThread
    suspend fun presentDebugger(paywallDatabaseId: String? = null) {
        isDebuggerLaunched = true
        val currentView = view
        if (currentView != null) {
            if (currentView.isActive) return

            currentView.paywallDatabaseId = paywallDatabaseId
            currentView.loadPreview()

            DebugViewActivity.startWithView(
                context,
                currentView,
            )
        } else {
            val newViewController = factory.makeDebugViewController(paywallDatabaseId)
            DebugViewActivity.startWithView(
                context,
                newViewController,
            )
            view = newViewController
        }
    }

    @MainThread
    suspend fun closeDebugger(animated: Boolean) {
        // suspend fun dismissViewController() {
        view?.encapsulatingActivity?.finish()

        view = null
        isDebuggerLaunched = false
//        }
//
//
//        viewController?.presentedViewController?.let {
//            it.dismiss(animated)
//            dismissViewController()
//        } ?: dismissViewController()
    }
}
