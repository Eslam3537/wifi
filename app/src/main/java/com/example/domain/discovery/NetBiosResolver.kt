package com.example.domain.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NetBiosResolver {

    /**
     * Sends an RFC 1002 NetBIOS Name Query (Node Status Request) to UDP port 137 of target IP.
     * Returns the primary computer / workstation name if responsive.
     */
    suspend fun queryNetBiosName(ip: String, timeoutMs: Int = 350): String? = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isValidIpv4(ip)) return@withContext null

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            // NetBIOS Node Status Request Packet
            // Transaction ID: 0x1337, Flags: 0x0000, Questions: 1, Answer RRs: 0, Authority RRs: 0, Additional RRs: 0
            // Question Name: '*' encoded as CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA (32 bytes) + 0x00
            // Question Type: 0x0021 (NBSTAT), Question Class: 0x0001 (IN)
            val request = byteArrayOf(
                0x13.toByte(), 0x37.toByte(), // Transaction ID
                0x00.toByte(), 0x00.toByte(), // Flags (Query)
                0x00.toByte(), 0x01.toByte(), // Questions: 1
                0x00.toByte(), 0x00.toByte(), // Answer RRs: 0
                0x00.toByte(), 0x00.toByte(), // Authority RRs: 0
                0x00.toByte(), 0x00.toByte(), // Additional RRs: 0
                // Name length: 32 bytes
                0x20.toByte(),
                // Encoded wildcard name '*' -> CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
                'C'.code.toByte(), 'K'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
                'A'.code.toByte(), 'A'.code.toByte(),
                0x00.toByte(), // Terminator
                0x00.toByte(), 0x21.toByte(), // Type: NBSTAT
                0x00.toByte(), 0x01.toByte()  // Class: IN
            )

            val address = InetAddress.getByName(ip)
            val packet = DatagramPacket(request, request.size, address, 137)
            socket.send(packet)

            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            parseNetBiosResponse(responseBuffer, responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Parses the NetBIOS Node Status Response to extract the unique computer name.
     */
    private fun parseNetBiosResponse(data: ByteArray, length: Int): String? {
        if (length < 57) return null

        // Number of names is at offset 56
        val numberOfNames = data[56].toInt() and 0xFF
        if (numberOfNames == 0 || length < 57 + (numberOfNames * 18)) return null

        var bestName: String? = null

        // Each name record is 18 bytes: 15 bytes ASCII name, 1 byte scope/type, 2 bytes flags
        for (i in 0 until numberOfNames) {
            val offset = 57 + (i * 18)
            val nameBytes = data.copyOfRange(offset, offset + 15)
            val nameType = data[offset + 15].toInt() and 0xFF
            val flags = ((data[offset + 16].toInt() and 0xFF) shl 8) or (data[offset + 17].toInt() and 0xFF)
            val isGroup = (flags and 0x8000) != 0

            val rawName = String(nameBytes, Charsets.US_ASCII).trim()
            if (rawName.isBlank() || rawName.all { it == '\u0000' || it.isWhitespace() }) continue

            // Type 0x00 = Workstation/Computer Name, Type 0x20 = Server/File Sharing Service
            if (!isGroup) {
                if (nameType == 0x00 || nameType == 0x20) {
                    bestName = cleanNetBiosString(rawName)
                    if (nameType == 0x00) {
                        return bestName
                    }
                }
            }
        }

        return bestName
    }

    private fun cleanNetBiosString(name: String): String {
        return name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.trim()
    }
}
