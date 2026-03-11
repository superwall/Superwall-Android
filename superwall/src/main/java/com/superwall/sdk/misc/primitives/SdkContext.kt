package com.superwall.sdk.misc.primitives

import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage

/**
 * SDK-level actor context — extends [ActorContext] with storage helpers.
 *
 * All Superwall domain contexts (IdentityContext, ConfigContext) extend this.
 */
interface SdkContext<S, Self : SdkContext<S, Self>> : ActorContext<S, Self> {
    val storage: Storage

    /** Persist a value to storage. */
    fun <T : Any> persist(
        storable: Storable<T>,
        value: T,
    ) {
        storage.write(storable, value)
    }

    /** Delete a value from storage. */
    fun delete(storable: Storable<*>) {
        @Suppress("UNCHECKED_CAST")
        storage.delete(storable as Storable<Any>)
    }
}
