package com.superwall.sdk.store

import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.misc.primitives.BaseContext

/**
 * All dependencies available to entitlements [EntitlementsState.Actions].
 *
 * Actions see only [EntitlementsState] via [actor].
 */
interface EntitlementsContext :
    BaseContext<EntitlementsState, EntitlementsContext>,
    HasExternalPurchaseControllerFactory
