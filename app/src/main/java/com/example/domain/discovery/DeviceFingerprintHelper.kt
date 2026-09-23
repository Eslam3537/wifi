package com.example.domain.discovery

import com.example.model.DiscoveredDevice
import com.example.model.DeviceType

object DeviceFingerprintHelper {

    fun resolveVendor(mac: String?): String {
        val resolution = ManufacturerResolver.resolveVendorFromMac(mac)
        return resolution.vendor ?: "Unknown manufacturer"
    }

    fun isReliableHostname(hostname: String?): Boolean {
        if (hostname.isNullOrBlank()) return false
        val clean = hostname.trim()
        if (clean.equals("Unknown", ignoreCase = true) ||
            clean.equals("Unavailable", ignoreCase = true) ||
            clean.equals("None reported", ignoreCase = true) ||
            clean.equals("Unknown Device", ignoreCase = true) ||
            clean.equals("Generic Device", ignoreCase = true) ||
            clean.equals("localhost", ignoreCase = true) ||
            clean.startsWith("ip-", ignoreCase = true) ||
            clean.matches(Regex("^\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3}.*$")) ||
            clean.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
        ) {
            return false
        }
        return true
    }

    fun getCleanManufacturer(vendor: String?): String? {
        return ManufacturerResolver.normalizeVendor(vendor)
    }

    /**
     * Resolves display identity adhering strictly to priority:
     * Priority 1: Real Device Name (SSDP, mDNS, DHCP, NetBIOS, HTTP Title)
     * Priority 2: Manufacturer / Vendor (Xiaomi, Apple, Samsung, Dell, etc.)
     * Priority 3: Safe Generic Identity ("Unknown Device")
     */
    fun getPrimaryIdentity(
        device: DiscoveredDevice,
        unknownDeviceLabel: String,
        gatewayLabel: String = "Default Gateway",
        thisDeviceLabel: String = "This Device"
    ): String {
        if (device.isCurrentDevice) return thisDeviceLabel
        if (device.isGateway) return gatewayLabel

        // Priority 1 — Real Device Name
        val bestName = device.ssdpFriendlyName ?: device.mdnsName ?: device.netbiosName ?: device.hostname
        if (isReliableHostname(bestName)) {
            return bestName!!.trim()
        }

        // Priority 2 — Manufacturer / Vendor
        val cleanVendor = getCleanManufacturer(device.vendor)
        if (!cleanVendor.isNullOrBlank() && !device.isMacRandomized) {
            return cleanVendor
        }

        // Priority 3 — Safe Generic Identity
        return unknownDeviceLabel
    }

    fun inferDeviceType(
        isGateway: Boolean,
        isCurrentDevice: Boolean,
        hostname: String?,
        openPorts: List<Int>,
        vendor: String
    ): DeviceType {
        val (type, _) = DeviceIdentityResolver.inferDeviceType(
            isGateway = isGateway,
            isCurrentDevice = isCurrentDevice,
            openPorts = openPorts,
            mdnsService = null,
            ssdpType = null,
            resolvedVendor = vendor,
            name = hostname,
            model = null
        )
        return type
    }
}

