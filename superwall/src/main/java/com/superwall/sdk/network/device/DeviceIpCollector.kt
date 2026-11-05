package com.superwall.sdk.network.device

import com.superwall.sdk.BuildConfig
import com.superwall.sdk.misc.primitives.StateActor
import kotlinx.coroutines.CoroutineScope
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
 *
 * Facade over a [DeviceIpState] actor: observations and lookup schedules live in its state, and
 * lookups are claimed in its queue so the same one never starts twice.
 */
class DeviceIpCollector internal constructor(
    override val lookups: List<Lookup>,
    override val scope: CoroutineScope,
    override val now: () -> Long = { System.currentTimeMillis() },
    override val actor: StateActor<DeviceIpContext, DeviceIpState>,
) : DeviceIpContext {
    /**
     * One address lookup with its own schedule, so a failing IPv6 lookup doesn't hold up or
     * speed up the IPv4 one.
     */
    class Lookup internal constructor(
        val name: String,
        val family: Int,
        val fetch: suspend () -> Map<String, String>,
    )

    /**
     * Starts any lookups that are due, in the background.
     *
     * @param families The address families to look up, or null for all of them.
     */
    internal fun refreshIfNeeded(families: Set<Int>? = null) {
        effect(DeviceIpState.Actions.Refresh(families))
    }

    internal fun record(device: Map<String, String>) {
        update(DeviceIpState.Updates.Recorded(device, now()))
    }

    /**
     * Starts the IPv4 lookup only when the device has been on IPv6 this session and no fresh
     * IPv4 address is known.
     */
    internal fun refreshIpV4IfOnlyIpV6IsKnown() {
        effect(DeviceIpState.Actions.RefreshIpV4IfOnlyIpV6IsKnown)
    }

    internal fun attributes(families: Set<Int> = setOf(4, 6)): Map<String, String> = state.value.attributes(families, now())

    internal companion object {
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
