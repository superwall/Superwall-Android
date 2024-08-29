package com.superwall.sdk.misc

sealed class Result<out T> {
    data class Success<T>(
        val value: T,
    ) : Result<T>()

    data class Failure(
        val error: Throwable,
    ) : Result<Nothing>()

    fun getSuccess(): T? =
        when (this) {
            is Success<T> -> this.value
            else -> null
        }
}

fun <In, Out> Result<In>.map(transform: (In) -> Out): Result<Out> =
    when (this) {
        is Result.Success -> Result.Success(transform(this.value))
        is Result.Failure -> this
    }

fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> =
    when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.Failure(transform(this.error))
    }

fun <T, Out> Result<T>.flatMap(transform: (T) -> Result<Out>): Result<Out> =
    when (this) {
        is Result.Success -> transform(this.value)
        is Result.Failure -> this
    }

fun <T> Result<T>.unwrap(): T =
    when (this) {
        is Result.Success -> this.value
        is Result.Failure -> throw this.error
    }

fun <T> Result<T>.fold(
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    when (this) {
        is Result.Success -> onSuccess(this.value)
        is Result.Failure -> onFailure(this.error)
    }
}
