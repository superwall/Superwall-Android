package com.superwall.sdk.misc

sealed class Either<out T> {
    data class Success<T>(
        val value: T,
    ) : Either<T>()

    data class Failure(
        val error: Throwable,
    ) : Either<Nothing>()

    fun getSuccess(): T? =
        when (this) {
            is Success<T> -> this.value
            else -> null
        }
}

fun <In, Out> Either<In>.map(transform: (In) -> Out): Either<Out> =
    when (this) {
        is Either.Success -> Either.Success(transform(this.value))
        is Either.Failure -> this
    }

fun <T> Either<T>.mapError(transform: (Throwable) -> Throwable): Either<T> =
    when (this) {
        is Either.Success -> this
        is Either.Failure -> Either.Failure(transform(this.error))
    }

fun <T, Out> Either<T>.flatMap(transform: (T) -> Either<Out>): Either<Out> =
    when (this) {
        is Either.Success -> transform(this.value)
        is Either.Failure -> this
    }

fun <T> Either<T>.unwrap(): T =
    when (this) {
        is Either.Success -> this.value
        is Either.Failure -> throw this.error
    }

suspend fun <T> Either<T>.fold(
    onSuccess: suspend (T) -> Unit,
    onFailure: suspend (Throwable) -> Unit,
) {
    when (this) {
        is Either.Success -> onSuccess(this.value)
        is Either.Failure -> onFailure(this.error)
    }
}
