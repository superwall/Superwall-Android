package com.superwall.sdk.paywall.manager

import android.view.View
import android.view.ViewGroup
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallURL
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallViewDelegateAdapter
import com.superwall.sdk.paywall.view.webview.PaywallUIDelegate
import com.superwall.sdk.paywall.view.webview.PaywallWebUI
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.web.WebPaywallRedeemer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Regression test: two campaigns that share one paywall must not report each
 * other's experiment. The cached [PaywallView] is shared per paywall identifier,
 * so an in-flight request for campaign A must not rewrite the experiment that
 * campaign B's presentation reports in `paywall_open` and transaction events.
 *
 * Replays the interleaving from the iOS report with real coroutines:
 * B fetches the view, A fetches the same view, A binds its request, B binds its request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaywallManagerExperimentIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var paywallRequestManager: PaywallRequestManager
    private lateinit var paywallManager: PaywallManager
    private lateinit var viewFactory: PaywallView.Factory
    private var cachedView: PaywallView? = null

    private val paywallId = "shared_paywall"
    private val experimentA = experiment("181395")
    private val experimentB = experiment("180872")

    private fun experiment(id: String) =
        Experiment(
            id = id,
            groupId = "group",
            variant = Experiment.Variant(id = "v_$id", type = Experiment.Variant.VariantType.TREATMENT, paywallId = paywallId),
        )

    @Before
    fun setUp() {
        viewFactory = mockk(relaxed = true)
        every { viewFactory.makeSuperwallOptions() } returns SuperwallOptions()
        every { viewFactory.updatePaywallInfo(any()) } just Runs
        every { viewFactory.getCurrentUserAttributes() } returns emptyMap()
        coEvery { viewFactory.track(any()) } returns Result.success(mockk<TrackingResult>(relaxed = true))

        val cache =
            mockk<PaywallViewCache>(relaxed = true) {
                every { getPaywallView(any()) } answers { cachedView }
                every { save(any(), any()) } answers { cachedView = firstArg() }
            }
        val deviceInfo = mockk<DeviceInfo> { every { locale } returns "en_US" }
        val managerFactory =
            mockk<PaywallManager.Factory> {
                every { makeCache() } returns cache
                every { makeDeviceInfo() } returns deviceInfo
                every { mainScope() } returns MainScope(Dispatchers.Unconfined)
                coEvery { makePaywallView(any(), any(), any()) } coAnswers { makeRealView(firstArg()) }
            }
        paywallRequestManager = mockk()
        paywallManager = PaywallManager(managerFactory, paywallRequestManager)
    }

    private fun makeRealView(paywall: Paywall): PaywallView {
        val controller = PaywallView.PaywallController(PaywallViewState(paywall = paywall, locale = "en_US"))
        val webUI = FakePaywallWebUI(mockk(relaxed = true))
        return PaywallView(
            context = context,
            eventCallback = null,
            callback = null,
            deviceHelper = mockk<DeviceHelper>(relaxed = true),
            factory = viewFactory,
            storage = mockk<LocalStorage>(relaxed = true),
            webView = webUI,
            cache = null,
            controller = controller,
            sendMessages = mockk(relaxed = true),
            redeemer = mockk<WebPaywallRedeemer>(relaxed = true),
        )
    }

    private fun paywallRequest(
        experiment: Experiment,
        eventName: String,
        source: String,
    ) = PaywallRequest(
        eventData = EventData(name = eventName, parameters = emptyMap(), createdAt = Date()),
        responseIdentifiers = ResponseIdentifiers(paywallId = paywallId, experiment = experiment),
        overrides = PaywallRequest.Overrides(products = null, isFreeTrial = null),
        isDebuggerLaunched = false,
        presentationSourceType = source,
        retryCount = 0,
    )

    private fun presentationRequest(
        info: PresentationInfo,
        type: PresentationRequestType,
    ) = PresentationRequest(
        presentationInfo = info,
        presenter = null,
        paywallOverrides = null,
        flags =
            PresentationRequest.Flags(
                isDebuggerLaunched = false,
                entitlements = MutableStateFlow(null),
                isPaywallPresented = false,
                type = type,
            ),
    )

    @Test
    fun `interleaved requests for a shared paywall each report their own experiment`() =
        runTest {
            Given("a shared paywall, an implicit session_start request for experiment B and a getPaywall request for experiment A") {
                val base = Paywall.stub().copy(identifier = paywallId)
                // The request manager returns the paywall stamped with each request's experiment,
                // as PaywallRequestManager.updatePaywall does, after a controllable delay.
                val gates = mapOf(experimentA.id to CompletableDeferred<Unit>(), experimentB.id to CompletableDeferred<Unit>())
                coEvery { paywallRequestManager.getPaywall(any(), any()) } coAnswers {
                    val req = firstArg<PaywallRequest>()
                    val exp = req.responseIdentifiers.experiment!!
                    gates.getValue(exp.id).await()
                    Either.Success(base.copy(experiment = exp, presentationSourceType = req.presentationSourceType))
                }

                val eventB = EventData(name = "session_start", parameters = emptyMap(), createdAt = Date())
                val eventA = EventData(name = "campaign_trigger", parameters = emptyMap(), createdAt = Date())
                val requestB = presentationRequest(PresentationInfo.ImplicitTrigger(eventB), PresentationRequestType.Presentation)
                val requestA =
                    presentationRequest(
                        PresentationInfo.ExplicitTrigger(eventA),
                        PresentationRequestType.GetPaywall(mockk(relaxed = true)),
                    )
                val bindA = CompletableDeferred<Unit>()
                val bindB = CompletableDeferred<Unit>()
                val publisher = MutableSharedFlow<PaywallState>()
                var viewA: PaywallView? = null
                var viewB: PaywallView? = null

                When("B fetches the view, A fetches the same view, A binds, then B binds") {
                    val pipelineB =
                        launch {
                            val view =
                                (
                                    paywallManager.getPaywallView(
                                        paywallRequest(experimentB, "session_start", "implicit"),
                                        isForPresentation = true,
                                        isPreloading = false,
                                        delegate = null,
                                    ) as Either.Success
                                ).value
                            viewB = view
                            bindB.await() // the async gap before presentPaywallView -> present -> set
                            view.set(requestB, publisher, null, experimentB)
                        }
                    val pipelineA =
                        launch {
                            val view =
                                (
                                    paywallManager.getPaywallView(
                                        paywallRequest(experimentA, "campaign_trigger", "getPaywall"),
                                        isForPresentation = true,
                                        isPreloading = false,
                                        delegate = mockk<PaywallViewDelegateAdapter>(relaxed = true),
                                    ) as Either.Success
                                ).value
                            viewA = view
                            bindA.await() // the async gap before InternalGetPaywall binds the request
                            view.set(requestA, publisher, null, experimentA)
                        }

                    gates.getValue(experimentB.id).complete(Unit)
                    testScheduler.advanceUntilIdle()
                    gates.getValue(experimentA.id).complete(Unit)
                    testScheduler.advanceUntilIdle()
                    bindA.complete(Unit)
                    testScheduler.advanceUntilIdle()

                    Then("both pipelines got the same cached view and A's binding reports A") {
                        assertSame(viewA, viewB)
                        val infoA = viewA!!.info
                        assertEquals("campaign_trigger", infoA.presentedByEventWithName)
                        assertEquals(experimentA.id, infoA.experiment?.id)
                        assertEquals("getPaywall", infoA.presentationSourceType)
                    }

                    bindB.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    pipelineA.join()
                    pipelineB.join()

                    Then("B's binding reports B's experiment and source, not A's") {
                        val infoB = viewB!!.info
                        assertEquals("session_start", infoB.presentedByEventWithName)
                        assertEquals(experimentB.id, infoB.experiment?.id)
                        assertEquals("implicit", infoB.presentationSourceType)
                    }
                }
            }
        }

    private inner class FakePaywallWebUI(
        override val messageHandler: PaywallMessageHandler,
    ) : PaywallWebUI {
        override var delegate: PaywallUIDelegate? = null
        override var onScrollChangeListener: PaywallWebUI.OnScrollChangeListener? = null
        private val view = View(context)

        override fun onView(perform: View.() -> Unit) = perform(view)

        override fun enableBackgroundRendering() = Unit

        override fun scrollBy(
            x: Int,
            y: Int,
        ) = Unit

        override fun scrollTo(
            x: Int,
            y: Int,
        ) = Unit

        override fun setup(
            url: PaywallURL,
            onRenderCrashed: (Boolean, Int) -> Unit,
        ) = Unit

        override fun evaluate(
            code: String,
            resultCallback: ((String?) -> Unit)?,
        ) {
            resultCallback?.invoke(null)
        }

        override fun destroyView() = Unit

        override fun detach(fromView: ViewGroup) = Unit

        override fun attach(toView: ViewGroup) = Unit
    }
}
