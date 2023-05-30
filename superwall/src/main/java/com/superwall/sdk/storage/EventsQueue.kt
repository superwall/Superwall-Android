package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.UserInitiatedEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.network.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter

@OptIn(ExperimentalCoroutinesApi::class)
class EventsQueue(private val context: Context, private val network: Network, private val configManager: ConfigManager): BroadcastReceiver() {
    private val maxEventCount = 50
    private var elements = mutableListOf<EventData>()
    private val timer = MutableSharedFlow<Long>()
    private var job: Job? = null

    init {
        CoroutineScope(Dispatchers.Main).launch {
            setupTimer()
            addObserver()
        }
    }

    private suspend fun setupTimer() {
        val timeInterval = if (configManager.options?.networkEnvironment == SuperwallOptions.NetworkEnvironment.RELEASE) 20L else 1L
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(timeInterval * 1000) // delay works in milliseconds
                timer.emit(System.currentTimeMillis())
            }
        }
        timer.collect {
            flushInternal()
        }
    }

    private suspend fun addObserver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(this, filter)
    }

    fun enqueue(event: EventData) {
        elements.add(event)
    }

    private fun externalDataCollectionAllowed(event: Trackable): Boolean {
        return when {
            Superwall.instance.options.isExternalDataCollectionEnabled -> true
            event is InternalSuperwallEvent.TriggerFire -> false
            event is InternalSuperwallEvent.Attributes -> false
            event is UserInitiatedEvent.Track -> false
            else -> true
        }
    }

    private suspend fun flushInternal(depth: Int = 10) {
        val eventsToSend = mutableListOf<EventData>()
        var i = 0
        while (i < maxEventCount && elements.isNotEmpty()) {
            eventsToSend.add(elements.removeFirst())
            i += 1
        }
        if (eventsToSend.isNotEmpty()) {
            // Send to network
            val events = EventsRequest(eventsToSend)
            CoroutineScope(Dispatchers.IO).launch {
                network.sendEvents(events)
            }
        }
        if (elements.isNotEmpty() && depth > 0) {
            flushInternal(depth - 1)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when(intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // equivalent to "applicationWillResignActive"
                // your code here
                CoroutineScope(Dispatchers.Default).launch {
                    flushInternal()
                }
            }
        }
    }
}
