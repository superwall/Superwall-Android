package com.superwall.sdk.paywall.view.webview.messaging

import android.app.Activity
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.dependencies.attachMockGraph
import com.superwall.sdk.logger.LogQueue
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.paywall.presentation.CustomCallbackRegistry
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.webview.templating.models.JsonVariables
import com.superwall.sdk.paywall.view.webview.templating.models.Variables
import com.superwall.sdk.permissions.PermissionStatus
import com.superwall.sdk.permissions.PermissionType
import com.superwall.sdk.permissions.UserPermissions
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `WebView.evaluateJavascript` reports a script's return value. `accept64` returns nothing,
 * which Android reports as the string "null" after every successful post: that must not be
 * logged as "Error Evaluating JS".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvaluateResultLogTest {
    private val errorLogs = CopyOnWriteArrayList<String?>()
    private lateinit var container: DependencyContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LogQueue.synchronous = true
        val adapter = SuperwallDelegateAdapter()
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun handleLog(
                    level: String,
                    scope: String,
                    message: String?,
                    info: Map<String, Any>?,
                    error: Throwable?,
                ) {
                    if (message == "Error Evaluating JS") errorLogs += error?.message
                }
            }
        container = attachMockGraph()
        every { container.delegateAdapter } returns adapter
    }

    @After
    fun tearDown() {
        SuperwallGraph.detach(container)
        LogQueue.synchronous = false
        SuperwallDelegateAdapter()
        Dispatchers.resetMain()
    }

    @Test
    fun `a script that returns nothing is not logged as an error`() =
        runTest {
            postMessage(evaluationResult = "null")

            assertEquals(emptyList<String?>(), errorLogs.toList())
        }

    @Test
    fun `a missing result is not logged as an error`() =
        runTest {
            postMessage(evaluationResult = null)

            assertEquals(emptyList<String?>(), errorLogs.toList())
        }

    @Test
    fun `any other result is still logged`() =
        runTest {
            postMessage(evaluationResult = "\"unexpected\"")

            assertEquals(listOf<String?>("\"unexpected\""), errorLogs.toList())
        }

    private suspend fun postMessage(evaluationResult: String?) {
        val handler =
            PaywallMessageHandler(
                factory = NoVariables(),
                options =
                    object : OptionsFactory {
                        override fun makeSuperwallOptions(): SuperwallOptions = SuperwallOptions()
                    },
                track = { },
                setAttributes = { },
                getView = { null },
                mainScope = MainScope(Dispatchers.Unconfined),
                ioScope = IOScope(Dispatchers.Unconfined),
                encodeToB64 = { it },
                userPermissions = GrantedPermissions(),
                getActivity = { null },
                customCallbackRegistry = CustomCallbackRegistry(),
            )
        handler.messageHandler =
            ResultDelegate(PaywallViewState(paywall = Paywall.stub(), locale = "en-US"), evaluationResult)

        // Posts through passMessageToWebView, the same path as a callback_result.
        handler.passEventTrackingBehaviorToWebView(EventTrackingBehavior.ALL)
    }

    private class ResultDelegate(
        override val state: PaywallViewState,
        private val evaluationResult: String?,
    ) : PaywallMessageHandlerDelegate {
        override fun updateState(update: PaywallViewState.Updates) {}

        override fun eventDidOccur(paywallWebEvent: PaywallWebEvent) {}

        override fun openDeepLink(url: String) {}

        override fun presentBrowserInApp(url: String) {}

        override fun presentBrowserExternal(url: String) {}

        override fun evaluate(
            code: String,
            resultCallback: ((String?) -> Unit)?,
        ) {
            resultCallback?.invoke(evaluationResult)
        }

        override fun presentPaymentSheet(url: String) {}
    }

    private class NoVariables : VariablesFactory {
        override suspend fun makeJsonVariables(
            products: List<ProductVariable>?,
            computedPropertyRequests: List<ComputedPropertyRequest>,
            event: EventData?,
        ): JsonVariables = JsonVariables("template_variables", Variables(emptyMap(), emptyMap(), emptyMap()))
    }

    private class GrantedPermissions : UserPermissions {
        override fun hasPermission(permission: PermissionType): PermissionStatus = PermissionStatus.GRANTED

        override suspend fun requestPermission(
            activity: Activity,
            permission: PermissionType,
        ): PermissionStatus = PermissionStatus.GRANTED
    }
}
