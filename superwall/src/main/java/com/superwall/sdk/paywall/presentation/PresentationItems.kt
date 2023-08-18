package com.superwall.sdk.paywall.presentation

import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PresentationItems {
    private var _last: LastPresentationItems? = null
    private val lastMutex = Mutex()

    suspend fun getLast(): LastPresentationItems? = lastMutex.withLock { _last }
    suspend fun setLast(value: LastPresentationItems?) = lastMutex.withLock { _last = value }

    private var _paywallInfo: PaywallInfo? = null
    private val paywallInfoMutex = Mutex()

    suspend fun getPaywallInfo(): PaywallInfo? = paywallInfoMutex.withLock { _paywallInfo }
    suspend fun setPaywallInfo(value: PaywallInfo?) = paywallInfoMutex.withLock { _paywallInfo = value }

    suspend fun reset() {
        withContext(Dispatchers.Default) {
            lastMutex.withLock { _last = null }
            paywallInfoMutex.withLock { _paywallInfo = null }
        }
    }
}

// Items involved in the last successful paywall presentation request.
data class LastPresentationItems(
    // The last paywall presentation request.
    val request: PresentationRequest,

    // The last state publisher.
    val statePublisher: kotlinx.coroutines.flow.MutableStateFlow<PaywallState>
)
