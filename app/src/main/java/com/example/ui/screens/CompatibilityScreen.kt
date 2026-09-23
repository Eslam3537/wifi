package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CapabilityState
import com.example.model.CompatibilityAuditResult
import com.example.model.KernelSuStatus
import com.example.model.RootStatus
import com.example.model.SystemCapabilityReport
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun CompatibilityScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val audit by viewModel.compatibilityAudit.collectAsState()
    val capabilityReport by viewModel.capabilityReport.collectAsState()
    val fullAudit by viewModel.capabilityAuditResult.collectAsState()
    val currentThemeMode by viewModel.currentThemeMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme & Appearance Preferences Card
        AppleCard(
            modifier = Modifier.fillMaxWidth().testTag("theme_preference_card"),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    AppleThemeIcon(
                        mode = currentThemeMode,
                        tint = MaterialTheme.colorScheme.secondary,
                        size = 18.dp
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.theme_mode),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (currentThemeMode) {
                            AppThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                            AppThemeMode.LIGHT -> stringResource(R.string.theme_light)
                            AppThemeMode.DARK -> stringResource(R.string.theme_dark)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3-way Segmented Selector for Theme
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val themeList = listOf(
                    AppThemeMode.SYSTEM to R.string.theme_system,
                    AppThemeMode.LIGHT to R.string.theme_light,
                    AppThemeMode.DARK to R.string.theme_dark
                )

                themeList.forEach { (mode, labelRes) ->
                    val isSelected = currentThemeMode == mode
                    Surface(
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("theme_chip_${mode.key}"),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (isSelected) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            AppleThemeIcon(
                                mode = mode,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 15.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        // Target Device Card
        AppleCard(
            modifier = Modifier.fillMaxWidth().testTag("compat_summary_card"),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        text = stringResource(R.string.system_capability_audit),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.target_architecture),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Environment: ${audit?.targetDevice ?: "Android Device"} | ${audit?.osVersion ?: "Android 16"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Re-audit Button
        OutlinedButton(
            onClick = {
                viewModel.reevaluateAllCapabilities(forceRefresh = true)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.reevaluate_capabilities),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        // Real-time Capability Dashboard
        AppleSectionHeader(
            title = stringResource(R.string.capability_dashboard),
            trailing = {
                IconButton(
                    onClick = { viewModel.refreshCapabilities() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Capabilities",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        )

        AppleCard(
            modifier = Modifier.testTag("capability_dashboard_card"),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            CapabilityStatusRow(name = stringResource(R.string.cap_root), state = capabilityReport?.root ?: CapabilityState.UNSUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_ksu), state = capabilityReport?.kernelSu ?: CapabilityState.UNSUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_network_scan), state = capabilityReport?.networkScan ?: CapabilityState.SUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_mdns), state = capabilityReport?.mDns ?: CapabilityState.SUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_tcp), state = capabilityReport?.tcpScan ?: CapabilityState.SUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_icmp), state = capabilityReport?.icmp ?: CapabilityState.LIMITED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_mac), state = capabilityReport?.macAccess ?: CapabilityState.RESTRICTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_arp), state = capabilityReport?.arpInfo ?: CapabilityState.RESTRICTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_router), state = capabilityReport?.routerControl ?: CapabilityState.UNSUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_block_device), state = capabilityReport?.blockDevice ?: CapabilityState.UNSUPPORTED)
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            CapabilityStatusRow(name = stringResource(R.string.cap_block_all), state = capabilityReport?.blockAll ?: CapabilityState.UNSUPPORTED)
        }

        // Android 14-16 API & Security Restrictions
        AppleSectionHeader(title = stringResource(R.string.android_restrictions_title))

        AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            AppleAuditCheckRow(
                title = stringResource(R.string.arm64_execution),
                passed = audit?.isArm64 == true,
                explanation = stringResource(R.string.arm64_explanation)
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            AppleAuditCheckRow(
                title = stringResource(R.string.mac_randomization_title),
                passed = audit?.macAddressRestricted == true,
                explanation = stringResource(R.string.mac_randomization_explanation)
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            AppleAuditCheckRow(
                title = stringResource(R.string.arp_sandbox_title),
                passed = audit?.procNetArpRestricted == true,
                explanation = stringResource(R.string.arp_sandbox_explanation)
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            AppleAuditCheckRow(
                title = stringResource(R.string.multicast_title),
                passed = audit?.localNetworkMulticastSupported == true,
                explanation = stringResource(R.string.multicast_explanation)
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            AppleAuditCheckRow(
                title = stringResource(R.string.bg_exec_title),
                passed = audit?.backgroundExecutionRestricted == true,
                explanation = stringResource(R.string.bg_exec_explanation)
            )
        }

        // Privilege Escalation & Root Diagnostics
        AppleSectionHeader(title = stringResource(R.string.privilege_escalation_title))

        AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            AppleDetailRow(
                title = stringResource(R.string.root_status_title),
                value = when (audit?.rootStatus) {
                    RootStatus.ROOT_AVAILABLE -> "ROOT AVAILABLE (Verified)"
                    RootStatus.ROOT_DENIED -> "ROOT DENIED (su non-zero)"
                    RootStatus.ROOT_UNSUPPORTED -> "ROOT UNSUPPORTED"
                    RootStatus.NO_ROOT, null -> "NO ROOT (Sandbox)"
                }
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            AppleDetailRow(
                title = stringResource(R.string.ksu_verification_title),
                value = when (audit?.kernelSuStatus) {
                    KernelSuStatus.SUPPORTED_AND_VERIFIED -> "VERIFIED & OPERATIONAL"
                    KernelSuStatus.DETECTED_NO_PERMISSION -> "DETECTED (No SU Permission)"
                    KernelSuStatus.NOT_DETECTED, null -> "NOT DETECTED"
                }
            )
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Text(
                text = stringResource(R.string.root_mac_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }

        fullAudit?.trafficPathReport?.let { path ->
            AppleSectionHeader(title = "Traffic Routing & Firewall Path")
            AppleCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                AppleDetailRow(
                    title = "Network Role",
                    value = when (path.role) {
                        com.example.domain.protection.PhoneNetworkRole.ROUTER_GATEWAY -> "Router Gateway"
                        com.example.domain.protection.PhoneNetworkRole.WIFI_HOTSPOT -> "Wi-Fi Hotspot (AP)"
                        com.example.domain.protection.PhoneNetworkRole.NETWORK_BRIDGE -> "Network Bridge"
                        com.example.domain.protection.PhoneNetworkRole.TRAFFIC_FORWARDING_DEVICE -> "Forwarding Node"
                        com.example.domain.protection.PhoneNetworkRole.NORMAL_WIFI_CLIENT -> "Wi-Fi Client (Station)"
                    }
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Traffic Traversal",
                    value = if (path.isForwardingEnabled) "Traverses Local Phone" else "Direct to Router (Bypasses Phone)"
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Peer Client Blocking",
                    value = if (path.canLocalFirewallBlockRemoteClients) "Active via Local Firewall" else "Requires Router ACL"
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = path.explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun AppleAuditCheckRow(
    title: String,
    passed: Boolean,
    explanation: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background((if (passed) AppleGreenLight else AppleRedLight).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (passed) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (passed) AppleGreenLight else AppleRedLight,
                modifier = Modifier.size(14.dp)
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CapabilityStatusRow(name: String, state: CapabilityState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        val (badgeText, badgeColor) = when (state) {
            CapabilityState.SUPPORTED -> stringResource(R.string.status_supported) to AppleGreenLight
            CapabilityState.LIMITED -> stringResource(R.string.status_limited) to AppleOrangeLight
            CapabilityState.RESTRICTED -> stringResource(R.string.status_restricted) to AppleTextSecondaryLight
            CapabilityState.UNSUPPORTED -> stringResource(R.string.status_unsupported) to AppleRedLight
        }

        ApplePillBadge(
            text = badgeText,
            textColor = badgeColor,
            containerColor = badgeColor.copy(alpha = 0.12f)
        )
    }
}

@Composable
fun AuditCheckRow(
    title: String,
    passed: Boolean,
    explanation: String
) {
    AppleAuditCheckRow(title = title, passed = passed, explanation = explanation)
}
