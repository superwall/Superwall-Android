package com.superwall.sdk.network.device

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction

/**
 * The device's public IP addresses seen this session, and when each lookup is next due.
 */
data class DeviceIpState(
    /** The latest address of each family and when it was seen, under `ipV4`, `ipV4ObservedAt` and so on. */
    val observations: Map<String, String> = emptyMap(),
    /** Each lookup's schedule, by lookup name. */
    val schedules: Map<String, Schedule> = emptyMap(),
    /**
     * Whether an IPv6 address has been seen this session. Kept after that address goes stale,
     * since enrichment may not run again this session.
     */
    val hasSeenIpV6: Boolean = false,
) {
    data class Schedule(
        val lastAttempt: Long,
        val nextAttemptAt: Long,
    )

    internal fun isDue(
        lookup: DeviceIpCollector.Lookup,
        now: Long,
    ): Boolean {
        val nextAttemptAt = schedules[lookup.name]?.nextAttemptAt ?: return true
        return now >= nextAttemptAt
    }

    internal fun hasFreshObservation(
        family: Int,
        now: Long,
    ): Boolean {
        val date = observations["ipV${family}ObservedAt"]?.let { DeviceIpCollector.parseDate(it) } ?: return false
        return isFresh(date, now)
    }

    internal fun attributes(
        families: Set<Int>,
        now: Long,
    ): Map<String, String> =
        families
            .sorted()
            .filter { hasFreshObservation(it, now) }
            .flatMap { family ->
                val key = "ipV$family"
                listOf(
                    key to observations.getValue(key),
                    "${key}ObservedAt" to observations.getValue("${key}ObservedAt"),
                )
            }.toMap()

    internal sealed class Updates(
        override val reduce: (DeviceIpState) -> DeviceIpState,
    ) : Reducer<DeviceIpState> {
        data class Attempted(
            val lookupNames: List<String>,
            val at: Long,
        ) : Updates({ state ->
                state.copy(
                    schedules =
                        state.schedules +
                            lookupNames.associateWith { Schedule(lastAttempt = at, nextAttemptAt = at + LIFETIME_MILLIS) },
                )
            })

        /** Tries again sooner than a success would, unless a newer attempt has started. */
        data class Failed(
            val lookupName: String,
            val attemptedAt: Long,
        ) : Updates({ state ->
                val schedule = state.schedules[lookupName]
                if (schedule == null || schedule.lastAttempt != attemptedAt) {
                    state
                } else {
                    state.copy(
                        schedules = state.schedules + (lookupName to schedule.copy(nextAttemptAt = attemptedAt + RETRY_DELAY_MILLIS)),
                    )
                }
            })

        data class Recorded(
            val device: Map<String, String>,
            val now: Long,
        ) : Updates({ state ->
                var observations = state.observations
                var hasSeenIpV6 = state.hasSeenIpV6
                for (family in listOf(4, 6)) {
                    val key = "ipV$family"
                    // Each address only counts with its own timestamp, so an `ipV6` with no time
                    // can't borrow the time of an IPv4 `ipAddress`.
                    val familyAddress = device[key]
                    val address = familyAddress ?: device["ipAddress"]
                    val timestamp =
                        if (familyAddress != null) device["${key}ObservedAt"] else device["ipAddressObservedAt"]
                    if (address == null || timestamp == null) {
                        continue
                    }
                    val date = DeviceIpCollector.parseDate(timestamp) ?: continue
                    if (!DeviceIpCollector.isValid(address, family) || !isFresh(date, now)) {
                        continue
                    }
                    val previousDate = observations["${key}ObservedAt"]?.let { DeviceIpCollector.parseDate(it) }
                    if (previousDate != null && previousDate > date) {
                        continue
                    }
                    observations = observations + mapOf(key to address, "${key}ObservedAt" to timestamp)
                    if (family == 6) {
                        hasSeenIpV6 = true
                    }
                }
                state.copy(observations = observations, hasSeenIpV6 = hasSeenIpV6)
            })
    }

    internal sealed class Actions(
        override val execute: suspend DeviceIpContext.() -> Unit,
    ) : TypedAction<DeviceIpContext> {
        /**
         * Starts any lookups that are due, in the background.
         *
         * @param families The address families to look up, or null for all of them.
         */
        data class Refresh(
            val families: Set<Int>?,
        ) : Actions({
                val date = now()
                val due =
                    lookups.filter { lookup ->
                        (families == null || lookup.family in families) && state.value.isDue(lookup, date)
                    }
                if (due.isNotEmpty()) {
                    update(Updates.Attempted(due.map { it.name }, date))
                    due.forEach { lookup -> sideEffect { fetch(lookup, date) } }
                }
            })

        /**
         * Starts the IPv4 lookup only when the device has been on IPv6 this session and no fresh
         * IPv4 address is known. On an IPv4 network the device's own requests already show its
         * IPv4 address, so there's nothing to look up.
         */
        object RefreshIpV4IfOnlyIpV6IsKnown : Actions({
            val current = state.value
            if (current.hasSeenIpV6 && !current.hasFreshObservation(4, now())) {
                immediate(Refresh(setOf(4)))
            }
        })
    }

    internal companion object {
        const val LIFETIME_MILLIS = 15 * 60 * 1000L

        /**
         * How long to wait after a failed fetch. Shorter than the lifetime so a blip is retried
         * soon, but long enough that an outage or offline device doesn't send a request on every
         * read of the device attributes.
         */
        const val RETRY_DELAY_MILLIS = 60 * 1000L

        fun isFresh(
            date: Long,
            now: Long,
        ): Boolean {
            val age = now - date
            return age >= -60_000 && age < LIFETIME_MILLIS
        }
    }
}

/** Runs off the actor's queue, so a slow lookup doesn't hold up the others. */
private suspend fun DeviceIpContext.fetch(
    lookup: DeviceIpCollector.Lookup,
    attemptedAt: Long,
) {
    try {
        val device = lookup.fetch()
        update(DeviceIpState.Updates.Recorded(device, now()))
    } catch (e: Throwable) {
        update(DeviceIpState.Updates.Failed(lookup.name, attemptedAt))
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.network,
            message = "Couldn't fetch the device's ${lookup.name} address",
            error = e,
        )
    }
}
