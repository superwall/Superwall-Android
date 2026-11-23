package com.superwall.sdk.paywall.view.webview.messaging

import android.app.Activity
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The templates hold the user's attributes and customer info. Logs reach the app's
 * `SuperwallDelegate.handleLog`, so the templates and the scripts that carry them must not be
 * logged, only posted to the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateLogPrivacyTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val scripts = CopyOnWriteArrayList<String>()
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
                    logs += "$message $info ${error?.message}"
                }
            }
        container = attachMockGraph()
        every { container.delegateAdapter } returns adapter
    }

    @After
    fun tearDown() {
        SuperwallGraph.detach(container)
        LogQueue.synchronous = false
        Dispatchers.resetMain()
    }

    @Test
    fun `templates reach the page but not the logs`() =
        runTest {
            val handler = handler()

            handler.handle(PaywallMessage.OnReady(paywallJsVersion = "1"))
            handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
            advanceUntilIdle()

            assertTrue("the templates were posted", scripts.count { MARKER in it } >= 2)
            assertTrue("something was logged", logs.isNotEmpty())
            assertTrue(
                "no log holds the customer info: ${logs.filter { MARKER in it }}",
                logs.none { MARKER in it },
            )
        }

    private fun handler(): PaywallMessageHandler {
        val handler =
            PaywallMessageHandler(
                factory = CustomerInfoVariables(),
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
        // An unexpected evaluation result makes the handler log the script's details too.
        handler.messageHandler =
            RecordingDelegate(PaywallViewState(paywall = Paywall.stub(), locale = "en-US"), scripts)
        return handler
    }

    private class RecordingDelegate(
        override val state: PaywallViewState,
        private val scripts: MutableList<String>,
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
            scripts += code
            resultCallback?.invoke("\"unexpected\"")
        }

        override fun presentPaymentSheet(url: String) {}
    }

    private class CustomerInfoVariables : VariablesFactory {
        override suspend fun makeJsonVariables(
            products: List<ProductVariable>?,
            computedPropertyRequests: List<ComputedPropertyRequest>,
            event: EventData?,
        ): JsonVariables =
            Variables(
                user = mapOf("email" to "$MARKER@example.com"),
                device = mapOf("customerInfo" to mapOf("userId" to MARKER)),
                params = emptyMap(),
            ).templated()
    }

    private class GrantedPermissions : UserPermissions {
        override fun hasPermission(permission: PermissionType): PermissionStatus = PermissionStatus.GRANTED

        override suspend fun requestPermission(
            activity: Activity,
            permission: PermissionType,
        ): PermissionStatus = PermissionStatus.GRANTED
    }

    private companion object {
        const val MARKER = "private-marker-123"
    }
}
