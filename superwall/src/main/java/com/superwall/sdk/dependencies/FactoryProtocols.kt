package com.superwall.sdk.dependencies

import android.app.Activity
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.trigger_session.TriggerSessionManager
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.network.Api
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.rule_logic.RuleAttributes
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.keys.SubscriptionStatus
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection


interface ApiFactory {
    // TODO: Think of an alternative way such that we don't need to do this:
    // swiftlint:disable implicitly_unwrapped_optional
    var api: Api
    var storage: Storage
//    var storage: Storage! { get }
//    var deviceHelper: DeviceHelper! { get }
    var configManager: ConfigManager
    var identityManager: IdentityManager
    // swiftlint:enable implicitly_unwrapped_optional

    suspend  fun makeHeaders(
        fromRequest: HttpURLConnection,
        isForDebugging: Boolean,
        requestId: String
    ): Map<String, String>
}


interface DeviceInfoFactory {
    fun makeDeviceInfo(): DeviceInfo
}

interface TriggerSessionManagerFactory {
    fun makeTriggerSessionManager(): TriggerSessionManager
    fun getTriggerSessionManager(): TriggerSessionManager
}


// RequestFactory interface
interface RequestFactory {
    fun makePaywallRequest(
        eventData: EventData? = null,
        responseIdentifiers: ResponseIdentifiers,
        overrides: PaywallRequest.Overrides? = null,
        isDebuggerLaunched: Boolean
    ): PaywallRequest

    fun makePresentationRequest(
        presentationInfo: PresentationInfo,
        paywallOverrides: PaywallOverrides? = null,
        presenter: Activity? = null,
        isDebuggerLaunched: Boolean? = null,
        subscriptionStatus: StateFlow<SubscriptionStatus?>? = null,
        isPaywallPresented: Boolean,
        type: PresentationRequestType
    ): PresentationRequest
}


interface RuleAttributesFactory {
    suspend fun makeRuleAttributes(): RuleAttributes
}

interface IdentityInfoFactory {
    suspend fun makeIdentityInfo(): IdentityInfo
}

interface LocaleIdentifierFactory {
    fun makeLocaleIdentifier(): String?
}

interface IdentityInfoAndLocaleIdentifierFactory {
    suspend fun makeIdentityInfo(): IdentityInfo
    fun makeLocaleIdentifier(): String?
}