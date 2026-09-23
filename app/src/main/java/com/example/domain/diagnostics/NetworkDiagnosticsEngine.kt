package com.example.domain.diagnostics

import com.example.domain.discovery.NetworkUtils
import com.example.model.DiagnosticStatus
import com.example.model.NetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class NetworkDiagnosticsEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    suspend fun runFullDiagnostics(targetIp: String = "8.8.8.8"): NetworkDiagnostics = withContext(Dispatchers.IO) {
        val pingResult = measurePingAndJitter(targetIp, count = 5)
        val dnsLatency = measureDnsLatency("google.com")
        val speedResult = measureDownloadSpeed()

        val status = when {
            pingResult.received == 0 && speedResult == null -> DiagnosticStatus.TIMED_OUT
            pingResult.received == 0 -> DiagnosticStatus.BLOCKED
            else -> DiagnosticStatus.MEASURED
        }

        NetworkDiagnostics(
            targetIp = targetIp,
            pingMs = pingResult.avgLatency,
            minLatencyMs = pingResult.minLatency,
            maxLatencyMs = pingResult.maxLatency,
            jitterMs = pingResult.jitter,
            packetLossPercent = pingResult.packetLoss,
            dnsLatencyMs = dnsLatency,
            downloadSpeedMbps = speedResult,
            uploadSpeedMbps = null, // Upload requires dedicated server endpoint
            status = status,
            statusMessage = if (status == DiagnosticStatus.MEASURED) "Successfully measured live metrics" else "Target timed out or ICMP filtered"
        )
    }

    data class PingMeasurement(
        val sent: Int,
        val received: Int,
        val avgLatency: Double?,
        val minLatency: Double?,
        val maxLatency: Double?,
        val jitter: Double?,
        val packetLoss: Double
    )

    fun measurePingAndJitter(targetIp: String, count: Int = 5): PingMeasurement {
        val latencies = mutableListOf<Double>()
        var received = 0

        val probePort = when (targetIp) {
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1" -> 53
            else -> 80
        }

        for (i in 0 until count) {
            val start = System.nanoTime()
            var reached = false
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(targetIp, probePort), 800)
                socket.close()
                reached = true
            } catch (e: Exception) {
                // Secondary fallback attempt on port 443
                try {
                    val s2 = Socket()
                    s2.connect(InetSocketAddress(targetIp, if (probePort == 53) 80 else 443), 600)
                    s2.close()
                    reached = true
                } catch (_: Exception) {
                    reached = false
                }
            }

            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            if (reached) {
                received++
                latencies.add(elapsedMs)
            }
        }

        val loss = NetworkUtils.calculatePacketLoss(count, received)
        val jitter = NetworkUtils.calculateJitter(latencies)
        val avg = if (latencies.isNotEmpty()) latencies.average() else null
        val min = latencies.minOrNull()
        val max = latencies.maxOrNull()

        return PingMeasurement(
            sent = count,
            received = received,
            avgLatency = avg?.let { kotlin.math.round(it * 10.0) / 10.0 },
            minLatency = min?.let { kotlin.math.round(it * 10.0) / 10.0 },
            maxLatency = max?.let { kotlin.math.round(it * 10.0) / 10.0 },
            jitter = jitter,
            packetLoss = loss
        )
    }

    fun measureDnsLatency(host: String = "google.com"): Long? {
        return try {
            val start = System.currentTimeMillis()
            val addrs = InetAddress.getAllByName(host)
            if (addrs.isNotEmpty()) {
                System.currentTimeMillis() - start
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun measureDownloadSpeed(): Double? {
        return try {
            // Fetch 1MB test file from Cloudflare Speed test CDN
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=1000000")
                .build()

            val start = System.nanoTime()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bytes = response.body?.bytes() ?: return null
            val durationSec = (System.nanoTime() - start) / 1_000_000_000.0
            if (durationSec <= 0.0) return null

            val bits = bytes.size * 8.0
            val mbps = (bits / durationSec) / 1_000_000.0
            kotlin.math.round(mbps * 100.0) / 100.0
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Re-discovers gateway state and validates against known MAC identity.
     */
    suspend fun auditGatewayIntegrity(
        gatewayIp: String,
        expectedMac: String?,
        arpAnalyzer: com.example.domain.security.ArpSecurityAnalyzer,
        iface: String = "wlan0"
    ): com.example.model.ArpIntegrityReport = withContext(Dispatchers.IO) {
        // Trigger probe to populate neighbor entry for gateway safely
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(gatewayIp, 80), 300)
            socket.close()
        } catch (_: Exception) {}

        arpAnalyzer.auditArpCache(gatewayIp, expectedMac, iface)
    }
}
