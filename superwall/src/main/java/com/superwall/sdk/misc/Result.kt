package com.superwall.sdk.misc

sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val error: Throwable) : Result<Nothing>()

    fun getSuccess(): T? {
       return when (this) {
           is Success<T> -> this.value
           else -> null
       }
    }
}