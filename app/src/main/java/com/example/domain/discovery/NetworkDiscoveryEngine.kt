package com.example.domain.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import com.example.domain.privilege.PrivilegeManager
import com.example.model.ConfidenceLevel
import com.example.model.DeviceStatus
import com.example.model.DeviceType
import com.example.model.DiscoveredDevice
import com.example.model.DiscoveryMethod
import com.example.model.IdentitySource
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class NetworkDiscoveryEngine(private val context: Context) {

    private val mdnsHelper by lazy { MdnsDiscoveryHelper(context) }

    private val connectivityManager by lazy {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    data class NetworkInterfaceInfo(
        val ip: String,
        val prefixLength: Int,
        val gatewayIp: String,
        val interfaceName: String,
        val dnsServers: List<String>
    )

    fun getActiveInterfaceInfo(): NetworkInterfaceInfo? {
        try {
            val cm = connectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val linkProperties: LinkProperties = cm.getLinkProperties(activeNetwork) ?: return null

            var currentIp: String? = null
            var prefixLength = 24

            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    currentIp = address.hostAddress
                    prefixLength = linkAddress.prefixLength
                    break
                }
            }

            if (currentIp == null) {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (netInt in interfaces) {
                    if (netInt.isUp && !netInt.isLoopback) {
                        for (addr in netInt.inetAddresses) {
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                currentIp = addr.hostAddress
                                break
                            }
                        }
                    }
                    if (currentIp != null) break
                }
            }

            if (currentIp == null) return null

            var candidateGateway: String? = null
            for (route in linkProperties.routes) {
                val gateway = route.gateway
                if (gateway is java.net.Inet4Address && !gateway.isLoopbackAddress) {
                    val host = gateway.hostAddress
                    if (!host.isNullOrBlank() && host != "0.0.0.0") {
                        candidateGateway = host
                        if (route.isDefaultRoute) break
                    }
                }
            }

            // Fallback 1: WifiManager.dhcpInfo
            if (candidateGateway == null) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val dhcp = wm?.dhcpInfo
                    if (dhcp != null && dhcp.gateway != 0) {
                        val g = dhcp.gateway
                        val dhcpGw = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
                        if (dhcpGw != "0.0.0.0" && NetworkUtils.isValidIpv4(dhcpGw)) {
                            candidateGateway = dhcpGw
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback 2: ip route show
            if (candidateGateway == null) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip route show default || ip route show"))
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line?.trim() ?: continue
                        if (text.contains("default via ")) {
                            val gw = text.substringAfter("default via ").substringBefore(" ").trim()
                            if (NetworkUtils.isValidIpv4(gw) && gw != "0.0.0.0") {
                                candidateGateway = gw
                                break
                            }
                        }
                    }
                    proc.destroy()
                } catch (_: Exception) {}
            }

            // Fallback 3: Subnet default host (.1) based on current IP
            val gatewayIp = candidateGateway ?: run {
                val lastDot = currentIp.lastIndexOf('.')
                if (lastDot != -1) "${currentIp.substring(0, lastDot)}.1" else "192.168.1.1"
            }

            val dnsServers = linkProperties.dnsServers.mapNotNull { it.hostAddress }

            return NetworkInterfaceInfo(
                ip = currentIp,
                prefixLength = prefixLength,
                gatewayIp = gatewayIp,
                interfaceName = linkProperties.interfaceName ?: "wlan0",
                dnsServers = dnsServers
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Executes real multi-source discovery.
     * Guaranteed: Emits ONLY currently verified online devices.
     * Stale ARP records and historical DB records NEVER contaminate this flow.
     */
    fun startLayeredDiscovery(): Flow<List<DiscoveredDevice>> = flow {
        val activeInfo = getActiveInterfaceInfo()
        if (activeInfo == null) {
            emit(emptyList())
            return@flow
        }

        val evidenceMap = mutableMapOf<String, MutableList<DeviceEvidence>>()
        val discoveredMap = mutableMapOf<String, DiscoveredDevice>()

        fun addEvidence(evidence: DeviceEvidence) {
            evidenceMap.getOrPut(evidence.ip) { mutableListOf() }.add(evidence)
        }

        // Layer 1: Self & Gateway Resolution (Strong Evidence)
        addEvidence(
            DeviceEvidence(
                ip = activeInfo.ip,
                source = DiscoveryMethod.CURRENT_INTERFACE,
                strength = EvidenceStrength.STRONG,
                responseStatus = "LOCAL_INTERFACE",
                details = "Device own bound IP address"
            )
        )

        val currentIdentity = DeviceIdentityResolver.resolveDeviceIdentity(
            ip = activeInfo.ip,
            mac = null,
            isGateway = false,
            isCurrentDevice = true,
            openPorts = emptyList()
        )

        val currentDevice = DiscoveredDevice(
            ip = activeInfo.ip,
            mac = null,
            vendor = currentIdentity.resolvedVendor,
            hostname = currentIdentity.primaryName,
            deviceType = currentIdentity.inferredType,
            openPorts = emptyList(),
            discoveryMethods = setOf(DiscoveryMethod.CURRENT_INTERFACE),
            latencyMs = 0L,
            isGateway = false,
            isCurrentDevice = true,
            isMacRandomized = false,
            nameSource = currentIdentity.nameSource,
            nameConfidence = currentIdentity.nameConfidence,
            vendorSource = currentIdentity.vendorSource,
            vendorConfidence = currentIdentity.vendorConfidence,
            modelName = currentIdentity.modelName,
            status = DeviceStatus.ONLINE
        )
        discoveredMap[activeInfo.ip] = currentDevice

        addEvidence(
            DeviceEvidence(
                ip = activeInfo.gatewayIp,
                source = DiscoveryMethod.CURRENT_INTERFACE,
                strength = EvidenceStrength.STRONG,
                responseStatus = "ROUTER_GATEWAY",
                details = "Default IP Gateway route"
            )
        )

        val gatewayIdentity = DeviceIdentityResolver.resolveDeviceIdentity(
            ip = activeInfo.gatewayIp,
            mac = null,
            isGateway = true,
            isCurrentDevice = false,
            openPorts = emptyList()
        )

        val gatewayDevice = DiscoveredDevice(
            ip = activeInfo.gatewayIp,
            mac = null,
            vendor = gatewayIdentity.resolvedVendor,
            hostname = gatewayIdentity.primaryName,
            deviceType = DeviceType.ROUTER,
            openPorts = emptyList(),
            discoveryMethods = setOf(DiscoveryMethod.CURRENT_INTERFACE),
            latencyMs = 1L,
            isGateway = true,
            isCurrentDevice = false,
            isMacRandomized = gatewayIdentity.isRandomizedMac,
            nameSource = gatewayIdentity.nameSource,
            nameConfidence = gatewayIdentity.nameConfidence,
            vendorSource = gatewayIdentity.vendorSource,
            vendorConfidence = gatewayIdentity.vendorConfidence,
            status = DeviceStatus.ONLINE
        )
        discoveredMap[activeInfo.gatewayIp] = gatewayDevice
        emit(getVerifiedOnlineDevices(discoveredMap, evidenceMap))

        // Layer 2: Subnet generation
        val subnetIps = NetworkUtils.calculateSubnetRange(activeInfo.ip, activeInfo.prefixLength)

        // Layer 3: Concurrent SSDP & mDNS active discovery
        val (mdnsList, ssdpList) = coroutineScope {
            val mdnsDeferred = async { runCatching { mdnsHelper.discoverAllMdnsServices(1800) }.getOrDefault(emptyList()) }
            val ssdpDeferred = async { runCatching { SsdpDiscoveryHelper.discoverSsdpDevices(1200) }.getOrDefault(emptyList()) }
            Pair(mdnsDeferred.await(), ssdpDeferred.await())
        }

        val mdnsMap = mdnsList.associateBy { it.ip }
        val ssdpMap = ssdpList.associateBy { it.ip }

        for (ssdp in ssdpList) {
            addEvidence(
                DeviceEvidence(
                    ip = ssdp.ip,
                    source = DiscoveryMethod.SSDP_UPNP,
                    strength = EvidenceStrength.STRONG,
                    responseStatus = "SSDP_RESPONSE",
                    details = "UPnP service: ${ssdp.friendlyName ?: ssdp.modelName ?: "Device"}"
                )
            )

            val existing = discoveredMap[ssdp.ip]
            val identity = DeviceIdentityResolver.resolveDeviceIdentity(
                ip = ssdp.ip,
                mac = existing?.mac,
                isGateway = (ssdp.ip == activeInfo.gatewayIp),
                isCurrentDevice = (ssdp.ip == activeInfo.ip),
                openPorts = existing?.openPorts ?: emptyList(),
                cachedSsdpInfo = ssdp
            )
            discoveredMap[ssdp.ip] = (existing ?: DiscoveredDevice(ip = ssdp.ip)).copy(
                hostname = identity.primaryName ?: existing?.hostname,
                vendor = identity.resolvedVendor ?: existing?.vendor,
                deviceType = identity.inferredType,
                ssdpFriendlyName = identity.ssdpName,
                modelName = identity.modelName,
                nameSource = identity.nameSource,
                nameConfidence = identity.nameConfidence,
                vendorSource = identity.vendorSource,
                vendorConfidence = identity.vendorConfidence,
                discoveryMethods = (existing?.discoveryMethods ?: emptySet()) + DiscoveryMethod.SSDP_UPNP,
                status = DeviceStatus.ONLINE
            )
        }

        for (m in mdnsList) {
            addEvidence(
                DeviceEvidence(
                    ip = m.ip,
                    source = DiscoveryMethod.MDNS_NSD,
                    strength = EvidenceStrength.STRONG,
                    responseStatus = "MDNS_RESPONSE",
                    details = "Service: ${m.serviceName ?: m.serviceType}"
                )
            )

            val existing = discoveredMap[m.ip]
            val identity = DeviceIdentityResolver.resolveDeviceIdentity(
                ip = m.ip,
                mac = existing?.mac,
                isGateway = (m.ip == activeInfo.gatewayIp),
                isCurrentDevice = (m.ip == activeInfo.ip),
                openPorts = existing?.openPorts ?: emptyList(),
                cachedMdnsInfo = m,
                cachedSsdpInfo = ssdpMap[m.ip]
            )
            discoveredMap[m.ip] = (existing ?: DiscoveredDevice(ip = m.ip)).copy(
                hostname = identity.primaryName ?: existing?.hostname,
                vendor = identity.resolvedVendor ?: existing?.vendor,
                deviceType = identity.inferredType,
                mdnsName = identity.mdnsName,
                modelName = identity.modelName ?: existing?.modelName,
                nameSource = identity.nameSource,
                nameConfidence = identity.nameConfidence,
                vendorSource = identity.vendorSource,
                vendorConfidence = identity.vendorConfidence,
                discoveryMethods = (existing?.discoveryMethods ?: emptySet()) + DiscoveryMethod.MDNS_NSD,
                status = DeviceStatus.ONLINE
            )
        }
        emit(getVerifiedOnlineDevices(discoveredMap, evidenceMap))

        // Layer 4: Concurrent Active TCP & ICMP probes across candidate IPs
        val semaphore = Semaphore(25)
        val candidateIps = subnetIps.filter { it != activeInfo.ip }

        coroutineScope {
            val deferreds = candidateIps.map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        probeIp(
                            ip = ip,
                            isGateway = (ip == activeInfo.gatewayIp),
                            cachedMdns = mdnsMap[ip],
                            cachedSsdp = ssdpMap[ip],
                            onEvidence = { ev -> addEvidence(ev) }
                        )
                    }
                }
            }

            val chunkSize = 25
            val chunks = deferreds.chunked(chunkSize)
            for (chunk in chunks) {
                val results = chunk.awaitAll().filterNotNull()
                for (res in results) {
                    val existing = discoveredMap[res.ip]
                    if (existing != null) {
                        val mergedPorts = (existing.openPorts + res.openPorts).distinct().sorted()
                        val mergedMethods = existing.discoveryMethods + res.discoveryMethods
                        val vendor = if (!res.vendor.isNullOrBlank() && res.vendor != "Unknown manufacturer") res.vendor else existing.vendor
                        val hostname = if (!res.hostname.isNullOrBlank()) res.hostname else existing.hostname

                        discoveredMap[res.ip] = existing.copy(
                            openPorts = mergedPorts,
                            discoveryMethods = mergedMethods,
                            latencyMs = res.latencyMs ?: existing.latencyMs,
                            vendor = vendor,
                            hostname = hostname,
                            deviceType = if (res.deviceType != DeviceType.UNKNOWN) res.deviceType else existing.deviceType,
                            nameSource = if (res.nameSource != IdentitySource.UNKNOWN) res.nameSource else existing.nameSource,
                            nameConfidence = if (res.nameConfidence.ordinal < existing.nameConfidence.ordinal) res.nameConfidence else existing.nameConfidence,
                            vendorSource = if (res.vendorSource != IdentitySource.UNKNOWN) res.vendorSource else existing.vendorSource,
                            vendorConfidence = if (res.vendorConfidence.ordinal < existing.vendorConfidence.ordinal) res.vendorConfidence else existing.vendorConfidence,
                            mdnsName = res.mdnsName ?: existing.mdnsName,
                            netbiosName = res.netbiosName ?: existing.netbiosName,
                            ssdpFriendlyName = res.ssdpFriendlyName ?: existing.ssdpFriendlyName,
                            modelName = res.modelName ?: existing.modelName,
                            rawDnsHostname = res.rawDnsHostname ?: existing.rawDnsHostname,
                            status = DeviceStatus.ONLINE
                        )
                    } else {
                        discoveredMap[res.ip] = res
                    }
                }
                emit(getVerifiedOnlineDevices(discoveredMap, evidenceMap))
            }
        }

        // Layer 5: Kernel Neighbor Table Parsing with Active Device Reconciliation
        val neighborEntries = readNeighborTableWithStates()
        for (entry in neighborEntries) {
            // Ignore incomplete, failed, or non-MAC entries
            if (entry.mac == null || entry.mac == "00:00:00:00:00:00" || entry.state == NeighborState.FAILED || entry.state == NeighborState.INCOMPLETE) {
                continue
            }
            // Ensure entry belongs to our current Wi-Fi subnet or matches the gateway
            if (!subnetIps.contains(entry.ip) && entry.ip != activeInfo.gatewayIp) {
                continue
            }

            val existing = discoveredMap[entry.ip]
            val macRes = ManufacturerResolver.resolveVendorFromMac(entry.mac)
            val cleanVendor = macRes.normalizedVendor
            val updatedVendor = when {
                !cleanVendor.isNullOrBlank() && !macRes.isRandomizedMac -> cleanVendor
                !existing?.vendor.isNullOrBlank() && existing?.vendor != "Unknown manufacturer" -> existing.vendor
                macRes.isRandomizedMac -> "Randomized (Privacy)"
                else -> cleanVendor ?: existing?.vendor
            }

            if (existing != null) {
                // Device is already in map: enrich hardware MAC and manufacturer
                val updatedMac = if (existing.mac == null || existing.mac == "Unavailable") entry.mac else existing.mac
                discoveredMap[entry.ip] = existing.copy(
                    mac = updatedMac,
                    vendor = updatedVendor,
                    isMacRandomized = macRes.isRandomizedMac,
                    vendorSource = if (!macRes.isRandomizedMac && cleanVendor != null) IdentitySource.MAC_OUI else existing.vendorSource,
                    vendorConfidence = if (!macRes.isRandomizedMac && cleanVendor != null) ConfidenceLevel.HIGH else existing.vendorConfidence,
                    discoveryMethods = existing.discoveryMethods + DiscoveryMethod.NEIGHBOR_TABLE
                )
                addEvidence(
                    DeviceEvidence(
                        ip = entry.ip,
                        mac = entry.mac,
                        source = DiscoveryMethod.NEIGHBOR_TABLE,
                        strength = if (entry.isFreshActive) EvidenceStrength.STRONG else EvidenceStrength.MEDIUM,
                        responseStatus = entry.state.name,
                        details = "Kernel neighbor state: ${entry.state.name} on ${entry.interfaceName ?: "wlan"}"
                    )
                )
            } else {
                // Unseen neighbor entry: verify active presence
                val isActive = verifyNeighborActive(entry.ip)
                val evStrength = if (entry.isFreshActive || isActive) EvidenceStrength.STRONG else EvidenceStrength.MEDIUM
                addEvidence(
                    DeviceEvidence(
                        ip = entry.ip,
                        mac = entry.mac,
                        source = DiscoveryMethod.NEIGHBOR_TABLE,
                        strength = evStrength,
                        responseStatus = if (isActive) "CONFIRMED_ONLINE" else entry.state.name,
                        details = "Kernel neighbor state: ${entry.state.name} on ${entry.interfaceName ?: "wlan"}"
                    )
                )

                val resolvedHostname = runCatching {
                    val addr = InetAddress.getByName(entry.ip)
                    val host = addr.canonicalHostName
                    if (host != entry.ip) host else null
                }.getOrNull()

                val isGw = (entry.ip == activeInfo.gatewayIp)
                val inferredType = DeviceFingerprintHelper.inferDeviceType(
                    isGw,
                    false,
                    resolvedHostname,
                    emptyList(),
                    updatedVendor ?: ""
                )

                discoveredMap[entry.ip] = DiscoveredDevice(
                    ip = entry.ip,
                    mac = entry.mac,
                    vendor = updatedVendor,
                    hostname = resolvedHostname,
                    deviceType = inferredType,
                    discoveryMethods = setOf(DiscoveryMethod.NEIGHBOR_TABLE),
                    isGateway = isGw,
                    isMacRandomized = macRes.isRandomizedMac,
                    vendorSource = if (cleanVendor != null) IdentitySource.MAC_OUI else IdentitySource.UNKNOWN,
                    vendorConfidence = if (cleanVendor != null) ConfidenceLevel.HIGH else ConfidenceLevel.NONE,
                    status = DeviceStatus.ONLINE
                )
            }
        }

        emit(getVerifiedOnlineDevices(discoveredMap, evidenceMap))

    }.flowOn(Dispatchers.IO)

    private fun getVerifiedOnlineDevices(
        deviceMap: Map<String, DiscoveredDevice>,
        evidenceMap: Map<String, List<DeviceEvidence>>
    ): List<DiscoveredDevice> {
        val verified = mutableListOf<DiscoveredDevice>()
        for ((ip, device) in deviceMap) {
            val evidences = evidenceMap[ip] ?: emptyList()
            val decision = DevicePresenceEngine.evaluatePresence(ip, evidences)
            if (decision.status == DeviceStatus.ONLINE) {
                verified.add(device.copy(status = DeviceStatus.ONLINE))
            }
        }

        return verified.sortedWith(
            compareByDescending<DiscoveredDevice> { it.isGateway }
                .thenByDescending { it.isCurrentDevice }
                .thenBy { NetworkUtils.ipToInt(it.ip) }
        )
    }

    private suspend fun verifyNeighborActive(ip: String): Boolean = withContext(Dispatchers.IO) {
        // Fast ICMP ping check
        try {
            if (InetAddress.getByName(ip).isReachable(150)) {
                return@withContext true
            }
        } catch (_: Exception) {}

        // Quick TCP probe across most common LAN ports
        val ports = listOf(80, 443, 8080, 53, 22, 445, 139, 8008, 5000, 62078)
        for (port in ports) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, port), 120)
                socket.close()
                return@withContext true
            } catch (_: ConnectException) {
                try { socket.close() } catch (_: Exception) {}
                return@withContext true
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
        false
    }

    private suspend fun probeIp(
        ip: String,
        isGateway: Boolean,
        cachedMdns: MdnsDiscoveryHelper.MdnsResolvedInfo?,
        cachedSsdp: SsdpDiscoveryHelper.SsdpDeviceMetadata?,
        onEvidence: (DeviceEvidence) -> Unit
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        var isOnline = false
        val openPorts = mutableListOf<Int>()
        var measuredLatency: Long? = null
        val methods = mutableSetOf<DiscoveryMethod>()

        // 1. Fast ICMP Ping check
        val pingStart = System.currentTimeMillis()
        var pingSuccess = false
        try {
            val addr = InetAddress.getByName(ip)
            if (addr.isReachable(180)) {
                pingSuccess = true
            }
        } catch (_: Exception) {}

        if (pingSuccess) {
            isOnline = true
            measuredLatency = (System.currentTimeMillis() - pingStart).coerceAtLeast(1L)
            methods.add(DiscoveryMethod.ICMP_PING)
            onEvidence(
                DeviceEvidence(
                    ip = ip,
                    source = DiscoveryMethod.ICMP_PING,
                    strength = EvidenceStrength.STRONG,
                    responseStatus = "PING_REPLY",
                    details = "ICMP echo reply in ${measuredLatency}ms"
                )
            )
        }

        // 2. TCP connect probes on standard LAN service ports
        val probePorts = if (isGateway) listOf(80, 443, 8080, 53) else listOf(80, 443, 8080, 22, 445, 139, 8008, 5000, 9100, 53, 3000, 62078)
        for (port in probePorts) {
            val tStart = System.currentTimeMillis()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, port), 160)
                socket.close()
                isOnline = true
                openPorts.add(port)
                methods.add(DiscoveryMethod.TCP_CONNECT)
                if (measuredLatency == null) {
                    measuredLatency = System.currentTimeMillis() - tStart
                }
                onEvidence(
                    DeviceEvidence(
                        ip = ip,
                        source = DiscoveryMethod.TCP_CONNECT,
                        strength = EvidenceStrength.STRONG,
                        responseStatus = "PORT_OPEN",
                        details = "TCP Port $port connection succeeded"
                    )
                )
            } catch (_: ConnectException) {
                // Connection refused = Host actively rejected port with TCP RST, proving it is alive
                try { socket.close() } catch (_: Exception) {}
                isOnline = true
                methods.add(DiscoveryMethod.TCP_CONNECT)
                if (measuredLatency == null) {
                    measuredLatency = (System.currentTimeMillis() - tStart).coerceAtLeast(1L)
                }
                onEvidence(
                    DeviceEvidence(
                        ip = ip,
                        source = DiscoveryMethod.TCP_CONNECT,
                        strength = EvidenceStrength.STRONG,
                        responseStatus = "PORT_RST_ACTIVE",
                        details = "Target actively refused port $port (Host confirmed online)"
                    )
                )
                break
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        // 3. Lightweight UDP trigger to stimulate kernel ARP resolution
        try {
            val udpSocket = DatagramSocket()
            udpSocket.soTimeout = 50
            val buf = byteArrayOf(0x00, 0x00, 0x00, 0x00)
            val packet = DatagramPacket(buf, buf.size, InetAddress.getByName(ip), 137)
            udpSocket.send(packet)
            udpSocket.close()
        } catch (_: Exception) {}

        if (!isOnline && cachedMdns == null && cachedSsdp == null) {
            return@withContext null
        }

        // 4. Multi-source Identity Resolution
        val identity = DeviceIdentityResolver.resolveDeviceIdentity(
            ip = ip,
            mac = null,
            isGateway = isGateway,
            isCurrentDevice = false,
            openPorts = openPorts,
            cachedMdnsInfo = cachedMdns,
            cachedSsdpInfo = cachedSsdp
        )

        if (identity.netbiosName != null) {
            methods.add(DiscoveryMethod.NETBIOS)
        }
        if (identity.rawDnsHostname != null) {
            methods.add(DiscoveryMethod.REVERSE_DNS)
        }

        DiscoveredDevice(
            ip = ip,
            mac = null,
            vendor = identity.resolvedVendor,
            hostname = identity.primaryName,
            deviceType = identity.inferredType,
            openPorts = openPorts,
            discoveryMethods = methods,
            latencyMs = measuredLatency,
            isGateway = isGateway,
            isMacRandomized = identity.isRandomizedMac,
            nameSource = identity.nameSource,
            nameConfidence = identity.nameConfidence,
            vendorSource = identity.vendorSource,
            vendorConfidence = identity.vendorConfidence,
            mdnsName = identity.mdnsName,
            netbiosName = identity.netbiosName,
            ssdpFriendlyName = identity.ssdpName,
            modelName = identity.modelName,
            rawDnsHostname = identity.rawDnsHostname,
            status = DeviceStatus.ONLINE
        )
    }

    private suspend fun readNeighborTableWithStates(): List<NeighborEntry> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus == RootStatus.ROOT_AVAILABLE) {
            val cmdRes = PrivilegeManager.executePrivilegedCommand("ip neigh show")
            val output = cmdRes.getOrNull()
            if (!output.isNullOrBlank()) {
                val parsed = NeighborTableParser.parseIpNeighOutput(output)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
            // Secondary root fallback: cat /proc/net/arp with root UID 0
            val arpRes = PrivilegeManager.executePrivilegedCommand("cat /proc/net/arp")
            val arpOutput = arpRes.getOrNull()
            if (!arpOutput.isNullOrBlank()) {
                val parsedArp = NeighborTableParser.parseProcNetArp(arpOutput.lines())
                if (parsedArp.isNotEmpty()) return@withContext parsedArp
            }
        }

        // Unprivileged fallback (Android 9 or below where /proc/net/arp is readable)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            try {
                val file = File("/proc/net/arp")
                if (file.exists() && file.canRead()) {
                    return@withContext NeighborTableParser.parseProcNetArp(file.readLines())
                }
            } catch (_: Exception) {}
        }

        emptyList()
    }
}
