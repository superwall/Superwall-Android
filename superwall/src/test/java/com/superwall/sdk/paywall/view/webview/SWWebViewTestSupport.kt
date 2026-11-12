package com.superwall.sdk.paywall.view.webview

import android.content.Context
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
import io.mockk.mockk

/** An [SWWebView] with inert inputs, for tests that exercise the view itself. */
internal fun testSWWebView(
    context: Context,
    messageHandler: PaywallMessageHandler = mockk(relaxed = true),
    gameControllerEnabled: () -> Boolean = { false },
    options: PaywallOptions = PaywallOptions(),
): SWWebView =
    SWWebView(
        context = context,
        messageHandler = messageHandler,
        options = { options },
        track = {},
        currentActivity = { null },
        localResources = { emptyMap() },
        deviceLocale = { null },
        gameControllerEnabled = gameControllerEnabled,
    )
