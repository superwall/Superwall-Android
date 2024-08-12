package com.superwall.sdk.paywall.presentation.rule_logic.cel.models
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionContext(
    val variables: PassableMap,
    val expression: String,
    val platform: Map<String, String>,
)

@Serializable
data class PassableMap(
    val map: Map<String, PassableValue>,
)

@Serializable
@Polymorphic
sealed interface PassableValue {
    @Serializable
    @SerialName("list")
    data class ListValue(
        val value: List<PassableValue>,
    ) : PassableValue

    @Serializable
    @SerialName("map")
    data class MapValue(
        val value: Map<String, PassableValue>,
    ) : PassableValue

    @Serializable
    @SerialName("function")
    data class FunctionValue(
        val value: String,
        val args: PassableValue?,
    ) : PassableValue

    @Serializable
    @SerialName("int")
    data class IntValue(
        val value: Int,
    ) : PassableValue

    @Serializable
    @SerialName("uint")
    data class UIntValue(
        val value: Long,
    ) : PassableValue

    @Serializable
    @SerialName("float")
    data class FloatValue(
        val value: Double,
    ) : PassableValue

    @Serializable
    @SerialName("string")
    data class StringValue(
        val value: String,
    ) : PassableValue

    @Serializable
    @SerialName("bytes")
    data class BytesValue(
        val value: ByteArray,
    ) : PassableValue

    @Serializable
    @SerialName("bool")
    data class BoolValue(
        val value: Boolean,
    ) : PassableValue

    @Serializable
    @SerialName("timestamp")
    data class TimestampValue(
        val value: Long,
    ) : PassableValue

    @Serializable
    @SerialName("null")
    object NullValue : PassableValue
}
