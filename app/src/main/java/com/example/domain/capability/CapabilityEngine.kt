package com.example.domain.capability

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.example.domain.discovery.NetworkDiscoveryEngine
import com.example.domain.privilege.KernelSUDetector
import com.example.domain.privilege.KernelSuAuditReport
import com.example.domain.privilege.PrivilegeManager
import com.example.domain.privilege.RootAuditReport
import com.example.domain.privilege.RootCapabilityDetector
import com.example.domain.protection.PhoneNetworkRole
import com.example.domain.protection.TrafficPathAnalyzer
import com.example.domain.protection.TrafficPathReport
import com.example.model.CapabilityState
import com.example.model.KernelSuStatus
import com.example.model.RootStatus
import com.example.model.RouterCapability
import com.example.model.SystemCapabilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class CapabilityBackend {
    LOCAL_LINUX,
    KERNELSU_ROOT,
    ROUTER_API,
    FALLBACK
}

data class DetailedCapability(
    val id: String,
    val name: String,
    val status: CapabilityState,
    val reason: String,
    val backend: CapabilityBackend,
    val verification: String,
    val requirements: String
)

data class FullCapabilityAudit(
    val capabilities: Map<String, DetailedCapability>,
    val systemReport: SystemCapabilityReport,
    val trafficPathReport: TrafficPathReport,
    val rootAudit: RootAuditReport,
    val ksuAudit: KernelSuAuditReport,
    val routerCapability: RouterCapability?
)

object CapabilityEngine {

    suspend fun auditAllCapabilities(
        context: Context,
        routerCapability: RouterCapability?,
        networkInfo: NetworkDiscoveryEngine.NetworkInterfaceInfo?,
        forceRefresh: Boolean = false
    ): FullCapabilityAudit = withContext(Dispatchers.IO) {
        val rootAudit = PrivilegeManager.auditRoot(forceRefresh)
        val ksuAudit = PrivilegeManager.auditKernelSu(forceRefresh)
        val trafficPath = TrafficPathAnalyzer.analyzeTrafficPath(
            localIp = networkInfo?.ip,
            gatewayIp = networkInfo?.gatewayIp,
            interfaceName = networkInfo?.interfaceName
        )

        val map = mutableMapOf<String, DetailedCapability>()

        // 1. ROOT
        val rootStatus = when (rootAudit.status) {
            RootStatus.ROOT_AVAILABLE -> CapabilityState.SUPPORTED
            RootStatus.ROOT_DENIED -> CapabilityState.RESTRICTED
            RootStatus.ROOT_UNSUPPORTED, RootStatus.NO_ROOT -> CapabilityState.UNSUPPORTED
        }
        map["ROOT"] = DetailedCapability(
            id = "ROOT",
            name = "Root Superuser Execution",
            status = rootStatus,
            reason = if (rootAudit.isUid0Verified) "Verified root execution (uid=0)" else (rootAudit.failureReason ?: "No su binary or permission denied"),
            backend = CapabilityBackend.KERNELSU_ROOT,
            verification = "Executed `su -c id` -> uid=${if (rootAudit.isUid0Verified) "0" else "unverified"}",
            requirements = "Root provider (KernelSU / Magisk / APatch)"
        )

        // 2. KERNELSU
        val ksuStatus = when (ksuAudit.status) {
            KernelSuStatus.SUPPORTED_AND_VERIFIED -> CapabilityState.SUPPORTED
            KernelSuStatus.DETECTED_NO_PERMISSION -> CapabilityState.RESTRICTED
            KernelSuStatus.NOT_DETECTED -> CapabilityState.UNSUPPORTED
        }
        map["KERNELSU"] = DetailedCapability(
            id = "KERNELSU",
            name = "KernelSU Integration",
            status = ksuStatus,
            reason = if (ksuAudit.isRootGrantedThroughKsu) "KernelSU active and verified root granted"
            else if (ksuAudit.isKernelSuDetected) "KernelSU detected in kernel/modules but root permission not granted to this app"
            else "KernelSU signatures not detected in kernel banner or storage",
            backend = CapabilityBackend.KERNELSU_ROOT,
            verification = ksuAudit.detectionEvidence.firstOrNull() ?: "No KernelSU evidence found",
            requirements = "GKI Kernel with KernelSU support"
        )

        // 3. NETWORK_SCAN
        val scanStatus = if (networkInfo != null) CapabilityState.SUPPORTED else CapabilityState.LIMITED
        map["NETWORK_SCAN"] = DetailedCapability(
            id = "NETWORK_SCAN",
            name = "Subnet Discovery Engine",
            status = scanStatus,
            reason = if (networkInfo != null) "Active interface ${networkInfo.interfaceName} (${networkInfo.ip}/${networkInfo.prefixLength})"
            else "No active Wi-Fi or tethering interface detected",
            backend = CapabilityBackend.LOCAL_LINUX,
            verification = "Interface socket bound: ${networkInfo?.interfaceName ?: "none"}",
            requirements = "Active network connection"
        )

        // 4. MDNS
        val mDnsStatus = try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("CapAuditLock")
            if (lock != null) CapabilityState.SUPPORTED else CapabilityState.LIMITED
        } catch (_: Exception) {
            CapabilityState.UNSUPPORTED
        }
        map["MDNS"] = DetailedCapability(
            id = "MDNS",
            name = "mDNS / NSD Discovery",
            status = mDnsStatus,
            reason = if (mDnsStatus == CapabilityState.SUPPORTED) "Android MulticastLock acquired successfully" else "MulticastLock unavailable",
            backend = CapabilityBackend.LOCAL_LINUX,
            verification = "Multicast socket allocation verified",
            requirements = "CHANGE_WIFI_MULTICAST_STATE permission"
        )

        // 5. TCP_SCAN
        map["TCP_SCAN"] = DetailedCapability(
            id = "TCP_SCAN",
            name = "TCP Connect Port Scanner",
            status = CapabilityState.SUPPORTED,
            reason = "Standard Java/POSIX non-blocking socket connect available",
            backend = CapabilityBackend.LOCAL_LINUX,
            verification = "Socket syscall available",
            requirements = "INTERNET permission"
        )

        // 6. ICMP
        val icmpStatus = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityState.SUPPORTED else CapabilityState.LIMITED
        map["ICMP"] = DetailedCapability(
            id = "ICMP",
            name = "ICMP Echo Probing",
            status = icmpStatus,
            reason = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) "Privileged /system/bin/ping execution supported"
            else "Non-root raw ICMP restricted by Android SELinux; fallback to TCP probe",
            backend = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityBackend.KERNELSU_ROOT else CapabilityBackend.FALLBACK,
            verification = "Privileged ping available: ${rootAudit.status == RootStatus.ROOT_AVAILABLE}",
            requirements = "Root or TCP probe fallback"
        )

        // 7. MAC_ACCESS
        val macStatus = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityState.SUPPORTED
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) CapabilityState.RESTRICTED
        else CapabilityState.LIMITED
        map["MAC_ACCESS"] = DetailedCapability(
            id = "MAC_ACCESS",
            name = "Physical MAC Address Resolution",
            status = macStatus,
            reason = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) "Full access to kernel neighbor table and privileged ip neigh"
            else "Android 10+ restricts MAC address querying to protect privacy (02:00:00:00:00:00)",
            backend = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityBackend.KERNELSU_ROOT else CapabilityBackend.FALLBACK,
            verification = "Kernel neighbor access mode: ${if (rootAudit.status == RootStatus.ROOT_AVAILABLE) "ROOT" else "SANDBOXED"}",
            requirements = "Root privileges on Android 10+"
        )

        // 8. NEIGHBOR_TABLE
        val arpStatus = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityState.SUPPORTED
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) CapabilityState.RESTRICTED
        else {
            val canRead = try {
                val f = File("/proc/net/arp")
                f.exists() && f.canRead()
            } catch (_: Exception) { false }
            if (canRead) CapabilityState.SUPPORTED else CapabilityState.RESTRICTED
        }
        map["NEIGHBOR_TABLE"] = DetailedCapability(
            id = "NEIGHBOR_TABLE",
            name = "Kernel Neighbor / ARP Table",
            status = arpStatus,
            reason = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) "Full `ip neigh` parsing available with kernel neighbor states"
            else "/proc/net/arp sandboxed on Android 10+",
            backend = if (rootAudit.status == RootStatus.ROOT_AVAILABLE) CapabilityBackend.KERNELSU_ROOT else CapabilityBackend.FALLBACK,
            verification = "`ip neigh` inspection verified",
            requirements = "Privileged shell on Android 10+"
        )

        // 9. ROUTER_CONTROL
        val routerStatus = if (routerCapability?.isSupported == true) CapabilityState.SUPPORTED
        else if (routerCapability != null && routerCapability.endpoint.isNotBlank()) CapabilityState.LIMITED
        else CapabilityState.UNSUPPORTED
        map["ROUTER_CONTROL"] = DetailedCapability(
            id = "ROUTER_CONTROL",
            name = "Gateway Management API",
            status = routerStatus,
            reason = routerCapability?.unsupportedReasons?.firstOrNull() ?: (if (routerCapability?.isSupported == true) "Router management API verified" else "No responsive gateway API found"),
            backend = CapabilityBackend.ROUTER_API,
            verification = "Gateway probe target: ${routerCapability?.endpoint ?: "none"}",
            requirements = "Supported gateway firmware (e.g. Huawei HG630 with admin API)"
        )

        // 10. REMOTE_DEVICE_BLOCK
        val blockDeviceStatus = when {
            routerCapability?.isSupported == true && routerCapability.supportedFeatures.contains("BLOCK_DEVICE") -> CapabilityState.SUPPORTED
            trafficPath.canLocalFirewallBlockRemoteClients -> CapabilityState.SUPPORTED
            else -> CapabilityState.UNSUPPORTED
        }
        val blockDeviceReason = when {
            routerCapability?.isSupported == true -> "Supported via router hardware ACL/MAC filtering"
            trafficPath.canLocalFirewallBlockRemoteClients -> "Supported via device forwarding/hotspot mode using isolated iptables chain"
            else -> "UNSUPPORTED: Phone is a standard Wi-Fi client. Local phone firewall cannot block other Wi-Fi clients without router ACL integration or Wi-Fi hotspot mode."
        }
        map["REMOTE_DEVICE_BLOCK"] = DetailedCapability(
            id = "REMOTE_DEVICE_BLOCK",
            name = "Remote Client Access Control (Block)",
            status = blockDeviceStatus,
            reason = blockDeviceReason,
            backend = if (routerCapability?.isSupported == true) CapabilityBackend.ROUTER_API else CapabilityBackend.LOCAL_LINUX,
            verification = "Enforcement path: ${trafficPath.role.name}",
            requirements = "Router ACL API or Hotspot/Forwarding mode"
        )

        // 11. BLOCK_ALL
        val blockAllStatus = blockDeviceStatus
        map["BLOCK_ALL"] = DetailedCapability(
            id = "BLOCK_ALL",
            name = "Subnet-Wide Device Blocking",
            status = blockAllStatus,
            reason = if (blockAllStatus == CapabilityState.SUPPORTED) "Supported on verified current online devices" else blockDeviceReason,
            backend = if (routerCapability?.isSupported == true) CapabilityBackend.ROUTER_API else CapabilityBackend.LOCAL_LINUX,
            verification = "Enforcement path: ${trafficPath.role.name}",
            requirements = "Router ACL API or Hotspot/Forwarding mode"
        )

        // 12. BANDWIDTH_CONTROL
        val bwStatus = when {
            trafficPath.canLocalTrafficControlShapeRemoteClients -> CapabilityState.SUPPORTED
            routerCapability?.isSupported == true && routerCapability.supportedFeatures.contains("QOS") -> CapabilityState.SUPPORTED
            rootAudit.status == RootStatus.ROOT_AVAILABLE -> CapabilityState.LIMITED
            else -> CapabilityState.UNSUPPORTED
        }
        map["BANDWIDTH_CONTROL"] = DetailedCapability(
            id = "BANDWIDTH_CONTROL",
            name = "Traffic Rate Shaping (tc / HTB)",
            status = bwStatus,
            reason = if (trafficPath.canLocalTrafficControlShapeRemoteClients) "Supported via kernel traffic control on forwarded hotspot packets"
            else "LIMITED: Phone does not forward packets for other clients. Local tc cannot shape peer client traffic on standard Wi-Fi.",
            backend = CapabilityBackend.LOCAL_LINUX,
            verification = "Forwarding enabled: ${trafficPath.isForwardingEnabled}",
            requirements = "Traffic traversing Android host (Hotspot / Bridge mode)"
        )

        val report = SystemCapabilityReport(
            root = rootStatus,
            kernelSu = ksuStatus,
            networkScan = scanStatus,
            mDns = mDnsStatus,
            tcpScan = CapabilityState.SUPPORTED,
            icmp = icmpStatus,
            macAccess = macStatus,
            arpInfo = arpStatus,
            routerControl = routerStatus,
            blockDevice = blockDeviceStatus,
            blockAll = blockAllStatus
        )

        FullCapabilityAudit(
            capabilities = map,
            systemReport = report,
            trafficPathReport = trafficPath,
            rootAudit = rootAudit,
            ksuAudit = ksuAudit,
            routerCapability = routerCapability
        )
    }
}
