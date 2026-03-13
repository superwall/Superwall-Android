package com.superwall.sdk

import com.superwall.sdk.config.ConfigContext
import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.identity.IdentityContext
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.store.EntitlementsContext
import com.superwall.sdk.store.EntitlementsState

/**
 * Root router for cross-slice dispatch and state reads.
 *
 * Routes actions to the right slice via [effect] / [immediate].
 * Exposes a [state] facade for cross-slice reads:
 * ```
 * sdkContext.state.config   // read config state
 * sdkContext.state.identity // read identity state
 * sdkContext.effect(SdkConfigState.Actions.PreloadPaywalls)
 * sdkContext.immediate(SdkConfigState.Actions.FetchAssignments)
 * ```
 */
interface SdkContext {
    val state: SdkState

    fun effect(action: TypedAction<*>)

    suspend fun immediate(action: TypedAction<*>)
}

class SdkContextImpl(
    private val configCtx: () -> ConfigContext,
    private val identityCtx: () -> IdentityContext,
    private val entitlementsCtx: () -> EntitlementsContext,
) : SdkContext {
    override val state =
        SdkState(
            identityStore = { identityCtx().actor },
            configStore = { configCtx().actor },
            entitlementsStore = { entitlementsCtx().actor },
        )

    // -- Interceptors --------------------------------------------------------

    private var actionInterceptors: List<(action: Any, next: () -> Unit) -> Unit> = emptyList()

    fun onAction(interceptor: (action: Any, next: () -> Unit) -> Unit) {
        actionInterceptors = actionInterceptors + interceptor
    }

    // -- Routing -------------------------------------------------------------

    override fun effect(action: TypedAction<*>) {
        runInterceptorChain(action) {
            when (action) {
                is SdkConfigState.Actions -> configCtx().effect(action)
                is IdentityState.Actions -> identityCtx().effect(action)
                is EntitlementsState.Actions -> entitlementsCtx().effect(action)
                else -> error("Unknown action: ${action::class}")
            }
        }
    }

    override suspend fun immediate(action: TypedAction<*>) {
        var shouldExecute = true
        if (actionInterceptors.isNotEmpty()) {
            shouldExecute = false
            var chain: () -> Unit = { shouldExecute = true }
            for (i in actionInterceptors.indices.reversed()) {
                val interceptor = actionInterceptors[i]
                val next = chain
                chain = { interceptor(action, next) }
            }
            chain()
        }
        if (shouldExecute) {
            when (action) {
                is SdkConfigState.Actions -> configCtx().immediate(action)
                is IdentityState.Actions -> identityCtx().immediate(action)
                is EntitlementsState.Actions -> entitlementsCtx().immediate(action)
                else -> error("Unknown action: ${action::class}")
            }
        }
    }

    private fun runInterceptorChain(
        action: Any,
        terminal: () -> Unit,
    ) {
        if (actionInterceptors.isEmpty()) {
            terminal()
        } else {
            var chain: () -> Unit = terminal
            for (i in actionInterceptors.indices.reversed()) {
                val interceptor = actionInterceptors[i]
                val next = chain
                chain = { interceptor(action, next) }
            }
            chain()
        }
    }
}
