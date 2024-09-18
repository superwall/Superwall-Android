package com.superwall.sdk.network

import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.models.paywall.Paywall

class NetworkMock : SuperwallAPI {
    //    var sentSessionEvents: SessionEventsRequest? = null
    var getConfigCalled = false
    var assignmentsConfirmed = false
    var assignments: MutableList<Assignment> = mutableListOf()
    var configReturnValue: Config? = Config.stub()
    var configError: Exception? = null

    override suspend fun sendEvents(events: EventsRequest): Either<Unit, NetworkError> {
        TODO("Not yet implemented")
    }

//    suspend fun sendSessionEvents(session: SessionEventsRequest) {
//        sentSessionEvents = session
//    }

    @Throws(Exception::class)
    override suspend fun getConfig(isRetryingCallback: suspend () -> Unit): Either<Config, NetworkError> {
        getConfigCalled = true

        configReturnValue?.let {
            return Either.Success(it)
        } ?: throw configError ?: Exception("Config Error")
    }

    override suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback): Either<Unit, NetworkError> {
        assignmentsConfirmed = true
        return Either.Success(Unit)
    }

    override suspend fun getPaywall(
        identifier: String?,
        event: EventData?,
    ): Either<Paywall, NetworkError> {
        TODO("Not yet implemented")
    }

    override suspend fun getPaywalls(): Either<List<Paywall>, NetworkError> {
        TODO("Not yet implemented")
    }

    override suspend fun getGeoInfo(): Either<GeoInfo?, NetworkError> {
        TODO("Not yet implemented")
    }

    @Throws(Exception::class)
    override suspend fun getAssignments(): Either<List<Assignment>, NetworkError> = Either.Success(assignments)
}
