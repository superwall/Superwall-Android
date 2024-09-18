package com.superwall.sdk.network

import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.models.paywall.Paywall

interface SuperwallAPI {
    suspend fun sendEvents(events: EventsRequest): Either<Unit, NetworkError>

    suspend fun getConfig(isRetryingCallback: suspend () -> Unit): Either<Config, NetworkError>

    suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback): Either<Unit, NetworkError>

    suspend fun getPaywall(
        identifier: String? = null,
        event: EventData? = null,
    ): Either<Paywall, NetworkError>

    suspend fun getPaywalls(): Either<List<Paywall>, NetworkError>

    suspend fun getGeoInfo(): Either<GeoInfo?, NetworkError>

    suspend fun getAssignments(): Either<List<Assignment>, NetworkError>
}
