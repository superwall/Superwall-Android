package com.superwall.sdk.paywall.vc

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PaywallStatePublisher
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import kotlinx.coroutines.flow.MutableStateFlow

class PaywallViewController(var paywall: Paywall) {

    var paywallInfo: PaywallInfo? = null

    var delegate: PaywallViewControllerDelegateAdapter? = null

    var request: PresentationRequest? = null

    var paywallStatePublisher: PaywallStatePublisher? = null

    // TODO: Implement this function for real
    fun dismiss(result: PaywallResult, closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic, completion: (() -> Unit)? = null) {
//        val dismissCompletionBlock = completion
//        val paywallResult = result
//        paywall.closeReason = closeReason
//
//        delegate?.let {
//            it.didFinish(this, result, result.convertForObjc())
//        } ?: dismiss(presentationIsAnimated)
    }

    fun present(
        presenter: Activity,
        request: PresentationRequest,
        presentationStyleOverride: PaywallPresentationStyle?,
        paywallStatePublisher: MutableStateFlow<PaywallState>,
        completion: (Boolean) -> Unit
    ) {
        if (Superwall.instance.isPaywallPresented
            // TODO: Presentation santization
//            || presenter is PaywallActivity
//            || presenter.isTaskRoot
        ) {  // Not an exact equivalent of `isBeingPresented`
            return completion(false)
        }

        this.request = request
        this.paywallStatePublisher = paywallStatePublisher


//        val intent = Intent(presenter, this::class.java)
//        presenter.startActivity(intent) // Assuming `this` is an Activity

        println("!!! Presenting!!!")

        completion(true)
    }





}