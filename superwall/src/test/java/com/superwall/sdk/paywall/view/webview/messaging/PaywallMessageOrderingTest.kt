package com.superwall.sdk.paywall.view.webview.messaging

import android.app.Activity
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.paywall.presentation.CustomCallbackRegistry
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.templating.models.JsonVariables
import com.superwall.sdk.paywall.view.webview.templating.models.Variables
import com.superwall.sdk.permissions.PermissionStatus
import com.superwall.sdk.permissions.PermissionType
import com.superwall.sdk.permissions.UserPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * The paywall runtime treats a `template_variables` message that lands after
 * `paywall_open` as a fresh load, then discards later `page_view`s. These tests
 * pin that open waits for an in-flight template send, without serializing
 * unrelated events like `transaction_start`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallMessageOrderingTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingDelegate(
        initial: PaywallViewState,
    ) : PaywallMessageHandlerDelegate {
        private var _state: PaywallViewState = initial
        override val state: PaywallViewState
            get() = _state

        val evaluations = mutableListOf<String>()

        override fun updateState(update: PaywallViewState.Updates) {
            _state = update.transform(_state)
        }

        override fun eventDidOccur(paywallWebEvent: PaywallWebEvent) {}

        override fun openDeepLink(url: String) {}

        override fun presentBrowserInApp(url: String) {}

        override fun presentBrowserExternal(url: String) {}

        override fun evaluate(
            code: String,
            resultCallback: ((String?) -> Unit)?,
        ) {
            evaluations.add(code)
            resultCallback?.invoke(null)
        }

        override fun presentPaymentSheet(url: String) {}
    }

    private class SlowVariablesFactory(
        private val fail: Boolean = false,
        private val delaysMs: List<Long> = listOf(TEMPLATE_BUILD_MS),
    ) : VariablesFactory {
        private val remaining = delaysMs.toMutableList()

        override suspend fun makeJsonVariables(
            products: List<ProductVariable>?,
            computedPropertyRequests: List<ComputedPropertyRequest>,
            event: EventData?,
        ): JsonVariables {
            val wait = if (remaining.isEmpty()) delaysMs.last() else remaining.removeAt(0)
            delay(wait)
            if (fail) throw IllegalStateException("could not build the templates")
            return JsonVariables("template_variables", Variables(emptyMap(), emptyMap(), emptyMap()))
        }
    }

    private class FakeUserPermissions : UserPermissions {
        override fun hasPermission(permission: PermissionType): PermissionStatus = PermissionStatus.GRANTED

        override suspend fun requestPermission(
            activity: Activity,
            permission: PermissionType,
        ): PermissionStatus = PermissionStatus.GRANTED
    }

    private fun createHandler(
        failTemplateBuild: Boolean = false,
        buildDelayMs: Long = TEMPLATE_BUILD_MS,
        delaysMs: List<Long>? = null,
    ): PaywallMessageHandler =
        PaywallMessageHandler(
            factory =
                SlowVariablesFactory(
                    fail = failTemplateBuild,
                    delaysMs = delaysMs ?: listOf(buildDelayMs),
                ),
            options =
                object : OptionsFactory {
                    override fun makeSuperwallOptions(): SuperwallOptions = SuperwallOptions()
                },
            track = { _: TrackableSuperwallEvent -> },
            setAttributes = { },
            getView = { null },
            mainScope = MainScope(testDispatcher),
            ioScope = IOScope(testDispatcher),
            encodeToB64 = { it },
            userPermissions = FakeUserPermissions(),
            getActivity = { null },
            customCallbackRegistry = CustomCallbackRegistry(),
        )

    private fun List<String>.indexOfMessage(needle: String): Int = indexOfFirst { it.contains(needle) }

    private fun assertTemplatesPrecedeOpen(evaluations: List<String>) {
        val templates = evaluations.indexOfMessage(TEMPLATE_VARIABLES)
        val open = evaluations.indexOfMessage(PAYWALL_OPEN)
        assertTrue("template_variables was never sent to the webview", templates >= 0)
        assertTrue("paywall_open was never sent to the webview", open >= 0)
        assertTrue(
            "paywall_open (index $open) overtook template_variables (index $templates)",
            templates < open,
        )
    }

    @Test
    fun templateVariablesReachWebviewBeforePaywallOpenOnAPreloadedPaywall() =
        runTest {
            Given("a paywall whose webview has already loaded") {
                val state = PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US")
                val delegate = RecordingDelegate(state)
                delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
                val handler = createHandler()
                handler.messageHandler = delegate

                When("presentation sends the templates and the open follows immediately") {
                    handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
                    handler.handle(PaywallMessage.PaywallOpen)
                    advanceUntilIdle()

                    Then("the templates reach the webview first") {
                        assertTemplatesPrecedeOpen(delegate.evaluations)
                    }
                }
            }
        }

    @Test
    fun templateVariablesReachWebviewBeforePaywallOpenWhenPaywallIsNotPreloaded() =
        runTest {
            Given("a paywall whose webview has just reported it is ready") {
                val state = PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US")
                val delegate = RecordingDelegate(state)
                val handler = createHandler()
                handler.messageHandler = delegate

                When("the open lands while the templates are still being built") {
                    handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                    handler.handle(PaywallMessage.PaywallOpen)
                    advanceUntilIdle()

                    Then("the templates reach the webview first") {
                        assertTemplatesPrecedeOpen(delegate.evaluations)
                    }
                }
            }
        }

    @Test
    fun deferredPaywallOpenIsSentAfterTheTemplatesOnceTheWebviewLoads() =
        runTest {
            Given("an open deferred while the webview is being recreated") {
                val state =
                    PaywallViewState(
                        paywall = Paywall.stub().copy(paywalljsVersion = null),
                        locale = "en-US",
                        isPresented = true,
                        lastOpen = Date(1),
                    )
                val delegate = RecordingDelegate(state)
                delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
                val handler = createHandler()
                handler.messageHandler = delegate

                When("the replacement webview finishes loading") {
                    handler.resetForWebViewReload()
                    handler.flushPendingMessages()
                    advanceUntilIdle()
                    assertTrue(
                        "the deferred open was sent before the webview loaded",
                        delegate.evaluations.indexOfMessage(PAYWALL_OPEN) < 0,
                    )

                    handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                    advanceUntilIdle()

                    Then("the open is delivered after the templates") {
                        assertTemplatesPrecedeOpen(delegate.evaluations)
                    }
                }
            }
        }

    @Test
    fun flushWhileTheWebviewIsUnloadedLeavesTheOpenQueued() =
        runTest {
            Given("a crash-recovery open queued against an unloaded webview") {
                val state =
                    PaywallViewState(
                        paywall = Paywall.stub().copy(paywalljsVersion = null),
                        locale = "en-US",
                        isPresented = true,
                        lastOpen = Date(1),
                    )
                val delegate = RecordingDelegate(state)
                val handler = createHandler()
                handler.messageHandler = delegate
                handler.resetForWebViewReload()

                When("flush runs before OnReady") {
                    handler.flushPendingMessages()
                    advanceUntilIdle()

                    Then("the open stays queued and the flush returns") {
                        assertEquals(-1, delegate.evaluations.indexOfMessage(PAYWALL_OPEN))
                        assertTrue(delegate.evaluations.isEmpty())
                    }
                }
            }
        }

    @Test
    fun overlappingTemplateSendsBothFinishBeforePaywallOpen() =
        runTest {
            Given("initialization is still building templates") {
                val state = PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US")
                val delegate = RecordingDelegate(state)
                val handler =
                    createHandler(
                        // First build (OnReady) is slower than the second so they
                        // finish out of arrival order if open only waits on the latest.
                        delaysMs = listOf(1_000L, 50L),
                    )
                handler.messageHandler = delegate

                When("a second template send starts before the first finishes, then open arrives") {
                    handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                    runCurrent()
                    handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
                    handler.handle(PaywallMessage.PaywallOpen)
                    advanceUntilIdle()

                    Then("both template payloads reach the webview before paywall_open") {
                        val templateCount = delegate.evaluations.count { it.contains(TEMPLATE_VARIABLES) }
                        assertEquals(2, templateCount)
                        val lastTemplate =
                            delegate.evaluations.indexOfLast { it.contains(TEMPLATE_VARIABLES) }
                        val open = delegate.evaluations.indexOfMessage(PAYWALL_OPEN)
                        assertTrue(lastTemplate >= 0)
                        assertTrue(open >= 0)
                        assertTrue(
                            "paywall_open (index $open) overtook a template send (last index $lastTemplate)",
                            lastTemplate < open,
                        )
                    }
                }
            }
        }

    @Test
    fun aFailedTemplateBuildDoesNotDropPaywallOpen() =
        runTest {
            Given("a paywall whose template build throws") {
                val state = PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US")
                val delegate = RecordingDelegate(state)
                delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
                val handler = createHandler(failTemplateBuild = true)
                handler.messageHandler = delegate

                When("an open is waiting on the failing template send") {
                    handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
                    handler.handle(PaywallMessage.PaywallOpen)
                    advanceUntilIdle()

                    Then("the open is still delivered") {
                        assertTrue(
                            "paywall_open was lost behind a failed template send",
                            delegate.evaluations.indexOfMessage(PAYWALL_OPEN) >= 0,
                        )
                    }
                }
            }
        }

    @Test
    fun aHungTemplateBuildDoesNotSilencePaywallOpen() =
        runTest {
            Given("a template build that never finishes") {
                val state = PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US")
                val delegate = RecordingDelegate(state)
                delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
                val handler = createHandler(buildDelayMs = 30_000L)
                handler.messageHandler = delegate

                When("open waits on that build") {
                    handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
                    handler.handle(PaywallMessage.PaywallOpen)
                    advanceUntilIdle()

                    Then("open is still sent after the wait times out") {
                        assertTrue(
                            "paywall_open was lost behind a hung template send",
                            delegate.evaluations.indexOfMessage(PAYWALL_OPEN) >= 0,
                        )
                    }
                }
            }
        }

    @Test
    fun transactionStartIsNotHeldBehindSlowTemplates() =
        runTest {
            val delegate =
                RecordingDelegate(
                    PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US"),
                )
            delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
            val handler = createHandler()
            handler.messageHandler = delegate

            handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
            handler.handle(PaywallMessage.TransactionStart)
            runCurrent()

            assertTrue(
                "transaction_start should not wait on template construction",
                delegate.evaluations.indexOfMessage("transaction_start") >= 0,
            )
            assertEquals(-1, delegate.evaluations.indexOfMessage(TEMPLATE_VARIABLES))

            handler.handle(PaywallMessage.PaywallOpen)
            advanceUntilIdle()

            assertTemplatesPrecedeOpen(delegate.evaluations)
        }

    @Test
    fun slowInitializationFinishesBeforeOpeningANewPaywall() =
        runTest {
            for (openBeforeReady in listOf(false, true)) {
                val delegate = RecordingDelegate(PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US"))
                val handler = createHandler(buildDelayMs = 3_000L)
                handler.messageHandler = delegate

                if (openBeforeReady) handler.handle(PaywallMessage.PaywallOpen)
                handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                if (!openBeforeReady) handler.handle(PaywallMessage.PaywallOpen)
                advanceUntilIdle()

                assertTemplatesPrecedeOpen(delegate.evaluations)
                assertEquals(PaywallLoadingState.Ready, delegate.state.loadingState)
            }
        }

    @Test
    fun recoveryDoesNotOpenCachedOrBackgroundedPaywalls() =
        runTest {
            for (presented in listOf(false, true)) {
                val delegate =
                    RecordingDelegate(
                        PaywallViewState(
                            paywall = Paywall.stub().copy(paywalljsVersion = null),
                            locale = "en-US",
                            isPresented = presented,
                            closedForBackground = presented,
                        ),
                    )
                val handler = createHandler()
                handler.messageHandler = delegate

                handler.resetForWebViewReload()
                handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                advanceUntilIdle()

                assertTrue(delegate.evaluations.indexOfMessage(TEMPLATE_VARIABLES) >= 0)
                assertEquals(-1, delegate.evaluations.indexOfMessage(PAYWALL_OPEN))
            }
        }

    @Test
    fun recoveryDoesNotReopenAPaywallDismissedOrBackgroundedWhileLoading() =
        runTest {
            for (update in listOf(PaywallViewState.Updates.CleanupAfterDestroy, PaywallViewState.Updates.SetClosedForBackground(true))) {
                val delegate =
                    RecordingDelegate(
                        PaywallViewState(
                            paywall = Paywall.stub().copy(paywalljsVersion = null),
                            locale = "en-US",
                            isPresented = true,
                            lastOpen = Date(1),
                        ),
                    )
                val handler = createHandler()
                handler.messageHandler = delegate

                handler.resetForWebViewReload()
                handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
                handler.flushPendingMessages()
                runCurrent()
                delegate.updateState(update)
                advanceUntilIdle()

                assertEquals(-1, delegate.evaluations.indexOfMessage(PAYWALL_OPEN))
            }
        }

    @Test
    fun recoveryOpenDoesNotCarryOverToANewPresentation() =
        runTest {
            val delegate =
                RecordingDelegate(
                    PaywallViewState(
                        paywall = Paywall.stub().copy(paywalljsVersion = null),
                        locale = "en-US",
                        isPresented = true,
                        lastOpen = Date(1),
                    ),
                )
            val handler = createHandler()
            handler.messageHandler = delegate
            handler.resetForWebViewReload()
            delegate.updateState(PaywallViewState.Updates.CleanupAfterDestroy)
            delegate.updateState(PaywallViewState.Updates.SetPresentedAndFinished)
            delegate.updateState(PaywallViewState.Updates.SetLastOpen)

            handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
            handler.handle(PaywallMessage.PaywallOpen)
            advanceUntilIdle()

            assertTemplatesPrecedeOpen(delegate.evaluations)
            assertEquals(1, delegate.evaluations.count { it.contains(PAYWALL_OPEN) })
        }

    @Test
    fun replacingWebViewCancelsOldTemplates() =
        runTest {
            val delegate = RecordingDelegate(PaywallViewState(paywall = Paywall.stub().copy(paywalljsVersion = null), locale = "en-US"))
            delegate.updateState(PaywallViewState.Updates.SetPaywallJsVersion(PAYWALL_JS_VERSION))
            val handler = createHandler()
            handler.messageHandler = delegate
            handler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
            runCurrent()

            handler.resetForWebViewReload()
            assertNull(delegate.state.paywall.paywalljsVersion)
            handler.handle(PaywallMessage.PaywallOpen)
            handler.flushPendingMessages()
            advanceUntilIdle()
            assertEquals(-1, delegate.evaluations.indexOfMessage(TEMPLATE_VARIABLES))
            assertEquals(-1, delegate.evaluations.indexOfMessage(PAYWALL_OPEN))

            handler.handle(PaywallMessage.OnReady(paywallJsVersion = PAYWALL_JS_VERSION))
            advanceUntilIdle()

            assertTemplatesPrecedeOpen(delegate.evaluations)
            assertEquals(1, delegate.evaluations.count { it.contains(TEMPLATE_VARIABLES) })
        }

    private companion object {
        const val TEMPLATE_BUILD_MS = 500L
        const val PAYWALL_JS_VERSION = "3.0.0"
        const val TEMPLATE_VARIABLES = "template_variables"
        const val PAYWALL_OPEN = "paywall_open"
    }
}
