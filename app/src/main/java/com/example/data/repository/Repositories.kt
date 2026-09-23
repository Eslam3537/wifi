package com.example.data.repository

import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.DeviceDao
import com.example.data.local.DeviceEntity
import com.example.model.DeviceStatus
import com.example.model.DeviceType
import com.example.model.DiscoveredDevice
import com.example.model.DiscoveryMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DeviceRepository(private val deviceDao: DeviceDao) {

    fun getDevicesFlow(): Flow<List<DiscoveredDevice>> {
        return deviceDao.getAllDevicesFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getAllDevices(): List<DiscoveredDevice> {
        return deviceDao.getAllDevices().map { it.toDomain() }
    }

    suspend fun getDeviceByIp(ip: String): DiscoveredDevice? {
        return deviceDao.getDeviceByIp(ip)?.toDomain()
    }

    suspend fun saveDevice(device: DiscoveredDevice) {
        deviceDao.insertOrUpdate(device.toEntity())
    }

    suspend fun saveDevices(devices: List<DiscoveredDevice>) {
        deviceDao.insertAll(devices.map { it.toEntity() })
    }

    suspend fun clearAll() {
        deviceDao.clearAll()
    }

    private fun DeviceEntity.toDomain(): DiscoveredDevice {
        val ports = if (openPortsCsv.isBlank()) emptyList() else openPortsCsv.split(",").mapNotNull { it.toIntOrNull() }
        val methods = if (discoveryMethodsCsv.isBlank()) emptySet() else discoveryMethodsCsv.split(",")
            .mapNotNull { runCatching { DiscoveryMethod.valueOf(it) }.getOrNull() }.toSet()
        val type = runCatching { DeviceType.valueOf(deviceType) }.getOrDefault(DeviceType.UNKNOWN)
        val stat = runCatching { DeviceStatus.valueOf(status) }.getOrDefault(DeviceStatus.ONLINE)
        val access = runCatching { com.example.model.DeviceAccessStatus.valueOf(accessStatus) }.getOrDefault(com.example.model.DeviceAccessStatus.ONLINE)
        val nSource = runCatching { com.example.model.IdentitySource.valueOf(nameSource) }.getOrDefault(com.example.model.IdentitySource.UNKNOWN)
        val nConf = runCatching { com.example.model.ConfidenceLevel.valueOf(nameConfidence) }.getOrDefault(com.example.model.ConfidenceLevel.NONE)
        val vSource = runCatching { com.example.model.IdentitySource.valueOf(vendorSource) }.getOrDefault(com.example.model.IdentitySource.UNKNOWN)
        val vConf = runCatching { com.example.model.ConfidenceLevel.valueOf(vendorConfidence) }.getOrDefault(com.example.model.ConfidenceLevel.NONE)

        return DiscoveredDevice(
            ip = ip,
            mac = mac,
            vendor = vendor,
            hostname = hostname,
            deviceType = type,
            openPorts = ports,
            discoveryMethods = methods,
            latencyMs = latencyMs,
            isGateway = isGateway,
            isCurrentDevice = isCurrentDevice,
            isProtected = isProtected,
            isMacRandomized = isMacRandomized,
            nameSource = nSource,
            nameConfidence = nConf,
            vendorSource = vSource,
            vendorConfidence = vConf,
            mdnsName = mdnsName,
            netbiosName = netbiosName,
            ssdpFriendlyName = ssdpFriendlyName,
            modelName = modelName,
            rawDnsHostname = rawDnsHostname,
            accessStatus = access,
            bandwidthLimitKbps = bandwidthLimitKbps,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            status = stat
        )
    }

    private fun DiscoveredDevice.toEntity(): DeviceEntity {
        return DeviceEntity(
            ip = ip,
            mac = mac,
            vendor = vendor,
            hostname = hostname,
            deviceType = deviceType.name,
            openPortsCsv = openPorts.joinToString(","),
            discoveryMethodsCsv = discoveryMethods.map { it.name }.joinToString(","),
            latencyMs = latencyMs,
            isGateway = isGateway,
            isCurrentDevice = isCurrentDevice,
            isProtected = isProtected,
            isMacRandomized = isMacRandomized,
            nameSource = nameSource.name,
            nameConfidence = nameConfidence.name,
            vendorSource = vendorSource.name,
            vendorConfidence = vendorConfidence.name,
            mdnsName = mdnsName,
            netbiosName = netbiosName,
            ssdpFriendlyName = ssdpFriendlyName,
            modelName = modelName,
            rawDnsHostname = rawDnsHostname,
            accessStatus = accessStatus.name,
            bandwidthLimitKbps = bandwidthLimitKbps,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            status = status.name
        )
    }
}

class ActivityLogRepository(private val activityLogDao: ActivityLogDao) {

    fun getLogsFlow(): Flow<List<ActivityLogEntity>> {
        return activityLogDao.getAllLogsFlow()
    }

    suspend fun log(
        component: String,
        level: String,
        operation: String,
        result: String,
        errorDetails: String? = null
    ) {
        activityLogDao.insert(
            ActivityLogEntity(
                timestamp = System.currentTimeMillis(),
                component = component,
                level = level,
                operation = operation,
                result = result,
                errorDetails = errorDetails
            )
        )
    }

    suspend fun clearLogs() {
        activityLogDao.clearAll()
    }
}
