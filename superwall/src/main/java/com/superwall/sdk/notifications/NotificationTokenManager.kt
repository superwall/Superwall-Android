package com.superwall.sdk.notifications

import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.getConfig
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.misc.SerialTaskManager
import com.superwall.sdk.notifications.NotificationTokenLogic.Identity
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredNotificationTokenLastSent
import com.superwall.sdk.storage.StoredNotificationTokenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reports the device's FCM token and notification permission to Superwall in a
 * `notification_token` event, and tracks opens of the notifications Superwall sends.
 *
 * The state is sent when it changes (token, permission, opt in/out), when the user changes
 * (identify and reset), and otherwise at most once a day as a heartbeat. Until the
 * `user_notifications` feature flag is on for the app nothing is sent, but the state is kept
 * so it's sent as soon as it is.
 *
 * Every operation runs one at a time, in the order it was called, so the stored state is
 * only ever read and written by one operation.
 */
internal class NotificationTokenManager(
    private val storage: Storage,
    private val tracker: suspend (Trackable) -> Unit,
    private val permissionStatus: () -> NotificationPermissionStatus,
    private val eventTrackingBehavior: () -> EventTrackingBehavior,
    private val configState: StateFlow<ConfigState>,
    private val identity: () -> Identity,
    private val appVersion: () -> String,
    private val sdkVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val tasks = SerialTaskManager(scope)

    private val isFeatureEnabled: Boolean
        get() =
            configState.value
                .getConfig()
                ?.featureFlags
                ?.userNotifications == true

    /**
     * Re-checks the state whenever config arrives with the feature flag on, the identity
     * changes (configure, identify and reset), and the app comes to the foreground, which is
     * when a permission change made in Settings shows.
     */
    fun listen(
        identityChanges: Flow<Identity>,
        appForegrounded: Flow<Unit>,
    ) {
        scope.launch {
            configState
                .map { it.getConfig()?.featureFlags?.userNotifications }
                .filterNotNull()
                .distinctUntilChanged()
                .filter { it }
                .collect { refresh() }
        }
        scope.launch {
            identityChanges.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            appForegrounded.collect { refresh() }
        }
    }

    /** Stores the FCM token and sends it if it's new. */
    fun setPushToken(token: String) {
        tasks.addTask { update { it.copy(address = token) } }
    }

    /** Opts the device in to or out of Superwall notifications. */
    fun setEnabled(enabled: Boolean) {
        tasks.addTask { update { it.copy(enabled = enabled) } }
    }

    /** Sends the state if it changed, the user changed or a day has passed. */
    fun refresh() {
        tasks.addTask { update { it } }
    }

    /** Tracks `notification_open` if Superwall sent the notification. */
    fun trackOpen(data: Map<String, String>) {
        val event = NotificationTokenLogic.notificationOpen(data) ?: return
        tasks.addTask { tracker(event) }
    }

    private suspend fun update(change: (NotificationTokenState) -> NotificationTokenState) {
        val storedState = storage.read(StoredNotificationTokenState) ?: NotificationTokenState()
        var state = change(storedState)

        // Without a token there's nothing to send, so don't check the permission yet.
        val address = state.address
        if (address == null) {
            save(state, storedState)
            return
        }

        val payload =
            NotificationTokenPayload(
                address = address,
                permissionStatus = permissionStatus(),
                enabled = state.enabled,
                appVersion = appVersion(),
                sdkVersion = sdkVersion,
            )
        state = state.copy(permissionStatus = payload.permissionStatus)
        save(state, storedState)

        // Kept until the feature flag is on, which `listen` re-checks for.
        if (!isFeatureEnabled) return
        // Not sent, so not marked as sent: it goes once tracking is allowed again.
        if (eventTrackingBehavior() == EventTrackingBehavior.NONE) return

        val fingerprint = NotificationTokenLogic.fingerprint(payload, identity())
        val lastSent = storage.read(StoredNotificationTokenLastSent)
        if (!NotificationTokenLogic.shouldSend(fingerprint, lastSent, now())) return

        tracker(PrivateSuperwallEvents.NotificationToken(payload))
        storage.write(StoredNotificationTokenLastSent, NotificationTokenLastSent(fingerprint, now()))
    }

    private fun save(
        state: NotificationTokenState,
        storedState: NotificationTokenState,
    ) {
        if (state != storedState) {
            storage.write(StoredNotificationTokenState, state)
        }
    }
}
