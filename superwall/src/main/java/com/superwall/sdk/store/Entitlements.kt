package com.superwall.sdk.store

import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.EntitlementStatus
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementStatus
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class Entitlements(
    private val storage: Storage,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val _entitlementsByProduct = ConcurrentHashMap<String, Set<Entitlement>>()

    private val _status: MutableStateFlow<EntitlementStatus> =
        MutableStateFlow(EntitlementStatus.Unknown)

    val status: StateFlow<EntitlementStatus>
        get() = _status.asStateFlow()

    // Mutable backing fields for entitlements
    private val _all = mutableSetOf<Entitlement>()
    private val _active = mutableSetOf<Entitlement>()
    private val _inactive = mutableSetOf<Entitlement>()

    // Exposed properties for entitlements
    val all: Set<Entitlement>
        get() = _all.toSet()
    val active: Set<Entitlement>
        get() = _active.toSet()
    val inactive: Set<Entitlement>
        get() = _inactive.toSet()

    init {
        storage.read(StoredEntitlementStatus)?.let {
            setEntitlementStatus(it)
        }
        storage.read(StoredEntitlementsByProductId)?.let {
            _entitlementsByProduct.putAll(it)
        }

        scope.launch {
            status.collect {
                storage.write(StoredEntitlementStatus, it)
            }
        }
    }

    fun setEntitlementStatus(value: EntitlementStatus) {
        when (value) {
            is EntitlementStatus.Active -> {
                if (value.entitlements.isEmpty()) {
                    setEntitlementStatus(EntitlementStatus.Inactive)
                } else {
                    _active.clear()
                    _all.addAll(value.entitlements)
                    _active.addAll(value.entitlements)
                    _inactive.removeAll(value.entitlements)
                    _status.value = value
                }
            }

            is EntitlementStatus.Inactive -> {
                _active.clear()
                _inactive.clear()
                _status.value = value
            }

            is EntitlementStatus.Unknown -> {
                _all.clear()
                _active.clear()
                _inactive.clear()
                _status.value = value
            }
        }
    }

    internal fun byProductId(id: String): Set<Entitlement> {
        val decomposedProductIds = DecomposedProductIds.from(id)
        listOf(
            decomposedProductIds.fullId,
            "${decomposedProductIds.subscriptionId}:${decomposedProductIds.basePlanId}",
            decomposedProductIds.subscriptionId,
        ).forEach { id ->
            _entitlementsByProduct.entries
                .firstOrNull { it.key.contains(id) && it.value.isNotEmpty() }
                .let {
                    if (it != null) {
                        return it.value
                    }
                }
        }
        return emptySet()
    }

    internal fun addEntitlementsByProductId(idToEntitlements: Map<String, Set<Entitlement>>) {
        _entitlementsByProduct.putAll(
            idToEntitlements.mapValues { (_, entitlements) ->
                entitlements.toSet()
            },
        )
        _all.clear()
        _all.addAll(_entitlementsByProduct.values.flatten())
        storage.write(StoredEntitlementsByProductId, _entitlementsByProduct)
    }
}
