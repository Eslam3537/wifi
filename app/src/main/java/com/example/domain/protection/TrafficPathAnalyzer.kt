package com.example.domain.protection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.domain.privilege.PrivilegeManager
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class PhoneNetworkRole {
    NORMAL_WIFI_CLIENT,
    WIFI_HOTSPOT,
    NETWORK_BRIDGE,
    ROUTER_GATEWAY,
    TRAFFIC_FORWARDING_DEVICE
}

data class TrafficPathReport(
    val role: PhoneNetworkRole,
    val isForwardingEnabled: Boolean,
    val canLocalFirewallBlockRemoteClients: Boolean,
    val canLocalTrafficControlShapeRemoteClients: Boolean,
    val explanation: String
)

object TrafficPathAnalyzer {

    /**
     * Analyzes whether the Android device physically forwards network traffic for other LAN hosts.
     * Prevents false assumptions that local phone iptables rules can magically block peer clients.
     */
    suspend fun analyzeTrafficPath(
        localIp: String?,
        gatewayIp: String?,
        interfaceName: String?
    ): TrafficPathReport = withContext(Dispatchers.IO) {
        val iface = interfaceName ?: "wlan0"
        val effectiveGateway = if (gatewayIp.isNullOrBlank() || gatewayIp == "0.0.0.0" || gatewayIp == "null") {
            if (localIp != null && localIp.contains(".")) {
                localIp.substringBeforeLast(".") + ".1"
            } else "192.168.1.1"
        } else gatewayIp

        // 1. Is this device the default gateway?
        if (localIp != null && localIp == effectiveGateway) {
            return@withContext TrafficPathReport(
                role = PhoneNetworkRole.ROUTER_GATEWAY,
                isForwardingEnabled = true,
                canLocalFirewallBlockRemoteClients = true,
                canLocalTrafficControlShapeRemoteClients = true,
                explanation = "This Android device is acting as the primary default gateway for the network."
            )
        }

        // 2. Is this device running Wi-Fi Hotspot / SoftAP tethering?
        val isHotspotInterface = iface.startsWith("ap") || iface.startsWith("softap") || iface.startsWith("tether") || iface.startsWith("rndis")
        if (isHotspotInterface) {
            return@withContext TrafficPathReport(
                role = PhoneNetworkRole.WIFI_HOTSPOT,
                isForwardingEnabled = true,
                canLocalFirewallBlockRemoteClients = true,
                canLocalTrafficControlShapeRemoteClients = true,
                explanation = "This Android device is running a Wi-Fi Hotspot / SoftAP. Connected peer traffic traverses this device."
            )
        }

        // 3. Check kernel IP forwarding flag (/proc/sys/net/ipv4/ip_forward)
        var isForwarding = false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            try {
                val forwardFile = File("/proc/sys/net/ipv4/ip_forward")
                if (forwardFile.exists() && forwardFile.canRead()) {
                    isForwarding = forwardFile.readText().trim() == "1"
                }
            } catch (_: Exception) {}
        }

        if (!isForwarding && PrivilegeManager.checkRootStatus() == RootStatus.ROOT_AVAILABLE) {
            val res = PrivilegeManager.executePrivilegedCommand("cat /proc/sys/net/ipv4/ip_forward")
            isForwarding = res.getOrNull()?.trim() == "1"
        }

        if (isForwarding) {
            return@withContext TrafficPathReport(
                role = PhoneNetworkRole.TRAFFIC_FORWARDING_DEVICE,
                isForwardingEnabled = true,
                canLocalFirewallBlockRemoteClients = true,
                canLocalTrafficControlShapeRemoteClients = true,
                explanation = "Linux kernel IP forwarding is enabled. Traffic traversing this device can be controlled by local iptables and tc."
            )
        }

        // Default case: Standard Wi-Fi client station
        TrafficPathReport(
            role = PhoneNetworkRole.NORMAL_WIFI_CLIENT,
            isForwardingEnabled = false,
            canLocalFirewallBlockRemoteClients = false,
            canLocalTrafficControlShapeRemoteClients = false,
            explanation = "This Android phone is a standard Wi-Fi station on the local subnet ($iface). Traffic between other LAN clients and the internet flows directly through the physical router ($effectiveGateway) without traversing this phone. Local iptables or tc can isolate direct traffic with this phone, while whole-network cutoff requires router ACL integration or Wi-Fi hotspot mode."
        )
    }
}
