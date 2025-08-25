package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.permissions.CommonPermission
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.supercel.HostContext
import com.superwall.supercel.ResultCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SuperscriptHostContext(
    private val json: Json,
    private val storage: CoreDataManager,
    private val dependencyContainer: DependencyContainer? = null,
) : HostContext {
    companion object ComputedProperties {
        val availableComputedProperties =
            SuperscriptExposedFunction.Names.entries.map {
                it.rawName
            }
        val availableDeviceProperties = availableComputedProperties
    }

    override fun computedProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableComputedProperties.contains(name)) {
            callback.onResult(
                json.encodeToString<PassableValue>(
                    PassableValue.BoolValue(false),
                ),
            )
            return
        }

        val res =
            runBlocking {
                val fn = SuperscriptExposedFunction.from(name, _args)
                when (fn) {
                    null -> null
                    is TimeSince -> fn(storage)
                    is SuperscriptExposedFunction.PlacementCount -> fn(storage)
                    is SuperscriptExposedFunction.ReviewRequestCount -> fn(storage)
                    is SuperscriptExposedFunction.RequestReview -> fn(storage)
                    is SuperscriptExposedFunction.HasPermission -> {
                        val permission =
                            CommonPermission.fromName(fn.permission)
                                ?: CommonPermission.fromRaw(fn.permission)
                        if (permission != null && dependencyContainer != null) {
                            dependencyContainer.userPermissions.hasPermission(permission)
                        } else {
                            false
                        }
                    }
                    is SuperscriptExposedFunction.RequestPermission -> {
                        val permission =
                            CommonPermission.fromName(fn.permission)
                                ?: CommonPermission.fromRaw(fn.permission)
                        val activity = dependencyContainer?.activityProvider?.getCurrentActivity()
                        if (permission != null && dependencyContainer != null && activity != null) {
                            try {
                                val result = dependencyContainer.userPermissions.requestPermission(activity, permission)
                                result is com.superwall.sdk.permissions.PermissionResult.Granted
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
            }
        callback.onResult(json.encodeToString<PassableValue>(res?.toPassableValue() ?: PassableValue.NullValue))
    }

    // Temporary solution until CEL lib is updated
    override fun deviceProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableDeviceProperties.contains(name)) {
            callback.onResult(
                json.encodeToString<PassableValue>(
                    PassableValue.BoolValue(false),
                ),
            )
            return
        }

        val res =
            runBlocking {
                val fn = SuperscriptExposedFunction.from(name, _args)
                when (fn) {
                    null -> null
                    is TimeSince -> fn(storage)
                    is SuperscriptExposedFunction.PlacementCount -> fn(storage)
                    is SuperscriptExposedFunction.ReviewRequestCount -> fn(storage)
                    is SuperscriptExposedFunction.RequestReview -> fn(storage)
                    is SuperscriptExposedFunction.HasPermission -> {
                        val permission =
                            CommonPermission.fromName(fn.permission)
                                ?: CommonPermission.fromRaw(fn.permission)
                        if (permission != null && dependencyContainer != null) {
                            dependencyContainer.userPermissions.hasPermission(permission)
                        } else {
                            false
                        }
                    }
                    is SuperscriptExposedFunction.RequestPermission -> {
                        val permission =
                            CommonPermission.fromName(fn.permission)
                                ?: CommonPermission.fromRaw(fn.permission)
                        val activity = dependencyContainer?.activityProvider?.getCurrentActivity()
                        if (permission != null && dependencyContainer != null && activity != null) {
                            try {
                                val result = dependencyContainer.userPermissions.requestPermission(activity, permission)
                                result is com.superwall.sdk.permissions.PermissionResult.Granted
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
            }
        callback.onResult(json.encodeToString<PassableValue>(res?.toPassableValue() ?: PassableValue.NullValue))
    }
}
