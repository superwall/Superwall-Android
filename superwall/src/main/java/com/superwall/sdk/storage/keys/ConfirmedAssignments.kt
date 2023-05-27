package com.superwall.sdk.storage.keys

import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ConfirmedAssignmentsConfig: StorageConfig {
    override val key: String = "store.confirmedAssignments"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class ConfirmedAssignments(val assignments: Map<ExperimentID, Experiment.Variant>)

class ConfirmedAssignmentsManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): ConfirmedAssignments? = mutex.withLock {
        this.cacheHelper.read(ConfirmedAssignmentsConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(confirmedAssignments: ConfirmedAssignments) = mutex.withLock {
        this.cacheHelper.write(ConfirmedAssignmentsConfig, Json.encodeToString(confirmedAssignments).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(ConfirmedAssignmentsConfig)
    }
}
