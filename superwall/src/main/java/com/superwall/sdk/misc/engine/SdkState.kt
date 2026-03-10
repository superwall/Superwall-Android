package com.superwall.sdk.misc.engine

import com.superwall.sdk.config.ConfigSlice
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.misc.primitives.Fx
import com.superwall.sdk.misc.primitives.Reducer

data class SdkState(
    val identity: IdentityState = IdentityState(),
    val config: ConfigSlice = ConfigSlice(),
    val configReady: Boolean = false,
) {
    companion object {
        fun initial() = SdkState()
    }

    internal sealed class Updates(
        override val applyOn: Fx.(SdkState) -> SdkState,
    ) : Reducer<SdkState>(applyOn) {
        data class UpdateIdentity(
            val update: IdentityState.Updates,
        ) : Updates({
                it.copy(identity = update.applyOn(this, it.identity))
            })

        data class UpdateConfig(
            val update: ConfigSlice.Updates,
        ) : Updates({
                it.copy(config = update.applyOn(this, it.config))
            })

        /** Cross-cutting: resets config + entitlements + session (NOT identity — handled inline) */
        internal object FullResetOnIdentify : Updates({
            it.copy(config = ConfigSlice(), configReady = false)
        })

        /** Dispatched by ConfigSlice.Updates.ConfigRetrieved/ConfigRefreshed when config is ready. */
        internal object ConfigReady : Updates({
            it.copy(configReady = true)
        })
    }
}
