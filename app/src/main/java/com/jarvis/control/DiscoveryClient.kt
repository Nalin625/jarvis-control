package com.jarvis.control

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.json.JSONObject

/** A laptop that answered the discovery broadcast. */
data class FoundLaptop(val ip: String, val port: Int, val name: String)

/**
 * Finds Jarvis laptops on the WiFi: broadcasts one small UDP packet and collects replies
 * for ~1.5 s. The laptop's reply carries no secrets (just its name and port).
 */
object DiscoveryClient {
    const val DISCOVERY_PORT = 8898
    private const val PROBE = "JARVIS_DISCOVER"

    fun find(timeoutMs: Int = 1500): List<FoundLaptop> {
        val found = LinkedHashMap<String, FoundLaptop>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true; soTimeout = 400 }
            val data = PROBE.toByteArray()
            // global broadcast + a couple of repeats (UDP can drop the first one)
            repeat(3) {
                socket.send(
                    DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                )
                Thread.sleep(150)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val o = JSONObject(String(pkt.data, 0, pkt.length))
                    if (o.optString("app") == "jarvis") {
                        val ip = pkt.address.hostAddress ?: continue
                        found[ip] = FoundLaptop(ip, o.optInt("port", 8899), o.optString("name", ip))
                    }
                } catch (e: SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (e: Exception) {
                    // ignore malformed replies
                }
            }
        } catch (e: Exception) {
            // no network: return whatever we have (nothing)
        } finally {
            socket?.close()
        }
        return found.values.toList()
    }
}
