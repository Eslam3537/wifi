package com.example.domain.protection

enum class EnforcementType {
    DEVICE_BLOCK,
    BANDWIDTH_LIMIT,
    GLOBAL_BANDWIDTH_LIMIT,
    APP_BLOCK,
    FIREWALL_CUSTOM_RULE
}

enum class EnforcementBackend {
    ROOT_IPTABLES,
    KERNELSU_NFTABLES,
    LINUX_TC_HTB,
    ROUTER_API
}

data class EnforcedRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: EnforcementType,
    val targetIdentifier: String, // IP, MAC, package UID, or "GLOBAL"
    val targetLabel: String,
    val backend: EnforcementBackend,
    val bandwidthLimitKbps: Long? = null,
    val appliedTimestamp: Long = System.currentTimeMillis(),
    val isVerified: Boolean = true,
    val rawRuleDescription: String = ""
)

data class RollbackSummary(
    val isSuccess: Boolean,
    val rolledBackCount: Int,
    val failedCount: Int,
    val message: String
)

data class RuleVerificationSummary(
    val totalRules: Int,
    val verifiedActiveCount: Int,
    val missingOrDroppedCount: Int,
    val isRootStillAvailable: Boolean,
    val message: String
)
