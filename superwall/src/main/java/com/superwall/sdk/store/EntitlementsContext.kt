package com.superwall.sdk.store

import com.superwall.sdk.misc.primitives.BaseContext

/**
 * All dependencies available to entitlements [EntitlementsState.Actions].
 *
 * Actions see only [EntitlementsState] via [actor] plus the storage helpers
 * inherited from [BaseContext].
 */
interface EntitlementsContext : BaseContext<EntitlementsState, EntitlementsContext>
