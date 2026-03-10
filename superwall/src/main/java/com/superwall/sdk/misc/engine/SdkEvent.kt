package com.superwall.sdk.misc.engine

/**
 * Marker interface for all events processed by the [com.superwall.sdk.misc.primitives.Engine].
 *
 * Domain events (e.g. [com.superwall.sdk.identity.IdentityState.Updates]) implement this directly
 * via [com.superwall.sdk.misc.primitives.Reducer]. Cross-cutting events like [com.superwall.sdk.misc.engine.SdkState.Updates.FullResetOnIdentify] and
 * [com.superwall.sdk.misc.engine.SdkState.Updates.ConfigReady] are top-level objects in their respective domain files.
 */
interface SdkEvent
