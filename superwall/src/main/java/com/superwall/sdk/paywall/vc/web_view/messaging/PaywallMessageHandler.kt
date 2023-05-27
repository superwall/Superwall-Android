package com.superwall.sdk.paywall.vc.web_view.messaging

import android.webkit.WebView
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.vc.web_view.PaywallMessage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.net.URL
//
//interface PaywallMessageHandlerDelegate {
////    val request: PresentationRequest?
//    var paywall: Paywall
//    val paywallInfo: PaywallInfo
//    val webView: WebView
//    var loadingState: PaywallLoadingState
//    val isActive: Boolean
//
//    fun eventDidOccur(paywallWebEvent: PaywallWebEvent)
//    fun openDeepLink(url: String)
//    fun presentSafariInApp(url: String)
//    fun presentSafariExternal(url: String)
//}
//
//class PaywallMessageHandler(
//    private val delegate: PaywallMessageHandlerDelegate?,
////    private val sessionEventsManager: SessionEventsManager,
//    private val factory: VariablesFactory
//) {
//    fun handle(message: PaywallMessage) {
//        val paywall = delegate?.paywall ?: return
//        when (message) {
//            is PaywallMessage.TemplateParamsAndUserAttributes ->
//                MainScope().launch { passTemplatesToWebView(paywall) }
//            is PaywallMessage.OnReady -> {
//                delegate.paywall.paywalljsVersion = message.paywalljsVersion
//                val loadedAt = System.currentTimeMillis()
//                MainScope().launch { didLoadWebView(paywall, loadedAt) }
//            }
//            is PaywallMessage.Close -> {
//                hapticFeedback()
//                delegate.eventDidOccur(PaywallWebEvent.Closed)
//            }
//            is PaywallMessage.OpenUrl -> openUrl(message.url)
//            is PaywallMessage.OpenUrlInSafari -> openUrlInSafari(message.url)
//            is PaywallMessage.OpenDeepLink -> openDeepLink(message.url)
//            is PaywallMessage.Restore -> restorePurchases()
//            is PaywallMessage.Purchase -> purchaseProduct(withId = message.productId)
//            is PaywallMessage.Custom -> handleCustomEvent(message.data)
//        }
//    }
//
//    private suspend fun passTemplatesToWebView(paywall: Paywall) {
//        val params = delegate?.request?.presentationInfo?.eventData?.parameters
//        val templates = TemplateLogic.getBase64EncodedTemplates(
//            paywall,
//            params,
//            factory
//        )
//
//        val templateScript = "window.paywall.accept64('$templates');"
//        delegate?.webView?.evaluateJavascript(templateScript, null)
//    }
//
//    private suspend fun didLoadWebView(paywall: Paywall, loadedAt: Long) {
//        delegate?.paywall?.webviewLoadingInfo?.endAt = loadedAt
//        // TODO: Implement tracking code with coroutines here
//
//        val params = delegate?.request?.presentationInfo?.eventData?.parameters
//        val templates = TemplateLogic.getBase64EncodedTemplates(
//            paywall,
//            params,
//            factory
//        )
//
//        val scriptSrc = "window.paywall.accept64('$templates');"
//        delegate?.webView?.evaluateJavascript(scriptSrc, null)
//
//        // TODO: Implement additional configuration here
//    }
//
//    private fun openUrl(url: URL) {
//        // TODO: Implement open URL
//    }
//
//    private fun openUrlInSafari(url: URL) {
//        // TODO: Implement open URL in Safari
//    }
//
//    private fun openDeepLink(url: URL) {
//        // TODO: Implement open Deep Link
//    }
//
//    private fun restorePurchases() {
//        // No op
//    }
//
//    private fun purchaseProduct(withId: String) {
//        // TODO: Implement purchase product with ID
//    }
//
//    private fun handleCustomEvent(customEvent: String) {
//        // TODO: Implement handle custom event
//    }
//}