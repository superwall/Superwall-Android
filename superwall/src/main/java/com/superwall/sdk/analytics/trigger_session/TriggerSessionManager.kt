package com.superwall.sdk.analytics.trigger_session

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsDelegate
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.util.UUID

enum class LoadState {
    START,
    END,
    FAIL
}

data class ActiveTriggerSession(val sessionId: String, val eventName: String)

// TODO: Ensure this uses/acts as an actor similar to iOS
// https://linear.app/superwall/issue/SW-2571/%5Bandroid%5D-ensure-triggersessionmanager-is-threaded-properly-as-an
// TODO: https://linear.app/superwall/issue/SW-2366/[android]-implement-triggersessionmanager
class TriggerSessionManager(
    val storage: Storage,
    val configManager: ConfigManager,
    val identityManager: IdentityManager,
    val delegate: SessionEventsDelegate,
    val sessionEventsManager: SessionEventsManager
) {
    private val singleThreadContext = newSingleThreadContext(name = "TriggerSessionManagerThread")
    val pendingTriggerSessionIds: MutableMap<String, String?> = mutableMapOf()

    /**
     * The active trigger session tuple in format (sessionId, eventName)
     */
    suspend fun getActiveTriggerSession(): ActiveTriggerSession? = withContext(singleThreadContext) {
        return@withContext activeTriggerSession
    }

    private var activeTriggerSession: ActiveTriggerSession? = null

    init {
        CoroutineScope(singleThreadContext).launch {
            listenForConfig()
        }
    }
    private suspend fun listenForConfig() {
        configManager.configState
            .mapNotNull { it.getSuccess()?.getConfig() }
            .collect { config ->
                createSessions(config)
            }
    }

    private fun createSessions(config: Config) {
        // Loop through triggers and create a session ID for each.
        for (trigger in config.triggers) {
            pendingTriggerSessionIds[trigger.eventName] = UUID.randomUUID().toString()
        }
    }

    suspend fun activateSession(
        presentationInfo: PresentationInfo,
        triggerResult: InternalTriggerResult? = null,
    ): String? = withContext(singleThreadContext) {
        val eventName = presentationInfo.eventName ?: return@withContext null

        val sessionId = pendingTriggerSessionIds[eventName] ?: return@withContext null

        val outcome = TriggerSessionManagerLogic.outcome(
            presentationInfo = presentationInfo,
            triggerResult = triggerResult?.toPublicType()
        ) ?: return@withContext null

        activeTriggerSession = ActiveTriggerSession(
            sessionId = sessionId,
            eventName = eventName
        )
        pendingTriggerSessionIds[eventName] = null

        triggerResult?.let {
            val trackedEvent = InternalSuperwallEvent.TriggerFire(
                triggerResult = it,
                triggerName = eventName,
                sessionEventsManager = sessionEventsManager
            )
            Superwall.instance.track(trackedEvent)
        }

        when (outcome) {
            TriggerSession.PresentationOutcome.HOLDOUT,
            TriggerSession.PresentationOutcome.NO_RULE_MATCH -> {
                endSession()
            }
            TriggerSession.PresentationOutcome.PAYWALL -> {}
        }

        return@withContext sessionId
    }

    fun endSession() = CoroutineScope(singleThreadContext).launch {
        val currentTriggerSession = activeTriggerSession ?: return@launch

        // Recreate a pending trigger session
        pendingTriggerSessionIds[currentTriggerSession.eventName] = UUID.randomUUID().toString()

        activeTriggerSession = null
    }
}
