package com.superwall.sdk.misc

import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.models.config.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

suspend fun Flow<Result<ConfigState>>.awaitFirstValidConfig(): Config? {
    return try {
        first { result ->
            if (result is Result.Failure) return@first false
            result.getSuccess()?.getConfig() != null
        }.getSuccess()?.getConfig()
    } catch (e: Throwable) {
        null
    }
}
