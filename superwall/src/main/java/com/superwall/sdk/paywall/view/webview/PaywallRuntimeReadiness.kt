package com.superwall.sdk.paywall.view.webview

/**
 * Whether the page being loaded has confirmed that its paywall runtime started.
 *
 * This is what decides if a failed runtime request means the page failed. It is not the same
 * as the paywall's loading state being Ready: with optimistic loading the SDK marks a paywall
 * Ready as soon as its page finishes, which a failed page does too, and that says nothing about
 * the runtime. A Ready the SDK announced that way is announced here first with
 * [optimisticReadySent], so the report it causes isn't taken for a confirmation.
 * Not thread-safe; call it from the main thread.
 */
internal class PaywallRuntimeReadiness {
    var confirmed = false
        private set

    // Optimistic Ready states sent whose reports haven't come back yet.
    private var optimisticReports = 0

    /** A page starts loading, including a retry or a fallback URL: nothing is confirmed yet. */
    fun pageLoadStarted() {
        confirmed = false
    }

    /** The SDK is about to mark the paywall Ready because its page finished. */
    fun optimisticReadySent() {
        optimisticReports += 1
    }

    /** The paywall's loading state became Ready. */
    fun readyReported() {
        if (optimisticReports > 0) {
            optimisticReports -= 1
        } else {
            confirmed = true
        }
    }
}
