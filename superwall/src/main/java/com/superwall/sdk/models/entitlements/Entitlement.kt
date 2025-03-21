package com.superwall.sdk.models.entitlements

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Entitlement(
    @SerialName("identifier")
    val id: String,
    @SerialName("type")
    val type: Type = Type.SERVICE_LEVEL,
    @Transient
    val source: Set<SourceType> = setOf(SourceType.ANDROID),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Entitlement

        if (id != other.id) return false
        if (type != other.type) return false

        return true
    }
    constructor(id: String) : this(id, type = Type.SERVICE_LEVEL, source = setOf(SourceType.ANDROID))

    @Serializable
    enum class Type(
        val raw: String,
    ) {
        @SerialName("SERVICE_LEVEL")
        SERVICE_LEVEL("SERVICE_LEVEL"),
    }
}

enum class SourceType {
    WEB,
    ANDROID,
    IOS,
}
