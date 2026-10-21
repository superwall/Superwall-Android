package com.superwall.sdk.network.device

import com.superwall.sdk.BuildConfig
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.threeten.bp.Instant
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Best-effort, session-local observations of the device's public IP addresses, for install
 * attribution. Never waits on the network when reading attributes. Mirrors `DeviceIPCollector`
 * on iOS.
 */
class DeviceIpCollector internal constructor(
    private val lookups: List<Lookup>,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * One address lookup with its own schedule, so a failing IPv6 lookup doesn't hold up or
     * speed up the IPv4 one.
     */
    internal class Lookup(
        val name: String,
        val family: Int,
        val fetch: suspend () -> Map<String, String>,
    ) {
        internal var lastAttempt: Long? = null
        internal var nextAttemptAt: Long? = null
    }

    private val lock = Any()
    private val observations = mutableMapOf<String, String>()

    /**
     * Whether an IPv6 address has been seen this session. Kept after that address goes stale,
     * since enrichment may not run again this session.
     */
    private var hasSeenIpV6 = false

    /**
     * Starts any lookups that are due, in the background. The returned job finishes when they
     * do and is only there so tests can wait for it.
     *
     * @param families The address families to look up, or null for all of them.
     */
    internal fun refreshIfNeeded(families: Set<Int>? = null): Job? {
        val date = now()
        val started = mutableListOf<Job>()
        synchronized(lock) {
            for (lookup in lookups) {
                if (families != null && lookup.family !in families) {
                    continue
                }
                val nextAttemptAt = lookup.nextAttemptAt
                if (nextAttemptAt != null && date < nextAttemptAt) {
                    continue
                }
                lookup.lastAttempt = date
                lookup.nextAttemptAt = date + LIFETIME_MILLIS
                started += start(lookup, date)
            }
        }
        if (started.isEmpty()) {
            return null
        }
        return scope.launch { started.joinAll() }
    }

    private fun start(
        lookup: Lookup,
        attemptedAt: Long,
    ): Job =
        scope.launch {
            try {
                record(lookup.fetch())
            } catch (e: Throwable) {
                synchronized(lock) {
                    // Try again sooner than a success would, unless a newer attempt started.
                    if (lookup.lastAttempt == attemptedAt) {
                        lookup.nextAttemptAt = attemptedAt + RETRY_DELAY_MILLIS
                    }
                }
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.network,
                    message = "Couldn't fetch the device's ${lookup.name} address",
                    error = e,
                )
            }
        }

    internal fun record(device: Map<String, String>) {
        synchronized(lock) {
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
                val date = parseDate(timestamp) ?: continue
                if (!isValid(address, family) || !isFresh(date)) {
                    continue
                }
                val previousDate = observations["${key}ObservedAt"]?.let { parseDate(it) }
                if (previousDate != null && previousDate > date) {
                    continue
                }
                observations[key] = address
                observations["${key}ObservedAt"] = timestamp
                if (family == 6) {
                    hasSeenIpV6 = true
                }
            }
        }
    }

    /**
     * Starts the IPv4 lookup only when the device has been on IPv6 this session and no fresh
     * IPv4 address is known. On an IPv4 network the device's own requests already show its
     * IPv4 address, so there's nothing to look up.
     */
    internal fun refreshIpV4IfOnlyIpV6IsKnown(): Job? {
        val shouldRefresh = synchronized(lock) { hasSeenIpV6 && !hasFreshObservation(4) }
        if (!shouldRefresh) {
            return null
        }
        return refreshIfNeeded(setOf(4))
    }

    internal fun attributes(families: Set<Int> = setOf(4, 6)): Map<String, String> =
        synchronized(lock) {
            families
                .sorted()
                .filter { hasFreshObservation(it) }
                .flatMap { family ->
                    val key = "ipV$family"
                    listOf(
                        key to observations.getValue(key),
                        "${key}ObservedAt" to observations.getValue("${key}ObservedAt"),
                    )
                }.toMap()
        }

    private fun hasFreshObservation(family: Int): Boolean {
        val date = observations["ipV${family}ObservedAt"]?.let { parseDate(it) } ?: return false
        return isFresh(date)
    }

    private fun isFresh(date: Long): Boolean {
        val age = now() - date
        return age >= -60_000 && age < LIFETIME_MILLIS
    }

    internal companion object {
        private const val LIFETIME_MILLIS = 15 * 60 * 1000L

        /**
         * How long to wait after a failed fetch. Shorter than the lifetime so a blip is retried
         * soon, but long enough that an outage or offline device doesn't send a request on every
         * read of the device attributes.
         */
        private const val RETRY_DELAY_MILLIS = 60 * 1000L

        private const val TIMEOUT_MILLIS = 3_000
        private const val MAX_RESPONSE_SIZE = 16_384

        /** Device attribute keys the collector owns, which enrichment mustn't fill in. */
        val ATTRIBUTE_KEYS = listOf("ipV4", "ipV6", "ipV4ObservedAt", "ipV6ObservedAt")

        private val ipV4Pattern =
            Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

        /**
         * Lookups for the given endpoints. A null URL isn't looked up.
         *
         * @param ipV4Url An IPv4-only endpoint to ask.
         * @param ipV6Url An endpoint to ask over IPv6 only.
         */
        fun lookups(
            ipV4Url: String?,
            ipV6Url: String?,
        ): List<Lookup> =
            listOfNotNull(
                ipV4Url?.let { url -> Lookup("IPv4", 4) { fetchIpV4(url) } },
                ipV6Url?.let { url -> Lookup("IPv6", 6) { fetchOverIpV6(url) } },
            )

        fun isValid(
            address: String,
            family: Int,
        ): Boolean {
            if (family == 4) {
                return ipV4Pattern.matches(address)
            }
            if (!address.contains(':') || address.lowercase().startsWith("::ffff:")) {
                return false
            }
            // A literal containing ':' is parsed without a DNS lookup.
            return runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
        }

        /** Parses ISO 8601 timestamps with or without milliseconds. */
        fun parseDate(timestamp: String): Long? = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()

        /** Reads the string values of the response's `device` object, skipping any that aren't strings. */
        fun parseDevice(body: String): Map<String, String> {
            val device = Json.parseToJsonElement(body).jsonObject["device"] as? JsonObject
            if (device == null) {
                throw IOException("Response has no device object")
            }
            return device
                .mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
                }.toMap()
        }

        private fun fetchIpV4(url: String): Map<String, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                if (connection.responseCode != 200) {
                    throw IOException("Unexpected status ${connection.responseCode}")
                }
                return parseDevice(connection.inputStream.readLimited().toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }

        /**
         * Asks [url] over IPv6 only, so the server sees the device's IPv6 address. Connects to
         * the host's IPv6 address directly and speaks HTTP/1.0 over TLS, since
         * [HttpURLConnection] can't be told which address family to use.
         */
        private fun fetchOverIpV6(url: String): Map<String, String> {
            val parsed = URL(url)
            if (parsed.protocol != "https") {
                throw IOException("Only https is supported")
            }
            val host = parsed.host
            val port = if (parsed.port == -1) 443 else parsed.port
            val address =
                InetAddress.getAllByName(host).firstOrNull { it is Inet6Address }
                    ?: throw IOException("$host has no IPv6 address")

            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MILLIS)
                socket.soTimeout = TIMEOUT_MILLIS
                val sslSocket =
                    (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, host, port, true) as SSLSocket
                sslSocket.use { ssl ->
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        throw SSLPeerUnverifiedException("Certificate doesn't match $host")
                    }
                    val path = parsed.path.ifEmpty { "/" }
                    val request =
                        "GET $path HTTP/1.0\r\n" +
                            "Host: $host\r\n" +
                            "User-Agent: SuperwallAndroid/${BuildConfig.SDK_VERSION}\r\n" +
                            "Accept: application/json\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                    ssl.outputStream.apply {
                        write(request.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    return parseDevice(bodyOfHttpResponse(ssl.inputStream.readLimited()))
                }
            } finally {
                runCatching { socket.close() }
            }
        }

        fun bodyOfHttpResponse(response: ByteArray): String {
            val text = response.toString(Charsets.UTF_8)
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd == -1) {
                throw IOException("Incomplete HTTP response")
            }
            val parts =
                text
                    .substring(0, headerEnd)
                    .lineSequence()
                    .first()
                    .split(" ")
            if (parts.size < 2 || !parts[0].startsWith("HTTP/") || parts[1] != "200") {
                throw IOException("Unexpected status line")
            }
            return text.substring(headerEnd + 4)
        }

        private fun InputStream.readLimited(): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val read = read(buffer)
                if (read == -1) {
                    break
                }
                output.write(buffer, 0, read)
                if (output.size() >= MAX_RESPONSE_SIZE) {
                    throw IOException("Response too large")
                }
            }
            return output.toByteArray()
        }
    }
}
