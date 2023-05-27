package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.storage.keys.Transactions
import com.superwall.sdk.storage.keys.TriggerSessions
import java.lang.Exception

class DeviceInfoFactoryMock : DeviceInfoFactory {
    override fun makeDeviceInfo(): DeviceInfo {
        return DeviceInfo(appInstalledAtString = "a", locale = "b")
    }
}

class StorageMock(
    context: Context,
    private var internalCachedTriggerSessions: List<TriggerSession> = listOf(),
//    private var internalCachedTransactions: List<StoreTransaction> = listOf(),
//    coreDataManager: CoreDataManagerFakeDataMock = CoreDataManagerFakeDataMock(),
    private var confirmedAssignments: Map<ExperimentID, Experiment.Variant> = mapOf(),
    cache: Cache = Cache(context)
) : Storage(context = context, factory = DeviceInfoFactoryMock()) {

    var didClearCachedSessionEvents = false

//    override fun <Key : Storable> get(keyType: Class<Key>): Key.Value? {
//        return when(keyType) {
//            TriggerSessions::class.java -> internalCachedTriggerSessions as? Key.Value
//            Transactions::class.java -> internalCachedTransactions as? Key.Value
//            else -> null
//        }
//    }

//    override fun <Key : Storable> get(keyType: Class<Key>): Key.Value? where Key.Value : Decodable {
//        return when(keyType) {
//            TriggerSessions::class.java -> internalCachedTriggerSessions as? Key.Value
//            Transactions::class.java -> internalCachedTransactions as? Key.Value
//            else -> null
//        }
//    }

    fun clearCachedSessionEvents() {
        didClearCachedSessionEvents = true
    }

    override suspend fun getConfirmedAssignments(): Map<ExperimentID, Experiment.Variant> {
        return confirmedAssignments
    }

    override suspend fun saveConfirmedAssignments(assignments: Map<String, Experiment.Variant>) {
        confirmedAssignments = assignments
    }
}
