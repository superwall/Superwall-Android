package com.superwall.sdk.paywall.vc

import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult

class PaywallViewController {

    var paywallInfo: PaywallInfo? = null



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

}