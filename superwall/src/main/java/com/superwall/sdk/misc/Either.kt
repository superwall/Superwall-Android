package com.superwall.sdk.misc

import android.util.Log
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

sealed class Either<out T, E : Throwable> {
    data class Success<T, E : Throwable>(
        val value: T,
    ) : Either<T, E>()

    data class Failure<E : Throwable>(
        val error: E,
    ) : Either<Nothing, E>()

    fun getSuccess(): T? =
        when (this) {
            is Success<T, E> -> this.value
            else -> null
        }
}

suspend fun <In, E : Throwable> Either<In, E>.then(then: suspend (In) -> Unit): Either<In, E> =
    when (this) {
        is Either.Success -> {
            then(this.value)
            this
        }

        is Either.Failure -> this
    }

fun <In, Out, E : Throwable> Either<In, E>.map(transform: (In) -> Out): Either<Out, E> =
    when (this) {
        is Either.Success -> Either.Success(transform(this.value))
        is Either.Failure -> this
    }

fun <T, E : Throwable, F : Throwable> Either<T, E>.mapError(transform: (E) -> F): Either<T, *> =
    when (this) {
        is Either.Success -> this
        is Either.Failure -> Either.Failure(transform(this.error))
    }

fun <T, E : Throwable> Either<T, E>.onError(onError: (E) -> Unit): Either<T, E> =
    when (this) {
        is Either.Success -> this
        is Either.Failure -> {
            onError(this.error)
            this
        }
    }

fun <T, Out, E : Throwable> Either<T, E>.flatMap(transform: (T) -> Either<Out, E>): Either<Out, E> =
    when (this) {
        is Either.Success -> transform(this.value)
        is Either.Failure -> this
    }

fun <T> Either<T, out Throwable>.unwrap(): T =
    when (this) {
        is Either.Success -> this.value
        is Either.Failure -> throw this.error
    }

suspend inline fun <T, E : Throwable> Either<T, E>.fold(
    crossinline onSuccess: suspend (T) -> Unit,
    crossinline onFailure: suspend (E) -> Unit,
) {
    when (this) {
        is Either.Success -> onSuccess(this.value)
        is Either.Failure -> onFailure(this.error)
    }
}

suspend inline fun <T, E : Throwable> Either<T, E>.into(crossinline map: suspend (Either<T, E>) -> Either<T, E>): Either<T, E> = map(this)

suspend fun <T> eitherWithTimeout(
    timeout: Duration,
    run: suspend () -> Either<T, out Throwable>,
): Either<T, out Throwable> {
    return try {
        withTimeout(timeout) {
            val res = run()
            Log.e("Either", "res: $res")
            return@withTimeout res
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        return Either.Failure(e)
    }
}
