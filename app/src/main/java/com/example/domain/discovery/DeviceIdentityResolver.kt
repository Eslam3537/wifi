package com.example.domain.discovery

import com.example.model.ConfidenceLevel
import com.example.model.DeviceType
import com.example.model.DiscoveredDevice
import com.example.model.IdentitySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress

object DeviceIdentityResolver {

    data class ResolvedIdentity(
        val primaryName: String?,
        val nameSource: IdentitySource,
        val nameConfidence: ConfidenceLevel,
        val resolvedVendor: String?,
        val vendorSource: IdentitySource,
        val vendorConfidence: ConfidenceLevel,
        val isRandomizedMac: Boolean,
        val inferredType: DeviceType,
        val typeConfidence: ConfidenceLevel,
        val mdnsName: String? = null,
        val ssdpName: String? = null,
        val netbiosName: String? = null,
        val modelName: String? = null,
        val rawDnsHostname: String? = null
    )

    /**
     * Resolves complete device identity by querying and consolidating all independent sources.
     */
    suspend fun resolveDeviceIdentity(
        ip: String,
        mac: String?,
        isGateway: Boolean,
        isCurrentDevice: Boolean,
        openPorts: List<Int>,
        cachedMdnsInfo: MdnsDiscoveryHelper.MdnsResolvedInfo? = null,
        cachedSsdpInfo: SsdpDiscoveryHelper.SsdpDeviceMetadata? = null,
        rawReverseDns: String? = null
    ): ResolvedIdentity = withContext(Dispatchers.IO) {
        if (isCurrentDevice) {
            val localModel = android.os.Build.MODEL
            val localManufacturer = android.os.Build.MANUFACTURER
            return@withContext ResolvedIdentity(
                primaryName = localModel,
                nameSource = IdentitySource.DHCP,
                nameConfidence = ConfidenceLevel.HIGH,
                resolvedVendor = ManufacturerResolver.normalizeVendor(localManufacturer) ?: localManufacturer,
                vendorSource = IdentitySource.DHCP,
                vendorConfidence = ConfidenceLevel.HIGH,
                isRandomizedMac = false,
                inferredType = DeviceType.PHONE,
                typeConfidence = ConfidenceLevel.HIGH,
                modelName = localModel
            )
        }

        if (isGateway) {
            val macResolution = ManufacturerResolver.resolveVendorFromMac(mac)
            val gwVendor = macResolution.normalizedVendor ?: "Router"
            return@withContext ResolvedIdentity(
                primaryName = "Default Gateway",
                nameSource = IdentitySource.SYSTEM_NEIGHBOR,
                nameConfidence = ConfidenceLevel.HIGH,
                resolvedVendor = gwVendor,
                vendorSource = if (macResolution.vendor != null) IdentitySource.MAC_OUI else IdentitySource.FALLBACK,
                vendorConfidence = if (macResolution.vendor != null) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                isRandomizedMac = macResolution.isRandomizedMac,
                inferredType = DeviceType.ROUTER,
                typeConfidence = ConfidenceLevel.HIGH
            )
        }

        // Parallel probe execution across independent sources with short timeouts
        val (ssdpResult, netbiosResult, httpResult, dnsHost) = coroutineScope {
            val ssdpDeferred = async {
                cachedSsdpInfo ?: SsdpDiscoveryHelper.probeDeviceSsdp(ip)
            }
            val netbiosDeferred = async {
                NetBiosResolver.queryNetBiosName(ip, timeoutMs = 350)
            }
            val httpDeferred = async {
                if (openPorts.any { it in listOf(80, 8080, 443, 8008, 5000) }) {
                    HttpBannerResolver.probeHttpIdentity(ip, openPorts)
                } else null
            }
            val dnsDeferred = async {
                if (!rawReverseDns.isNullOrBlank()) rawReverseDns
                else runCatching {
                    val canonical = InetAddress.getByName(ip).canonicalHostName
                    if (canonical != ip && !canonical.isNullOrBlank()) canonical else null
                }.getOrNull()
            }

            Quadruple(
                ssdpDeferred.await(),
                netbiosDeferred.await(),
                httpDeferred.await(),
                dnsDeferred.await()
            )
        }

        // 1. MAC & Vendor resolution
        val macResolution = ManufacturerResolver.resolveVendorFromMac(mac)

        // 2. Multi-source Name Resolution (Hierarchical precedence)
        val mdnsName = cleanCandidateName(cachedMdnsInfo?.serviceName)
        val ssdpFriendlyName = cleanCandidateName(ssdpResult?.friendlyName)
        val netbiosName = cleanCandidateName(netbiosResult)
        val httpTitle = cleanCandidateName(httpResult?.title)
        val reverseDns = cleanCandidateName(dnsHost)

        var primaryName: String? = null
        var nameSource = IdentitySource.UNKNOWN
        var nameConfidence = ConfidenceLevel.NONE

        if (!ssdpFriendlyName.isNullOrBlank()) {
            primaryName = ssdpFriendlyName
            nameSource = IdentitySource.SSDP_UPNP
            nameConfidence = ConfidenceLevel.HIGH
        } else if (!mdnsName.isNullOrBlank()) {
            primaryName = mdnsName
            nameSource = IdentitySource.MDNS_NSD
            nameConfidence = ConfidenceLevel.HIGH
        } else if (!netbiosName.isNullOrBlank()) {
            primaryName = netbiosName
            nameSource = IdentitySource.NETBIOS
            nameConfidence = ConfidenceLevel.HIGH
        } else if (!httpTitle.isNullOrBlank()) {
            primaryName = httpTitle
            nameSource = IdentitySource.HTTP_BANNER
            nameConfidence = ConfidenceLevel.MEDIUM
        } else if (!reverseDns.isNullOrBlank() && isReliableHostname(reverseDns)) {
            primaryName = reverseDns
            nameSource = IdentitySource.REVERSE_DNS
            nameConfidence = ConfidenceLevel.LOW
        }

        // 3. Multi-source Manufacturer Resolution
        var resolvedVendor: String? = null
        var vendorSource = IdentitySource.UNKNOWN
        var vendorConfidence = ConfidenceLevel.NONE

        val ssdpVendor = ManufacturerResolver.normalizeVendor(ssdpResult?.manufacturer)
        val httpVendor = extractVendorFromHttp(httpResult?.serverHeader, httpResult?.title)

        if (!ssdpVendor.isNullOrBlank()) {
            resolvedVendor = ssdpVendor
            vendorSource = IdentitySource.SSDP_UPNP
            vendorConfidence = ConfidenceLevel.HIGH
        } else if (macResolution.vendor != null && !macResolution.isRandomizedMac) {
            resolvedVendor = macResolution.normalizedVendor
            vendorSource = IdentitySource.MAC_OUI
            vendorConfidence = ConfidenceLevel.HIGH
        } else if (!httpVendor.isNullOrBlank()) {
            resolvedVendor = httpVendor
            vendorSource = IdentitySource.HTTP_BANNER
            vendorConfidence = ConfidenceLevel.MEDIUM
        } else if (macResolution.isRandomizedMac) {
            resolvedVendor = "Randomized (Privacy)"
            vendorSource = IdentitySource.MAC_OUI
            vendorConfidence = ConfidenceLevel.LOW
        } else {
            // Check textual clues in hostname or mDNS service
            val inferredFromHost = inferVendorFromHostname(primaryName ?: mdnsName ?: reverseDns)
            if (inferredFromHost != null) {
                resolvedVendor = inferredFromHost
                vendorSource = IdentitySource.MDNS_NSD
                vendorConfidence = ConfidenceLevel.LOW
            }
        }

        // 4. Model Extraction
        val resolvedModel = ssdpResult?.modelName ?: ssdpResult?.modelNumber ?: cachedMdnsInfo?.attributes?.get("model")

        // 5. Device Type Inference
        val (inferredType, typeConfidence) = inferDeviceType(
            isGateway = isGateway,
            isCurrentDevice = isCurrentDevice,
            openPorts = openPorts,
            mdnsService = cachedMdnsInfo?.serviceType,
            ssdpType = ssdpResult?.deviceType,
            resolvedVendor = resolvedVendor,
            name = primaryName ?: mdnsName ?: reverseDns,
            model = resolvedModel
        )

        ResolvedIdentity(
            primaryName = primaryName,
            nameSource = nameSource,
            nameConfidence = nameConfidence,
            resolvedVendor = resolvedVendor,
            vendorSource = vendorSource,
            vendorConfidence = vendorConfidence,
            isRandomizedMac = macResolution.isRandomizedMac,
            inferredType = inferredType,
            typeConfidence = typeConfidence,
            mdnsName = mdnsName,
            ssdpName = ssdpFriendlyName,
            netbiosName = netbiosName,
            modelName = resolvedModel,
            rawDnsHostname = reverseDns
        )
    }

    private fun cleanCandidateName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val clean = name.trim()
        if (clean.equals("Unknown", ignoreCase = true) ||
            clean.equals("Unavailable", ignoreCase = true) ||
            clean.equals("None reported", ignoreCase = true) ||
            clean.equals("Generic Device", ignoreCase = true) ||
            clean.equals("localhost", ignoreCase = true)
        ) {
            return null
        }
        return clean
    }

    private fun isReliableHostname(hostname: String?): Boolean {
        if (hostname.isNullOrBlank()) return false
        val clean = hostname.trim()
        if (clean.startsWith("ip-", ignoreCase = true) ||
            clean.startsWith("host-", ignoreCase = true) ||
            clean.matches(Regex("^\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3}.*$")) ||
            clean.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
        ) {
            return false
        }
        return true
    }

    private fun extractVendorFromHttp(serverHeader: String?, title: String?): String? {
        val combined = "${serverHeader ?: ""} ${title ?: ""}"
        return ManufacturerResolver.normalizeVendor(combined)
    }

    private fun inferVendorFromHostname(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val lower = host.lowercase()
        return when {
            lower.contains("iphone") || lower.contains("ipad") || lower.contains("macbook") || lower.contains("apple") -> "Apple"
            lower.contains("galaxy") || lower.contains("samsung") -> "Samsung"
            lower.contains("redmi") || lower.contains("xiaomi") || lower.contains("poco") -> "Xiaomi"
            lower.contains("huawei") || lower.contains("honor") -> "Huawei"
            lower.contains("pixel") || lower.contains("nest") || lower.contains("chromecast") -> "Google"
            lower.contains("echo") || lower.contains("firetv") || lower.contains("kindle") -> "Amazon"
            lower.contains("tplink") || lower.contains("tp-link") || lower.contains("archer") -> "TP-Link"
            lower.contains("playstation") || lower.contains("ps4") || lower.contains("ps5") -> "Sony"
            lower.contains("xbox") -> "Microsoft"
            lower.contains("synology") -> "Synology"
            lower.contains("raspberry") -> "Raspberry Pi"
            lower.contains("espressif") || lower.contains("esp32") || lower.contains("esp8266") -> "Espressif"
            else -> null
        }
    }

    /**
     * Determines device type from real physical signals (ports, mDNS services, SSDP descriptors, vendors).
     */
    fun inferDeviceType(
        isGateway: Boolean,
        isCurrentDevice: Boolean,
        openPorts: List<Int>,
        mdnsService: String?,
        ssdpType: String?,
        resolvedVendor: String?,
        name: String?,
        model: String?
    ): Pair<DeviceType, ConfidenceLevel> {
        if (isGateway) return DeviceType.ROUTER to ConfidenceLevel.HIGH
        if (isCurrentDevice) return DeviceType.PHONE to ConfidenceLevel.HIGH

        val lowerName = (name ?: "").lowercase()
        val lowerMdns = (mdnsService ?: "").lowercase()
        val lowerSsdp = (ssdpType ?: "").lowercase()
        val lowerVendor = (resolvedVendor ?: "").lowercase()
        val lowerModel = (model ?: "").lowercase()

        // 1. Printers
        if (lowerMdns.contains("printer") || lowerMdns.contains("ipp") ||
            lowerSsdp.contains("printer") || openPorts.contains(9100) || openPorts.contains(631) ||
            lowerName.contains("printer") || lowerName.contains("deskjet") || lowerName.contains("pixma")
        ) {
            return DeviceType.PRINTER to ConfidenceLevel.HIGH
        }

        // 2. Smart TVs & Streaming Devices
        if (lowerMdns.contains("googlecast") || lowerMdns.contains("airplay") || lowerMdns.contains("spotify") ||
            lowerSsdp.contains("mediarenderer") || lowerSsdp.contains("smarttv") || lowerSsdp.contains("dial") ||
            openPorts.contains(8008) || openPorts.contains(8009) ||
            lowerName.contains("tv") || lowerName.contains("roku") || lowerName.contains("chromecast") ||
            lowerVendor.contains("roku") || lowerVendor.contains("sonos") || lowerVendor.contains("lg electronics")
        ) {
            return DeviceType.SMART_TV to ConfidenceLevel.HIGH
        }

        // 3. Gaming Consoles
        if (lowerName.contains("playstation") || lowerName.contains("ps4") || lowerName.contains("ps5") ||
            lowerName.contains("xbox") || lowerName.contains("nintendo") ||
            lowerVendor.contains("nintendo") || lowerModel.contains("playstation") || lowerModel.contains("xbox")
        ) {
            return DeviceType.GAMING_CONSOLE to ConfidenceLevel.HIGH
        }

        // 4. IP Cameras
        if (openPorts.contains(554) || lowerName.contains("camera") || lowerName.contains("cam") ||
            lowerName.contains("dahua") || lowerName.contains("hikvision") || lowerMdns.contains("onvif")
        ) {
            return DeviceType.CAMERA to ConfidenceLevel.HIGH
        }

        // 5. Routers / Gateways / APs
        if (lowerSsdp.contains("internetgatewaydevice") || lowerName.contains("router") ||
            lowerName.contains("gateway") || lowerName.contains("openwrt") || lowerName.contains("archer") ||
            (openPorts.contains(53) && (openPorts.contains(80) || openPorts.contains(8080)))
        ) {
            return DeviceType.ROUTER to ConfidenceLevel.HIGH
        }

        // 6. PCs / Laptops / Workstations
        if (lowerMdns.contains("smb") || lowerMdns.contains("workstation") ||
            openPorts.contains(445) || openPorts.contains(139) || openPorts.contains(3389) ||
            lowerName.contains("desktop") || lowerName.contains("laptop") || lowerName.contains("macbook") ||
            lowerName.contains("pc") || lowerName.contains("thinkpad") || lowerName.contains("surface")
        ) {
            return DeviceType.PC to ConfidenceLevel.HIGH
        }

        // 7. Tablets
        if (lowerName.contains("ipad") || lowerName.contains("tablet") || lowerName.contains("tab")) {
            return DeviceType.TABLET to ConfidenceLevel.HIGH
        }

        // 8. Mobile Phones
        if (lowerName.contains("iphone") || lowerName.contains("galaxy") || lowerName.contains("pixel") ||
            lowerName.contains("redmi") || lowerName.contains("phone") || lowerName.contains("android") ||
            lowerMdns.contains("companion-link")
        ) {
            return DeviceType.PHONE to ConfidenceLevel.HIGH
        }

        // 9. IoT & Embedded Smart Home
        if (lowerVendor.contains("espressif") || lowerVendor.contains("raspberry") ||
            lowerVendor.contains("tuya") || lowerVendor.contains("philips") ||
            lowerName.contains("esp32") || lowerName.contains("sonoff") || lowerName.contains("shelly") ||
            lowerName.contains("hub") || lowerName.contains("smart")
        ) {
            return DeviceType.IOT to ConfidenceLevel.HIGH
        }

        // Fallback checks on vendor
        if (lowerVendor == "apple" || lowerVendor == "samsung" || lowerVendor == "xiaomi" || lowerVendor == "huawei") {
            return DeviceType.PHONE to ConfidenceLevel.MEDIUM
        }
        if (lowerVendor == "dell" || lowerVendor == "hp" || lowerVendor == "lenovo" || lowerVendor == "intel" || lowerVendor == "microsoft") {
            return DeviceType.PC to ConfidenceLevel.MEDIUM
        }
        if (lowerVendor == "tp-link" || lowerVendor == "netgear" || lowerVendor == "d-link" || lowerVendor == "cisco" || lowerVendor == "asus") {
            return DeviceType.ROUTER to ConfidenceLevel.MEDIUM
        }

        return DeviceType.UNKNOWN to ConfidenceLevel.NONE
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
