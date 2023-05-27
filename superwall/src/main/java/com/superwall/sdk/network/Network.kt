package com.superwall.sdk.network

import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmedAssignmentResponse
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.events.EventsResponse
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.UUID


open class Network(
    private val urlSession: CustomHttpUrlConnection = CustomHttpUrlConnection(),
    private val factory: ApiFactory
) {
//    private val applicationStatePublisher: Flow<UIApplication.State> =
//        UIApplication.shared.publisher { property ->
//            if (property == "applicationState") {
//                emit(UIApplication.sharedInstance().applicationState)
//            }
//        }

    suspend fun sendEvents(events: EventsRequest) {
        try {
            val result =
                urlSession.request<EventsResponse>(
                    Endpoint.events(
                        eventsRequest = events,
                        factory = factory,
                    )
                )

            when (result.status) {
                EventsResponse.Status.OK -> {
                }
                EventsResponse.Status.PARTIAL_SUCCESS -> {
                    Logger.debug(
                        logLevel = LogLevel.warn,
                        scope = LogScope.network,
                        message = "Request had partial success: /events",
                    )
                }
            }
        } catch (error: Throwable) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.network,
                message = "Request Failed: /events",
                info = mapOf("payload" to events),
                error = error
            )
        }
    }



//    @MainActor
    suspend fun getConfig(
//        injectedApplicationStatePublisher: (Flow<UIApplication.State>)? = null
    ): Config {
    // TODO: ApplicationStatePublisher
//        // Suspend until app is in foreground.
//        val applicationStatePublisher =
//            injectedApplicationStatePublisher ?: this.applicationStatePublisher
//
//        applicationStatePublisher
//            .flowOn(Dispatchers.Main)
//            .filter { it != UIApplication.State.BACKGROUND }
//            .first()

        return try {
            val requestId = UUID.randomUUID().toString()
            val config = urlSession.request<Config>(
                Endpoint.config(
                    factory = factory,
                    requestId = requestId
                )
            )
            config.requestId = requestId
            config
        } catch (error: Throwable) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.network,
                message = "Request Failed: /static_config",
                error = error
            )
            throw error
        }
    }

    open suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback) {
        try {
            urlSession.request<ConfirmedAssignmentResponse>(
                Endpoint.confirmAssignments(
                    confirmableAssignments = confirmableAssignments,
                    factory = factory
                )
            )
        } catch (error: Throwable) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.network,
                message = "Request Failed: /confirm_assignments",
                info = mapOf("assignments" to confirmableAssignments),
                error = error
            )
        }
    }

    open suspend fun getAssignments(): List<Assignment> {
        return try {
            val result = urlSession.request<ConfirmedAssignmentResponse>(
                Endpoint.assignments(factory = factory)
            )
            result.assignments
        } catch (error: Throwable) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.network,
                message = "Request Failed: /assignments",
                error = error
            )
            throw error
        }
    }


}