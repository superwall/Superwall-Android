package com.superwall.sdk.network.device

import com.superwall.sdk.misc.IOScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.threeten.bp.Instant
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/** Session-local observations, bounded independently from configuration and purchases. */
internal class DeviceIPCollector(
    private val scope: CoroutineScope = IOScope(),
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend () -> Map<String, String> = { fetchIPv4() },
) {
    private var lastAttempt: Long? = null
    private val observations = mutableMapOf<String, String>()

    @Synchronized
    fun refreshIfNeeded() {
        val current = now()
        if (lastAttempt?.let { current - it < LIFETIME } == true) return
        lastAttempt = current
        scope.launch {
            try {
                record(fetch())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Optional enrichment must not fail configuration or purchase flows.
            }
        }
    }

    @Synchronized
    fun record(device: Map<String, Any>) {
        for (family in listOf(4, 6)) {
            val key = "ipV$family"
            val address = (device[key] ?: device["ipAddress"]) as? String ?: continue
            val timestamp = (device["${key}ObservedAt"] ?: device["ipAddressObservedAt"]) as? String ?: continue
            if (!isValid(address, family) || !isFresh(timestamp)) continue
            val previous = observations["${key}ObservedAt"]
            if (previous != null && Instant.parse(previous).isAfter(Instant.parse(timestamp))) continue
            observations[key] = address
            observations["${key}ObservedAt"] = timestamp
        }
    }

    @Synchronized
    fun attributes(): Map<String, String> = buildMap {
        for (key in listOf("ipV4", "ipV6")) {
            val timestamp = observations["${key}ObservedAt"] ?: continue
            val address = observations[key] ?: continue
            if (isFresh(timestamp)) {
                put(key, address)
                put("${key}ObservedAt", timestamp)
            }
        }
    }

    private fun isFresh(timestamp: String): Boolean =
        try {
            now() - Instant.parse(timestamp).toEpochMilli() in -60_000 until LIFETIME
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val LIFETIME = 15 * 60 * 1000L
        val attributeKeys = setOf("ipV4", "ipV6", "ipV4ObservedAt", "ipV6ObservedAt", "ipAddress", "ipAddressObservedAt")

        internal fun isValid(address: String, family: Int): Boolean {
            if (family == 4) {
                val octets = address.split('.')
                return octets.size == 4 && octets.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) <= 255
                }
            }
            // Numeric literals only: never resolve an arbitrary hostname from a response.
            if (!address.contains(':') || !address.matches(Regex("[0-9a-fA-F:]+"))) return false
            return try {
                InetAddress.getByName(address) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }

        private fun fetchIPv4(): Map<String, String> {
            val connection = URL("https://v4.superwall-enrichment.com/api/v1/enrich").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return emptyMap()
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(16_384)
                    var size = 0
                    while (size < buffer.size) {
                        val read = input.read(buffer, size, buffer.size - size)
                        if (read == -1) break
                        size += read
                    }
                    if (size == buffer.size) return emptyMap()
                    buffer.copyOf(size)
                }
                return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    .getValue("device").jsonObject.mapValues { it.value.jsonPrimitive.content }
            } finally {
                connection.disconnect()
            }
        }
    }
}
