package com.nuvio.tv.data.mediaserver

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** A media server that answered the network search. [address] is a ready-to-use base URL. */
data class DiscoveredServer(val name: String, val address: String)

/**
 * Finds Emby and Jellyfin servers on the local network, the way their own apps do: a short UDP
 * broadcast question on port 7359 that every server on the network answers with its name and
 * address (for example `http://192.168.1.20:8096`).
 */
object ServerDiscovery {
    private const val DISCOVERY_PORT = 7359
    private const val RESEND_EVERY_MS = 700L
    private const val SEND_ROUNDS = 3

    /** The question each server type listens for (case does not matter to the server). */
    private fun question(providerId: String): String? = when (providerId) {
        "emby" -> "who is EmbyServer?"
        "jellyfin" -> "who is JellyfinServer?"
        else -> null
    }

    fun isSupported(providerId: String): Boolean = question(providerId) != null

    /** Listens for [listenMs] and returns every server that answered, in the order they replied. */
    suspend fun find(
        context: Context,
        providerId: String,
        listenMs: Long = 3_000L
    ): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val message = question(providerId)?.toByteArray(Charsets.UTF_8) ?: return@withContext emptyList()
        val found = LinkedHashMap<String, DiscoveredServer>()
        val targets = broadcastTargets(context)
        try {
            DatagramSocket(null).use { socket ->
                socket.setReuseAddress(true)
                socket.setBroadcast(true)
                socket.setSoTimeout(300)
                socket.bind(InetSocketAddress(0))

                val buffer = ByteArray(2048)
                val start = SystemClock.elapsedRealtime()
                var rounds = 0
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (elapsed >= listenMs) break
                    // UDP can drop packets, so ask a few times.
                    if (rounds < SEND_ROUNDS && elapsed >= rounds * RESEND_EVERY_MS) {
                        targets.forEach { target ->
                            runCatching {
                                socket.send(DatagramPacket(message, message.size, target, DISCOVERY_PORT))
                            }
                        }
                        rounds++
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        parse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { server ->
                            found.putIfAbsent(server.address.lowercase(), server)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Nothing yet; loop to resend or stop.
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // No network or the radio refused the broadcast: report whatever was found.
        }
        found.values.toList()
    }

    private fun parse(text: String): DiscoveredServer? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val address = json.optString("Address").trim().trimEnd('/')
        if (!address.startsWith("http://", ignoreCase = true) && !address.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val name = json.optString("Name").trim().ifEmpty { address }
        return DiscoveredServer(name = name, address = address)
    }

    /** The general broadcast address plus this network's own broadcast address (some routers need it). */
    private fun broadcastTargets(context: Context): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val properties = if (network != null) manager.getLinkProperties(network) else null
            properties?.linkAddresses?.forEach { link ->
                val ip = link.address as? Inet4Address ?: return@forEach
                val prefix = link.prefixLength
                if (prefix in 1..30) {
                    val ipValue = ByteBuffer.wrap(ip.address).int
                    val mask = -1 shl (32 - prefix)
                    val broadcast = ipValue or mask.inv()
                    targets.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array()))
                }
            }
        }
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        return targets.toList()
    }
}
