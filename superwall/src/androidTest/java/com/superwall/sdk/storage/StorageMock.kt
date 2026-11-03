package com.superwall.sdk.storage

import com.superwall.sdk.storage.core_data.CoreDataManager
import android.content.Context
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.network.device.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

class StorageFactoryMock : LocalStorage.Factory {
    override fun makeDeviceInfo(): DeviceInfo = DeviceInfo(appInstalledAtString = "a", locale = "b")

    override fun makeIsSandbox(): Boolean = true

    override suspend fun makeSessionDeviceAttributes(): HashMap<String, Any> = hashMapOf()

    override fun makeHasExternalPurchaseController(): Boolean = true
}

class StorageMock(
    context: Context,
//    private var internalCachedTriggerSessions: List<TriggerSession> = listOf(),
//    private var internalCachedTransactions: List<StoreTransaction> = listOf(),
//    coreDataManager: CoreDataManagerFakeDataMock = CoreDataManagerFakeDataMock(),
    private var confirmedAssignments: Map<ExperimentID, Experiment.Variant> = mapOf(),
    private var coroutineScope: CoroutineScope,
//    cache: Cache = Cache(context)
) : LocalStorage(
        context = context,
        factory = StorageFactoryMock(),
        ioScope = IOScope(),
        json =
            Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                ignoreUnknownKeys = true
            },
        _apiKey = "",
        cache = Cache(context = context, json = Json { ignoreUnknownKeys = true }),
        coreDataManager = CoreDataManager(context = context),
        track = {},
    ) {
    var didClearCachedSessionEvents = false

    override fun clearCachedSessionEvents() {
        didClearCachedSessionEvents = true
    }

    override fun getConfirmedAssignments(): Map<ExperimentID, Experiment.Variant> = confirmedAssignments

    override fun saveConfirmedAssignments(assignments: Map<ExperimentID, Experiment.Variant>) {
        confirmedAssignments = assignments
    }
}
