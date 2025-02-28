package com.superwall.sdk.analytics.superwall

@Deprecated("Use SuperwallEventInfo instead")
typealias SuperwallPlacementInfo = SuperwallEventInfo

data class SuperwallEventInfo(
    public val placement: SuperwallPlacement,
    public val params: Map<String, Any>,
)
