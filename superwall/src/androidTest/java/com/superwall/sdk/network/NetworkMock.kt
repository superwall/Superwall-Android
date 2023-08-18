package com.superwall.sdk.network

import androidx.lifecycle.Lifecycle
import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.config.Config
import kotlinx.coroutines.flow.SharedFlow


class NetworkMock(
    factory: ApiFactory
) : Network(factory = factory) {
//    var sentSessionEvents: SessionEventsRequest? = null
    var getConfigCalled = false
    var assignmentsConfirmed = false
    var assignments: MutableList<Assignment> = mutableListOf()
    var configReturnValue: Config? = Config.stub()
    var configError: Exception? = null

//    suspend fun sendSessionEvents(session: SessionEventsRequest) {
//        sentSessionEvents = session
//    }

    @Throws(Exception::class)
    suspend fun getConfig(
        injectedApplicationStatePublisher: SharedFlow<Lifecycle.State>? = null
    ): Config {
        getConfigCalled = true


        configReturnValue?.let {
            return it
        } ?: throw configError ?: Exception("Config Error")
    }

    override suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback) {
        assignmentsConfirmed = true
    }

    @Throws(Exception::class)
    override suspend fun getAssignments(): List<Assignment> {
        return assignments
    }
}
