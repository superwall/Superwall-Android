package com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast

import kotlinx.serialization.*

// Used to rewrite the AST to replace all the CELExpressions with the proper values
internal sealed interface CELNode {
    // When implementing, ensure that the expression is transformed leaf to node
    abstract fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression
}

@Serializable
@SerialName("CELRelationOp")
@Polymorphic
internal sealed class CELRelationOp {
    @SerialName("LessThan")
    @Serializable
    object LessThan : CELRelationOp() {
        override fun toString() = "<"
    }

    @SerialName("LessThanEq")
    @Serializable
    object LessThanEq : CELRelationOp() {
        override fun toString() = "<="
    }

    @SerialName("GreaterThan")
    @Serializable
    object GreaterThan : CELRelationOp() {
        override fun toString() = ">"
    }

    @SerialName("GreaterThanEq")
    @Serializable
    object GreaterThanEq : CELRelationOp() {
        override fun toString() = ">="
    }

    @SerialName("Equals")
    @Serializable
    object Equals : CELRelationOp() {
        override fun toString() = "=="
    }

    @SerialName("NotEquals")
    @Serializable
    object NotEquals : CELRelationOp() {
        override fun toString() = "!="
    }

    @SerialName("In")
    @Serializable
    object In : CELRelationOp() {
        override fun toString() = "in"
    }
}

@Serializable
@SerialName("CELArithmeticOp")
@Polymorphic
internal sealed class CELArithmeticOp {
    @SerialName("Add")
    @Serializable
    object Add : CELArithmeticOp() {
        override fun toString() = "+"
    }

    @SerialName("Subtract")
    @Serializable
    object Subtract : CELArithmeticOp() {
        override fun toString() = "-"
    }

    @SerialName("Divide")
    @Serializable
    object Divide : CELArithmeticOp() {
        override fun toString() = "/"
    }

    @SerialName("Multiply")
    @Serializable
    object Multiply : CELArithmeticOp() {
        override fun toString() = "*"
    }

    @SerialName("Modulus")
    @Serializable
    object Modulus : CELArithmeticOp() {
        override fun toString() = "%"
    }
}

@Serializable
@SerialName("CELUnaryOp")
@Polymorphic
internal sealed class CELUnaryOp {
    @SerialName("Not")
    @Serializable
    object Not : CELUnaryOp()

    @SerialName("DoubleNot")
    @Serializable
    object DoubleNot : CELUnaryOp()

    @SerialName("Minus")
    @Serializable
    object Minus : CELUnaryOp()

    @SerialName("DoubleMinus")
    @Serializable
    object DoubleMinus : CELUnaryOp()
}

@Serializable
@SerialName("CELExpression")
@Polymorphic
internal sealed class CELExpression : CELNode {
    @SerialName("Arithmetic")
    @Serializable
    data class Arithmetic(
        @SerialName("left") val left: CELExpression,
        @SerialName("op") val op: CELArithmeticOp,
        @SerialName("right") val right: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Arithmetic(
                    left.mapAll(transform),
                    op,
                    right.mapAll(transform),
                ),
            )

        override fun toString(): String = "$left $op $right"
    }

    @SerialName("Relation")
    @Serializable
    data class Relation(
        @SerialName("left") val left: CELExpression,
        @SerialName("op") val op: CELRelationOp,
        @SerialName("right") val right: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Relation(
                    left.mapAll(transform),
                    op,
                    right.mapAll(transform),
                ),
            )

        override fun toString(): String = "$left $op $right"
    }

    @SerialName("Ternary")
    @Serializable
    data class Ternary(
        @SerialName("condition") val condition: CELExpression,
        @SerialName("trueExpr") val trueExpr: CELExpression,
        @SerialName("falseExpr") val falseExpr: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Ternary(
                    condition.mapAll(transform),
                    trueExpr.mapAll(transform),
                    falseExpr.mapAll(transform),
                ),
            )

        override fun toString(): String = "$condition ? $trueExpr : $falseExpr"
    }

    @SerialName("Or")
    @Serializable
    data class Or(
        @SerialName("left") val left: CELExpression,
        @SerialName("right") val right: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Or(
                    left.mapAll(transform),
                    right.mapAll(transform),
                ),
            )

        override fun toString(): String = "$left || $right"
    }

    @SerialName("And")
    @Serializable
    data class And(
        @SerialName("left") val left: CELExpression,
        @SerialName("right") val right: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                And(
                    left.mapAll(transform),
                    right.mapAll(transform),
                ),
            )

        override fun toString(): String = "($left && $right)"
    }

    @SerialName("Unary")
    @Serializable
    data class Unary(
        @SerialName("op") val op: CELUnaryOp,
        @SerialName("expr") val expr: CELExpression,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Unary(
                    op,
                    expr.mapAll(transform),
                ),
            )

        override fun toString(): String = "+$expr)"
    }

    @SerialName("Member")
    @Serializable
    data class Member(
        @SerialName("expr") val expr: CELExpression,
        @SerialName("member") val member: CELMember,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Member(
                    expr.mapAll(transform),
                    member,
                ),
            )
    }

    @SerialName("FunctionCall")
    @Serializable
    data class FunctionCall(
        @SerialName("function") val function: CELExpression,
        @SerialName("receiver") val receiver: CELExpression?,
        @SerialName("arguments") val arguments: kotlin.collections.List<CELExpression>,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                FunctionCall(
                    function.mapAll(transform),
                    receiver?.mapAll(transform),
                    arguments.map { it.mapAll(transform) },
                ),
            )
    }

    @SerialName("List")
    @Serializable
    data class List(
        @SerialName("elements") val elements: kotlin.collections.List<CELExpression>,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                List(
                    elements.map { it.mapAll(transform) },
                ),
            )

        override fun toString(): String = "[${elements.joinToString(", ")}]"
    }

    @SerialName("Map")
    @Serializable
    data class Map(
        @SerialName("entries") val entries: kotlin.collections.List<Pair<CELExpression, CELExpression>>,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression =
            transform(
                Map(
                    entries.map { (key, value) ->
                        key.mapAll(transform) to value.mapAll(transform)
                    },
                ),
            )
    }

    @SerialName("Atom")
    @Serializable
    data class Atom(
        @SerialName("value") val value: CELAtom,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression = transform(this)

        override fun toString(): String = value.toString()
    }

    @SerialName("Ident")
    @Serializable
    data class Ident(
        @SerialName("name") val name: String,
    ) : CELExpression() {
        override fun mapAll(transform: (CELExpression) -> CELExpression): CELExpression = transform(this)

        override fun toString(): String = name
    }
}

@Serializable
@SerialName("CELMember")
@Polymorphic
internal sealed class CELMember {
    @SerialName("Attribute")
    @Serializable
    data class Attribute(
        @SerialName("name") val name: String,
    ) : CELMember()

    @SerialName("Index")
    @Serializable
    data class Index(
        @SerialName("expr") val expr: CELExpression,
    ) : CELMember()

    @SerialName("Fields")
    @Serializable
    data class Fields(
        @SerialName("fields") val fields: List<Pair<String, CELExpression>>,
    ) : CELMember()
}

@Serializable
@SerialName("CELAtom")
internal sealed class CELAtom {
    @SerialName("Int")
    @Serializable
    data class Int(
        @SerialName("value") val value: Long,
    ) : CELAtom()

    @SerialName("UInt")
    @Serializable
    data class UInt(
        @SerialName("value") val value: ULong,
    ) : CELAtom()

    @SerialName("Float")
    @Serializable
    data class Float(
        @SerialName("value") val value: Double,
    ) : CELAtom()

    @SerialName("String")
    @Serializable
    data class String(
        @SerialName("value") val value: kotlin.String,
    ) : CELAtom()

    @SerialName("Bytes")
    @Serializable
    data class Bytes(
        @SerialName("value") val value: ByteArray,
    ) : CELAtom()

    @SerialName("Bool")
    @Serializable
    data class Bool(
        @SerialName("value") val value: Boolean,
    ) : CELAtom()

    @SerialName("Null")
    @Serializable
    object Null : CELAtom()
}
