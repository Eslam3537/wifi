package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DeviceStatus
import com.example.model.DeviceType

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    val ip: String,
    val mac: String?,
    val vendor: String?,
    val hostname: String?,
    val deviceType: String,
    val openPortsCsv: String,
    val discoveryMethodsCsv: String,
    val latencyMs: Long?,
    val isGateway: Boolean,
    val isCurrentDevice: Boolean,
    val isProtected: Boolean = false,
    val isMacRandomized: Boolean = false,
    val nameSource: String = "UNKNOWN",
    val nameConfidence: String = "NONE",
    val vendorSource: String = "UNKNOWN",
    val vendorConfidence: String = "NONE",
    val mdnsName: String? = null,
    val netbiosName: String? = null,
    val ssdpFriendlyName: String? = null,
    val modelName: String? = null,
    val rawDnsHostname: String? = null,
    val accessStatus: String = "ONLINE",
    val bandwidthLimitKbps: Long? = null,
    val firstSeen: Long,
    val lastSeen: Long,
    val status: String
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val component: String,
    val level: String,
    val operation: String,
    val result: String,
    val errorDetails: String?
)
