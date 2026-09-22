package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.assertTrue
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.product.CrossplatformProduct
import com.superwall.sdk.models.product.Offer
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.survey.SurveyPresentationResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.Date

class PaywallViewStateTest {
    private fun makeState(paywall: Paywall = Paywall.stub()): PaywallViewState = PaywallViewState(paywall = paywall, locale = "en-US")

    private fun makeRequest(): PresentationRequest {
        val info =
            PresentationInfo.ExplicitTrigger(
                EventData(name = "evt", parameters = emptyMap(), createdAt = java.util.Date()),
            )
        val flags =
            PresentationRequest.Flags(
                isDebuggerLaunched = false,
                entitlements = MutableStateFlow(null),
                isPaywallPresented = false,
                type = PresentationRequestType.Presentation,
            )
        return PresentationRequest(
            presentationInfo = info,
            presenter = null,
            paywallOverrides = null,
            flags = flags,
        )
    }

    @Test
    fun presentationWillBegin_setsCloseReasonWithoutMutatingOriginal() {
        Given("an initial state with a paywall closeReason not None") {
            val initialPaywall = Paywall.stub().copy(closeReason = PaywallCloseReason.SystemLogic)
            val state = makeState(initialPaywall)

            When("PresentationWillBegin is applied") {
                val newState = PaywallViewState.Updates.PresentationWillBegin.transform(state)

                Then("it sets closeReason to None and does not mutate original") {
                    // New state has a new Paywall instance
                    assertNotSame(state.paywall, newState.paywall)
                    // New state closeReason is None
                    assertEquals(PaywallCloseReason.None, newState.paywall.closeReason)
                    // Old state's paywall remains unchanged
                    assertEquals(PaywallCloseReason.SystemLogic, state.paywall.closeReason)
                    // Callback reset
                    assertTrue(!newState.callbackInvoked)
                }
            }
        }
    }

    @Test
    fun shimmerStarted_setsStartAtWithoutMutatingOriginal() {
        Given("an initial state with null shimmer startAt") {
            val state = makeState()

            When("ShimmerStarted is applied") {
                val newState = PaywallViewState.Updates.ShimmerStarted.transform(state)

                Then("it sets startAt and leaves original untouched") {
                    assertNotSame(state.paywall, newState.paywall)
                    assertEquals(null, state.paywall.shimmerLoadingInfo.startAt)
                    assertTrue(newState.paywall.shimmerLoadingInfo.startAt != null)
                    // presentationWillPrepare becomes false
                    assertEquals(false, newState.presentationWillPrepare)
                }
            }
        }
    }

    @Test
    fun shimmerEnded_setsEndAtWithoutMutatingOriginal() {
        Given("an initial state with null shimmer endAt") {
            val state = makeState()

            When("ShimmerEnded is applied") {
                val newState = PaywallViewState.Updates.ShimmerEnded.transform(state)

                Then("it sets endAt and leaves original untouched") {
                    assertNotSame(state.paywall, newState.paywall)
                    assertEquals(null, state.paywall.shimmerLoadingInfo.endAt)
                    assertTrue(newState.paywall.shimmerLoadingInfo.endAt != null)
                }
            }
        }
    }

    @Test
    fun webLoadingStarted_setsStartAtOnceWithoutMutatingOriginal() {
        Given("an initial state with null webview startAt") {
            val state = makeState()

            When("WebLoadingStarted is applied twice") {
                val once = PaywallViewState.Updates.WebLoadingStarted.transform(state)
                val twice = PaywallViewState.Updates.WebLoadingStarted.transform(once)

                Then("it sets startAt only once and does not mutate original") {
                    assertNotSame(state.paywall, once.paywall)
                    assertEquals(null, state.paywall.webviewLoadingInfo.startAt)
                    val firstStartAt = once.paywall.webviewLoadingInfo.startAt
                    assertTrue(firstStartAt != null)
                    assertEquals(firstStartAt, twice.paywall.webviewLoadingInfo.startAt)
                }
            }
        }
    }

    @Test
    fun webLoadingFailed_setsFailAtOnceWithoutMutatingOriginal() {
        Given("an initial state with null webview failAt") {
            val state = makeState()

            When("WebLoadingFailed is applied twice") {
                val once = PaywallViewState.Updates.WebLoadingFailed.transform(state)
                val twice = PaywallViewState.Updates.WebLoadingFailed.transform(once)

                Then("it sets failAt only once and does not mutate original") {
                    assertNotSame(state.paywall, once.paywall)
                    assertEquals(null, state.paywall.webviewLoadingInfo.failAt)
                    val firstFailAt = once.paywall.webviewLoadingInfo.failAt
                    assertTrue(firstFailAt != null)
                    assertEquals(firstFailAt, twice.paywall.webviewLoadingInfo.failAt)
                }
            }
        }
    }

    @Test
    fun initiateDismiss_setsCloseReasonAndResultWithoutMutatingOriginal() {
        Given("an initial state with a paywall and null result") {
            val state = makeState()
            val result = PaywallResult.Declined()
            val completionCalled = arrayOf(false)

            When("InitiateDismiss is applied") {
                val newState =
                    PaywallViewState.Updates
                        .InitiateDismiss(
                            result = result,
                            closeReason = PaywallCloseReason.ManualClose,
                            completion = { completionCalled[0] = true },
                        ).transform(state)

                Then("it sets result, closeReason immutably and stores completion") {
                    assertNotSame(state.paywall, newState.paywall)
                    assertEquals(PaywallCloseReason.ManualClose, newState.paywall.closeReason)
                    // Note: default stub is None; ensure original remains None
                    assertEquals(PaywallCloseReason.None, state.paywall.closeReason)
                    assertEquals(result, newState.paywallResult)
                    assertTrue(newState.dismissCompletionBlock != null)
                }
            }
        }
    }

    @Test
    fun setPaywallJsVersion_setsVersionWithoutMutatingOriginal() {
        Given("an initial state with null paywalljsVersion") {
            val state = makeState()

            When("SetPaywallJsVersion is applied") {
                val newState = PaywallViewState.Updates.SetPaywallJsVersion("1.2.3").transform(state)

                Then("it sets the version and does not mutate original") {
                    assertNotSame(state.paywall, newState.paywall)
                    // Stub defaults to empty string for paywalljsVersion
                    assertEquals("", state.paywall.paywalljsVersion)
                    assertEquals("1.2.3", newState.paywall.paywalljsVersion)
                }
            }
        }
    }

    @Test
    fun webLoadingEnded_setsEndAtWithoutMutatingOriginal() {
        Given("an initial state with null webview endAt") {
            val state = makeState()
            val end = Date()

            When("WebLoadingEnded is applied") {
                val newState = PaywallViewState.Updates.WebLoadingEnded(end).transform(state)

                Then("it sets endAt and does not mutate original") {
                    assertNotSame(state.paywall, newState.paywall)
                    assertEquals(null, state.paywall.webviewLoadingInfo.endAt)
                    assertEquals(end, newState.paywall.webviewLoadingInfo.endAt)
                }
            }
        }
    }

    @Test
    fun mergePaywall_mergesFieldsAndProductItems() {
        Given("a cached view state and a newly fetched paywall") {
            val original = Paywall.stub()
            val state = makeState(original)

            val from =
                Paywall.stub().copy(
                    // Configure product items so that productIds get refreshed
                    _productItemsV3 =
                        listOf(
                            CrossplatformProduct(
                                compositeId = "p1:b1:sw-auto",
                                storeProduct =
                                    CrossplatformProduct.StoreProduct.PlayStore(
                                        productIdentifier = "p1",
                                        basePlanIdentifier = "b1",
                                        offer = Offer.Automatic(),
                                    ),
                                entitlements = emptyList(),
                                name = "Item1",
                            ),
                        ),
                )
            from.productVariables = listOf()
            from.isFreeTrialAvailable = true

            When("MergePaywall is applied") {
                val newState = PaywallViewState.Updates.MergePaywall(from).transform(state)

                Then("state has a new paywall with merged fields and updated productIds") {
                    assertNotSame(state.paywall, newState.paywall)
                    assertEquals(from.productVariables, newState.paywall.productVariables)
                    assertEquals(from.isFreeTrialAvailable, newState.paywall.isFreeTrialAvailable)
                    // productIds should follow the product items we set above
                    assertEquals(listOf("p1:b1:sw-auto"), newState.paywall.productIds)
                    // original remains unchanged
                    assertEquals(original.productVariables, state.paywall.productVariables)
                    assertEquals(original.productIds, state.paywall.productIds)
                }
            }
        }
    }

    @Test
    fun setLoadingState_updatesLoadingState() {
        Given("an initial state with Unknown loading state") {
            val state = makeState()

            When("SetLoadingState(LoadingURL) is applied") {
                val newState =
                    PaywallViewState.Updates
                        .SetLoadingState(PaywallLoadingState.LoadingURL)
                        .transform(state)

                Then("loadingState becomes LoadingURL") {
                    assertEquals(PaywallLoadingState.LoadingURL, newState.loadingState)
                }
            }
        }
    }

    @Test
    fun toggleSpinner_show_then_hide_transitions_between_ready_and_manual() {
        Given("a state in Ready loading state") {
            val state =
                PaywallViewState.Updates
                    .SetLoadingState(PaywallLoadingState.Ready)
                    .transform(makeState())

            When("ToggleSpinner(hidden = false) is applied") {
                val showing = PaywallViewState.Updates.ToggleSpinner(hidden = false).transform(state)

                Then("loadingState becomes ManualLoading") {
                    assertEquals(PaywallLoadingState.ManualLoading, showing.loadingState)
                }

                When("ToggleSpinner(hidden = true) is applied") {
                    val hidden = PaywallViewState.Updates.ToggleSpinner(hidden = true).transform(showing)

                    Then("loadingState returns to Ready") {
                        assertEquals(PaywallLoadingState.Ready, hidden.loadingState)
                    }
                }
            }
        }
    }

    @Test
    fun toggleSpinner_hide_when_not_manual_or_purchase_keeps_state() {
        Given("a state in LoadingURL loading state") {
            val state =
                PaywallViewState.Updates
                    .SetLoadingState(PaywallLoadingState.LoadingURL)
                    .transform(makeState())

            When("ToggleSpinner(hidden = true) is applied") {
                val hidden = PaywallViewState.Updates.ToggleSpinner(hidden = true).transform(state)

                Then("loadingState remains LoadingURL") {
                    assertEquals(PaywallLoadingState.LoadingURL, hidden.loadingState)
                }
            }
        }
    }

    @Test
    fun setPresentationConfig_applies_override_and_completion() {
        Given("a state and a completion") {
            val state = makeState()
            var completed = false
            val completion: (Boolean) -> Unit = { completed = it }

            When("SetPresentationConfig is applied with override") {
                val style = PaywallPresentationStyle.Modal
                val newState = PaywallViewState.Updates.SetPresentationConfig(style, completion).transform(state)

                Then("presentation style is overridden and completion stored") {
                    assertEquals(style, newState.presentationStyle)
                    assertTrue(newState.viewCreatedCompletion != null)
                    newState.viewCreatedCompletion?.invoke(true)
                    assertTrue(completed)
                }
            }
        }
    }

    @Test
    fun setPresentationConfig_ignores_none_override() {
        Given("a state with paywall style and None override") {
            val paywall = Paywall.stub()
            val state = makeState(paywall)

            When("SetPresentationConfig is applied with None") {
                val newState = PaywallViewState.Updates.SetPresentationConfig(PaywallPresentationStyle.None, null).transform(state)

                Then("presentation style remains paywall's style") {
                    assertEquals(paywall.presentation.style, newState.presentationStyle)
                }
            }
        }
    }

    @Test
    fun clearStatePublisher_sets_null() {
        Given("a state with a non-null publisher") {
            val state = makeState().copy(paywallStatePublisher = MutableSharedFlow())

            When("ClearStatePublisher is applied") {
                val newState = PaywallViewState.Updates.ClearStatePublisher.transform(state)

                Then("publisher becomes null") {
                    assertEquals(null, newState.paywallStatePublisher)
                }
            }
        }
    }

    @Test
    fun setInterceptTouchEvents_toggles_flag() {
        Given("a default state") {
            val state = makeState()

            When("SetInterceptTouchEvents(true) is applied") {
                val s1 = PaywallViewState.Updates.SetInterceptTouchEvents(true).transform(state)
                Then("interceptTouchEvents is true") { assertEquals(true, s1.interceptTouchEvents) }

                When("SetInterceptTouchEvents(false) is applied") {
                    val s2 = PaywallViewState.Updates.SetInterceptTouchEvents(false).transform(s1)
                    Then("interceptTouchEvents is false") { assertEquals(false, s2.interceptTouchEvents) }
                }
            }
        }
    }

    @Test
    fun setBrowserPresented_toggles_flag() {
        Given("a default state") {
            val state = makeState()

            When("SetBrowserPresented(true) then false are applied") {
                val s1 = PaywallViewState.Updates.SetBrowserPresented(true).transform(state)
                val s2 = PaywallViewState.Updates.SetBrowserPresented(false).transform(s1)

                Then("flag reflects last update") {
                    assertEquals(false, s2.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun cleanupAfterDestroy_invokes_completion_and_resets_fields() {
        Given("a state with dismiss completion and result set") {
            var invoked = false
            val completion = { invoked = true }
            val state =
                makeState().copy(
                    dismissCompletionBlock = completion,
                    paywallResult = PaywallResult.Declined(),
                    isPresented = true,
                )

            When("CleanupAfterDestroy is applied") {
                val newState = PaywallViewState.Updates.CleanupAfterDestroy.transform(state)

                Then("completion called and fields reset") {
                    assertTrue(invoked)
                    assertEquals(null, newState.paywallResult)
                    assertEquals(false, newState.isPresented)
                    assertEquals(null, newState.dismissCompletionBlock)
                }
            }
        }
    }

    @Test
    fun toggleSpinner_from_loadingPurchase_to_ready() {
        Given("a state in LoadingPurchase") {
            val state = PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.LoadingPurchase).transform(makeState())

            When("ToggleSpinner(hidden = true) is applied") {
                val newState = PaywallViewState.Updates.ToggleSpinner(hidden = true).transform(state)

                Then("loadingState becomes Ready") {
                    assertEquals(PaywallLoadingState.Ready, newState.loadingState)
                }
            }
        }
    }

    @Test
    fun resetPresentationPreparations_resets_flags_to_initial_values() {
        Given("a state with preparations done") {
            val state = makeState().copy(presentationWillPrepare = false, presentationDidFinishPrepare = true)

            When("ResetPresentationPreparations is applied") {
                val newState = PaywallViewState.Updates.ResetPresentationPreparations.transform(state)

                Then("flags reset to initial values ready for next presentation") {
                    assertEquals(true, newState.presentationWillPrepare)
                    assertEquals(false, newState.presentationDidFinishPrepare)
                }
            }
        }
    }

    @Test
    fun cachedPaywall_secondPresentation_allowsPreparation() {
        Given("a state simulating a cached paywall after first dismiss") {
            // Simulate first presentation completing
            val afterFirstPresent = PaywallViewState.Updates.SetPresentedAndFinished.transform(makeState())
            assertEquals(true, afterFirstPresent.presentationDidFinishPrepare)

            // Simulate dismiss resetting preparations
            val afterDismiss = PaywallViewState.Updates.ResetPresentationPreparations.transform(afterFirstPresent)

            When("a new request is set for the second presentation") {
                val req = makeRequest()
                val publisher = MutableSharedFlow<com.superwall.sdk.paywall.presentation.internal.state.PaywallState>()
                val afterSetRequest = PaywallViewState.Updates.SetRequest(req, publisher, null, null).transform(afterDismiss)

                Then("presentation flags allow presentationWillBegin to run") {
                    assertEquals(true, afterSetRequest.presentationWillPrepare)
                    assertEquals(false, afterSetRequest.presentationDidFinishPrepare)
                }

                Then("PresentationWillBegin guard condition passes") {
                    // The guard is: if (!presentationWillPrepare || presentationDidFinishPrepare) return
                    val wouldReturnEarly = !afterSetRequest.presentationWillPrepare || afterSetRequest.presentationDidFinishPrepare
                    assertEquals(false, wouldReturnEarly)
                }
            }
        }
    }

    @Test
    fun setPresentedAndFinished_sets_flags_true() {
        Given("a default state") {
            val state = makeState()

            When("SetPresentedAndFinished is applied") {
                val newState = PaywallViewState.Updates.SetPresentedAndFinished.transform(state)

                Then("flags are true") {
                    assertEquals(true, newState.isPresented)
                    assertEquals(true, newState.presentationDidFinishPrepare)
                }
            }
        }
    }

    @Test
    fun setLastOpen_sets_timestamp() {
        Given("a default state with null lastOpen") {
            val state = makeState()
            assertEquals(null, state.lastOpen)

            When("SetLastOpen is applied") {
                val newState = PaywallViewState.Updates.SetLastOpen.transform(state)

                Then("lastOpen is set to a non-null Date") {
                    assertTrue(newState.lastOpen != null)
                }
            }
        }
    }

    @Test
    fun cleanupAfterDestroy_clears_lastOpen() {
        Given("a state with lastOpen set") {
            val state =
                makeState().copy(
                    lastOpen = Date(),
                    isPresented = true,
                )

            When("CleanupAfterDestroy is applied") {
                val newState = PaywallViewState.Updates.CleanupAfterDestroy.transform(state)

                Then("lastOpen is cleared") {
                    assertEquals(null, newState.lastOpen)
                }
            }
        }
    }

    @Test
    fun clearViewCreatedCompletion_clears_callback() {
        Given("a state with viewCreatedCompletion set") {
            val state = makeState().copy(viewCreatedCompletion = { })

            When("ClearViewCreatedCompletion is applied") {
                val newState = PaywallViewState.Updates.ClearViewCreatedCompletion.transform(state)

                Then("callback is cleared") {
                    assertEquals(null, newState.viewCreatedCompletion)
                }
            }
        }
    }

    @Test
    fun setRequest_sets_request_publisher_and_occurrence() {
        Given("a default state") {
            val state = makeState()
            val req = makeRequest()
            val publisher = MutableSharedFlow<com.superwall.sdk.paywall.presentation.internal.state.PaywallState>()
            val occurrence = TriggerRuleOccurrence.stub()

            When("SetRequest is applied") {
                val newState = PaywallViewState.Updates.SetRequest(req, publisher, occurrence, null).transform(state)

                Then("all fields are set and same instance preserved") {
                    org.junit.Assert.assertSame(req, newState.request)
                    org.junit.Assert.assertSame(publisher, newState.paywallStatePublisher)
                    assertEquals(occurrence, newState.unsavedOccurrence)
                }
            }
        }
    }

    @Test
    fun updateSurveyState_sets_result() {
        Given("a default state") {
            val state = makeState()

            When("UpdateSurveyState is applied") {
                val res = SurveyPresentationResult.SHOW
                val newState = PaywallViewState.Updates.UpdateSurveyState(res).transform(state)

                Then("surveyPresentationResult equals res") {
                    assertEquals(res, newState.surveyPresentationResult)
                }
            }
        }
    }

    private fun experiment(id: String) =
        Experiment(
            id = id,
            groupId = "group",
            variant = Experiment.Variant(id = "v_$id", type = Experiment.Variant.VariantType.TREATMENT, paywallId = "pw"),
        )

    @Test
    fun mergePaywall_doesNotOverwriteExperimentOrPresentationSource() {
        Given("a state whose paywall is bound to experiment A") {
            val state =
                makeState(Paywall.stub().copy(experiment = experiment("A"), presentationSourceType = "implicit"))
            val from = Paywall.stub().copy(experiment = experiment("B"), presentationSourceType = "getPaywall")

            When("a paywall resolved for experiment B is merged") {
                val newState = PaywallViewState.Updates.MergePaywall(from).transform(state)

                Then("the experiment and source stay bound to A") {
                    assertEquals("A", newState.paywall.experiment?.id)
                    assertEquals("implicit", newState.paywall.presentationSourceType)
                }
            }
        }
    }

    @Test
    fun setRequest_bindsExperimentAndSourceTogetherWithRequest() {
        Given("a state whose paywall carries a stale experiment") {
            val state = makeState(Paywall.stub().copy(experiment = experiment("stale"), presentationSourceType = "getPaywall"))
            val request = makeRequest() // explicit trigger, Presentation type -> "register"

            When("a request is bound with its experiment") {
                val newState =
                    PaywallViewState.Updates
                        .SetRequest(request, null, null, experiment("bound"))
                        .transform(state)

                Then("info reports the bound experiment, the request's source and the request's placement") {
                    assertEquals("bound", newState.info.experiment?.id)
                    assertEquals("register", newState.info.presentationSourceType)
                    assertEquals("evt", newState.info.presentedByEventWithName)
                }
            }

            When("a request is bound with a null experiment") {
                val newState = PaywallViewState.Updates.SetRequest(request, null, null, null).transform(state)

                Then("the stale experiment is not inherited") {
                    assertEquals(null, newState.info.experiment)
                }
            }
        }
    }

    @Test
    fun presentationId_isKeptFromFetchThroughTheFirstOpen() {
        Given("a freshly created view whose paywall carries the presentation id minted at fetch time") {
            val state = makeState(Paywall.stub().copy(presentationId = "created"))
            val request = makeRequest()

            When("its request is bound, a paywall is merged and the presentation begins") {
                val bound = PaywallViewState.Updates.SetRequest(request, null, null, experiment("A")).transform(state)
                val merged = PaywallViewState.Updates.MergePaywall(Paywall.stub().copy(presentationId = "other")).transform(bound)
                val begun = PaywallViewState.Updates.BeginPresentation.transform(merged)
                val opened = PaywallViewState.Updates.SetLastOpen.transform(begun)

                Then("the id its load events already carry is kept all the way to paywall_open") {
                    assertEquals("created", bound.info.presentationId)
                    assertEquals("created", merged.info.presentationId)
                    assertEquals("created", begun.info.presentationId)
                    assertEquals("created", opened.info.presentationId)
                    assert(opened.presentationIdOpened)
                }
            }
        }

        Given("a view created from a paywall that never got a presentation id") {
            val state = makeState(Paywall.stub().copy(presentationId = null))

            When("the presentation begins") {
                val begun = PaywallViewState.Updates.BeginPresentation.transform(state)

                Then("one is minted so paywall_open never reports a null id") {
                    assert(!begun.info.presentationId.isNullOrBlank())
                }
            }
        }
    }

    @Test
    fun presentationId_isMintedPerPresentationOfACachedView() {
        Given("a cached view that has already been presented once and torn down") {
            val request = makeRequest()
            var state = makeState(Paywall.stub().copy(presentationId = "first"))
            state = PaywallViewState.Updates.SetRequest(request, null, null, experiment("A")).transform(state)
            state = PaywallViewState.Updates.BeginPresentation.transform(state)
            state = PaywallViewState.Updates.SetPresentedAndFinished.transform(state)
            state = PaywallViewState.Updates.SetLastOpen.transform(state)
            state = PaywallViewState.Updates.CleanupAfterDestroy.transform(state)

            Then("the consumed id survives the teardown") {
                assertEquals("first", state.info.presentationId)
                assert(state.presentationIdOpened)
            }

            When("it is re-bound and the next presentation begins") {
                val rebound = PaywallViewState.Updates.SetRequest(request, null, null, experiment("B")).transform(state)
                val begun = PaywallViewState.Updates.BeginPresentation.transform(rebound)

                Then("binding leaves the id alone and beginning the presentation mints a new one") {
                    assertEquals("first", rebound.info.presentationId)
                    assert(begun.info.presentationId != "first")
                    assert(!begun.info.presentationId.isNullOrBlank())
                    assert(!begun.presentationIdOpened)
                }

                Then("beginning is idempotent until the next open consumes the id") {
                    val again = PaywallViewState.Updates.BeginPresentation.transform(begun)
                    assertEquals(begun.info.presentationId, again.info.presentationId)
                }
            }
        }

        Given("an embedded view that is re-presented without ever being torn down") {
            var state = makeState(Paywall.stub().copy(presentationId = "first"))
            state = PaywallViewState.Updates.BeginPresentation.transform(state)
            state = PaywallViewState.Updates.SetPresentedAndFinished.transform(state)
            state = PaywallViewState.Updates.SetLastOpen.transform(state)
            // The cache-hit reset PaywallManager runs for every new presentation.
            state = PaywallViewState.Updates.ResetPresentationPreparations.transform(state)

            When("the second presentation begins") {
                val begun = PaywallViewState.Updates.BeginPresentation.transform(state)

                Then("it does not reuse the first presentation's id") {
                    assert(state.isPresented)
                    assert(begun.info.presentationId != "first")
                }
            }
        }
    }

    @Test
    fun presentationId_isStableWhileAPresentationIsLive() {
        Given("a presented view that has tracked paywall_open") {
            val request = makeRequest()
            var state = makeState(Paywall.stub().copy(presentationId = "live"))
            state = PaywallViewState.Updates.SetRequest(request, null, null, experiment("A")).transform(state)
            state = PaywallViewState.Updates.BeginPresentation.transform(state)
            state = PaywallViewState.Updates.SetPresentedAndFinished.transform(state)
            state = PaywallViewState.Updates.SetLastOpen.transform(state)

            When("another request is bound to it mid-presentation") {
                val rebound = PaywallViewState.Updates.SetRequest(request, null, null, experiment("B")).transform(state)

                Then("the live presentation keeps its id") {
                    assertEquals("live", rebound.info.presentationId)
                }
            }

            When("it is closed for background and opened again") {
                val closed = PaywallViewState.Updates.SetClosedForBackground(true).transform(state)
                val reopened = PaywallViewState.Updates.SetLastOpen.transform(closed)

                Then("the resumed presentation keeps its id") {
                    assertEquals("live", reopened.info.presentationId)
                }
            }
        }
    }
}
