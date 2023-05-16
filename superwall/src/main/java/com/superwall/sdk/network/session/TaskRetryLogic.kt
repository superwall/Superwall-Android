package com.superwall.sdk.network.session

//  File.kt

import kotlin.math.pow
import kotlin.random.Random

object TaskRetryLogic {
    fun delay(
        attempt: Int,
        maxRetries: Int
    ): ULong? {
        if (attempt > maxRetries) {
            return null
        }
        val jitter = Random.nextDouble(0.0, 1.0)
        val initialDelay = 5.0
        val multiplier = 1.0
        val attemptRatio = attempt.toDouble() / maxRetries.toDouble()
        val delay = initialDelay.pow(multiplier + attemptRatio) + jitter

        val oneSecond = 1_000_000_000.0
        return (oneSecond * delay * 1000).toULong()
    }
}