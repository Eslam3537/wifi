package com.example.domain.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object SsdpDiscoveryHelper {

    data class SsdpDeviceMetadata(
        val ip: String,
        val friendlyName: String? = null,
        val manufacturer: String? = null,
        val modelName: String? = null,
        val modelNumber: String? = null,
        val deviceType: String? = null,
        val locationUrl: String? = null
    )

    /**
     * Broadcasts SSDP M-SEARCH query and collects device responses.
     */
    suspend fun discoverSsdpDevices(timeoutMs: Int = 1200): List<SsdpDeviceMetadata> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SsdpDeviceMetadata>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n"

            val sendData = mSearch.toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("239.255.255.250")
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, 1900)
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(recvPacket)

                    val response = String(buffer, 0, recvPacket.length, Charsets.UTF_8)
                    val senderIp = recvPacket.address.hostAddress ?: continue
                    val location = extractHeader(response, "LOCATION")

                    val metadata = if (!location.isNullOrBlank()) {
                        fetchXmlMetadata(senderIp, location) ?: parseHeadersOnly(senderIp, response)
                    } else {
                        parseHeadersOnly(senderIp, response)
                    }

                    if (metadata != null) {
                        results.add(metadata)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
            // Ignored
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        results
    }

    /**
     * Probes SSDP metadata directly for a specific IP using directed unicast / standard UPnP description paths.
     */
    suspend fun probeDeviceSsdp(ip: String): SsdpDeviceMetadata? = withContext(Dispatchers.IO) {
        val commonUrls = listOf(
            "http://$ip:1900/description.xml",
            "http://$ip:8008/ssdp/device-desc.xml",
            "http://$ip:5000/rootDesc.xml",
            "http://$ip:49152/description.xml",
            "http://$ip:80/rootDesc.xml",
            "http://$ip:8080/description.xml"
        )

        for (url in commonUrls) {
            val meta = fetchXmlMetadata(ip, url)
            if (meta != null && (!meta.friendlyName.isNullOrBlank() || !meta.manufacturer.isNullOrBlank())) {
                return@withContext meta
            }
        }
        null
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val regex = Regex("(?i)^$headerName:\\s*(.+)$", RegexOption.MULTILINE)
        return regex.find(response)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseHeadersOnly(ip: String, response: String): SsdpDeviceMetadata? {
        val server = extractHeader(response, "SERVER")
        val st = extractHeader(response, "ST")
        val usn = extractHeader(response, "USN")

        if (server.isNullOrBlank() && st.isNullOrBlank()) return null

        return SsdpDeviceMetadata(
            ip = ip,
            friendlyName = null,
            manufacturer = server,
            modelName = null,
            deviceType = st,
            locationUrl = null
        )
    }

    private fun fetchXmlMetadata(ip: String, locationUrl: String): SsdpDeviceMetadata? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(locationUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val xml = reader.readText()
            reader.close()

            val friendlyName = extractTag(xml, "friendlyName")
            val manufacturer = extractTag(xml, "manufacturer")
            val modelName = extractTag(xml, "modelName")
            val modelNumber = extractTag(xml, "modelNumber")
            val deviceType = extractTag(xml, "deviceType")

            return SsdpDeviceMetadata(
                ip = ip,
                friendlyName = cleanXmlString(friendlyName),
                manufacturer = cleanXmlString(manufacturer),
                modelName = cleanXmlString(modelName),
                modelNumber = cleanXmlString(modelNumber),
                deviceType = deviceType,
                locationUrl = locationUrl
            )
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun cleanXmlString(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val clean = text.replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .trim()
        return if (clean.isBlank()) null else clean
    }
}
