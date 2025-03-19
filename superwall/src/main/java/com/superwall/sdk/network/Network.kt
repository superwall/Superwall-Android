package com.superwall.sdk.network

import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.map
import com.superwall.sdk.misc.onError
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.events.EventsResponse
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.paywall.Paywall
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.util.UUID

open class Network(
    private val baseHostService: BaseHostService,
    private val collectorService: CollectorService,
    private val geoService: GeoService,
    private val factory: ApiFactory,
    private val subscriptionService: SubscriptionService,
) : SuperwallAPI {
    override suspend fun sendEvents(events: EventsRequest): Either<Unit, NetworkError> =
        collectorService
            .events(
                events,
            ).map {
                if (it.status == EventsResponse.Status.PARTIAL_SUCCESS) {
                    Logger.debug(
                        logLevel = LogLevel.warn,
                        scope = LogScope.network,
                        message = "Request had partial success: /events",
                    )
                }
                Unit
            }.logError("/events", mapOf("payload" to events))

    override suspend fun getConfig(isRetryingCallback: suspend () -> Unit): Either<Config, NetworkError> {
        awaitUntilAppInForeground()

        val requestId = UUID.randomUUID().toString()

        return baseHostService
            .config(
                requestId,
            ).map { config ->
                config.requestId = requestId
                config
            }.logError("/static_config")
    }

    override suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback) =
        baseHostService
            .confirmAssignments(confirmableAssignments)
            .map { }
            .logError("/confirm_assignments", mapOf("assignments" to confirmableAssignments))

    override suspend fun getPaywall(
        identifier: String?,
        event: EventData?,
    ): Either<Paywall, NetworkError> =
        baseHostService
            .paywall(identifier)
            .logError(
                "/paywall${identifier?.let { "/:$it" } ?: ""}",
                if (identifier == null) {
                    mapOf(
                        "identifier" to (identifier ?: "none"),
                        "event" to (event?.name ?: ""),
                    )
                } else {
                    null
                },
            )

    override suspend fun getPaywalls(isForDebugging: Boolean): Either<List<Paywall>, NetworkError> =
        baseHostService
            .paywalls(isForDebugging)
            .map {
                it.paywalls
            }.logError("/paywalls")

    override suspend fun getGeoInfo(): Either<GeoInfo, NetworkError> {
        awaitUntilAppInForeground()

        return geoService
            .geo()
            .map {
                it.info
            }.logError("/geo")
    }

    override suspend fun getAssignments(): Either<List<Assignment>, NetworkError> =
        baseHostService
            .assignments()
            .map {
                it.assignments
            }.logError("/assignments")

    override suspend fun redeemToken(
        codes: List<Redeemable>,
        userId: UserId,
        aliasId: String?,
        vendorId: DeviceVendorId,
    ) = subscriptionService
        .redeemToken(codes, userId, aliasId, vendorId)
        .logError("/redeem")

    override suspend fun webEntitlementsByUserId(userId: UserId) =
        subscriptionService
            .webEntitlementsByUserId(userId)
            .logError("/redeem")

    override suspend fun webEntitlementsByDeviceID(deviceId: DeviceVendorId) =
        subscriptionService
            .webEntitlementsByDeviceId(deviceId)
            .logError("/redeem")

    private suspend fun awaitUntilAppInForeground() {
        // Wait until the app is not in the background.
        factory.appLifecycleObserver
            .isInBackground
            .filter { !it }
            .first()
    }

    private fun <T> Either<T, NetworkError>.logError(
        url: String,
        data: Map<String, Any>? = null,
    ) = onError {
        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.network,
            message = "Request Failed: $url",
            info = data,
            error = it,
        )
    }
}
