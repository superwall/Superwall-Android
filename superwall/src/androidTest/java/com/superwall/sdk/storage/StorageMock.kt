package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.network.device.DeviceInfo

class StorageFactoryMock : Storage.Factory {
    override fun makeDeviceInfo(): DeviceInfo {
        return DeviceInfo(appInstalledAtString = "a", locale = "b")
    }

    override fun makeIsSandbox(): Boolean {
        return true
    }

    override suspend fun makeSessionDeviceAttributes(): HashMap<String, Any> {
        return hashMapOf()
    }

    override fun makeHasExternalPurchaseController(): Boolean {
        return true
    }
}

class StorageMock(
    context: Context,
//    private var internalCachedTriggerSessions: List<TriggerSession> = listOf(),
//    private var internalCachedTransactions: List<StoreTransaction> = listOf(),
//    coreDataManager: CoreDataManagerFakeDataMock = CoreDataManagerFakeDataMock(),
    private var confirmedAssignments: Map<ExperimentID, Experiment.Variant> = mapOf(),
//    cache: Cache = Cache(context)
) : Storage(context = context, factory = StorageFactoryMock()) {

    var didClearCachedSessionEvents = false

    override fun clearCachedSessionEvents() {
        didClearCachedSessionEvents = true
    }

    override fun getConfirmedAssignments(): Map<ExperimentID, Experiment.Variant> {
        return confirmedAssignments
    }

    override fun saveConfirmedAssignments(assignments: Map<ExperimentID, Experiment.Variant>) {
        confirmedAssignments = assignments
    }
}
