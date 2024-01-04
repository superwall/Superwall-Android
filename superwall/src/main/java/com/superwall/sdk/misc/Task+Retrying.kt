package com.superwall.sdk.misc

import com.superwall.sdk.network.session.TaskRetryLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

suspend fun <T> retrying(
    coroutineContext: CoroutineContext,
    maxRetryCount: Int,
    isRetryingCallback: (() -> Unit)?,
    operation: suspend () -> T
): T = withContext(coroutineContext) {
    for (attempt in 0 until maxRetryCount) {
        try {
            return@withContext operation()
        } catch (e: Throwable) {
            isRetryingCallback?.invoke()
            val delayTime = TaskRetryLogic.delay(attempt, maxRetryCount) ?: break
            delay(delayTime)
        }
    }
    ensureActive()
    operation()
}