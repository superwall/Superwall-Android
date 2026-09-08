package com.superwall.sdk.web

import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import org.threeten.bp.DateTimeException
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime

/** A config delay is relative to checkout, whereas WorkManager needs a delay relative to redemption. */
internal fun webTrialReminderDelay(
    product: PaywallProduct,
    configuredDelay: Long,
    now: Long,
): Long? {
    if (product.trialPeriodDays <= 0 || configuredDelay <= 0) return null
    // Display strings and calendar dates have no unambiguous instant. Never guess their timezone.
    return try {
        val end = OffsetDateTime.parse(product.trialPeriodEndDate).toInstant()
        val target = end.minus(Duration.ofDays(product.trialPeriodDays.toLong())).plusMillis(configuredDelay)
        val current = Instant.ofEpochMilli(now)
        if (target <= current || target >= end) null else Duration.between(current, target).toMillis()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
