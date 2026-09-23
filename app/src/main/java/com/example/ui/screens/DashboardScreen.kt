package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
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
import com.example.domain.discovery.DeviceFingerprintHelper
import com.example.model.*
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToDevices: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val networkInfo by viewModel.networkInfo.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val devices by viewModel.devices.collectAsState(initial = emptyList())
    val securityAlerts by viewModel.securityAlerts.collectAsState()
    val activeRules by viewModel.activeEnforcedRules.collectAsState()
    val isBgProtectionEnabled by viewModel.isBackgroundProtectionEnabled.collectAsState()
    val hasAllPermissions by viewModel.hasAllPermissions.collectAsState()

    val currentIp = networkInfo?.ip

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        // Permissions Missing Banner (Prompts user to grant all required permissions)
        if (!hasAllPermissions) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("permissions_request_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.permissions_required_title),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.permissions_required_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("grant_permissions_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.grant_permissions_action),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }

        // Active Background Protection Banner (when active)
        if (activeRules.isNotEmpty() || isBgProtectionEnabled) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("active_protection_banner_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.protection_active_banner),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.protection_active_banner_desc, activeRules.size),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            ApplePillBadge(
                                text = "${activeRules.size} RULES",
                                textColor = MaterialTheme.colorScheme.error,
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.rollbackAllNow() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.exit_restore_title),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Button(
                                onClick = { viewModel.openSmartExitDialog() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.manage_protection),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Hero Network Status Card
        item {
            AppleCard(
                modifier = Modifier.testTag("network_summary_card"),
                backgroundColor = MaterialTheme.colorScheme.surface,
                cornerRadius = 18.dp
            ) {
                // Top Header inside card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = networkInfo?.interfaceName?.uppercase() ?: "WLAN0",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (networkInfo != null) "Local Wi-Fi Network" else "Network Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ApplePillBadge(
                        text = if (networkInfo != null) stringResource(R.string.status_online) else stringResource(R.string.status_offline),
                        textColor = if (networkInfo != null) AppleGreenLight else AppleRedLight,
                        containerColor = (if (networkInfo != null) AppleGreenLight else AppleRedLight).copy(alpha = 0.12f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Network Specifications Grid (2x2)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(
                        title = stringResource(R.string.current_ip),
                        value = networkInfo?.ip ?: "—",
                        icon = Icons.Default.Computer
                    )
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(
                        title = stringResource(R.string.active_gateway),
                        value = networkInfo?.gatewayIp ?: "—",
                        icon = Icons.Default.Router
                    )
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(
                        title = stringResource(R.string.subnet_mask),
                        value = "/${networkInfo?.prefixLength ?: 24}",
                        icon = Icons.Default.Layers
                    )
                }
            }
        }

        // 2. Primary Action Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (isScanning) viewModel.stopScan() else viewModel.startScan()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("scan_network_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (isScanning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.Radar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning) stringResource(R.string.stop_scan) else stringResource(R.string.scan_network),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                OutlinedButton(
                    onClick = {
                        val gw = networkInfo?.gatewayIp ?: "8.8.8.8"
                        viewModel.runDiagnostics(gw)
                        onNavigateToDiagnostics()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("diagnostics_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.nav_diagnostics),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // 3. Stats Grid (Apple Inset Cards)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Device Count Card
                AppleCard(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToDevices() },
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.total_devices),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${devices.size}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Security Alerts Card
                AppleCard(
                    modifier = Modifier.weight(1f),
                    backgroundColor = if (securityAlerts.isNotEmpty()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surface,
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.security_alerts),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (securityAlerts.isNotEmpty()) AppleRedLight else AppleGreenLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${securityAlerts.size}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (securityAlerts.isNotEmpty()) AppleRedLight else AppleGreenLight
                    )
                }
            }
        }

        // 4. Discovered Devices Section
        item {
            AppleSectionHeader(
                title = stringResource(R.string.nav_devices),
                trailing = {
                    TextButton(onClick = onNavigateToDevices) {
                        Text(
                            text = stringResource(R.string.view_all),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }

        if (devices.isEmpty()) {
            item {
                AppleCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (isScanning) stringResource(R.string.scanning) else "No devices discovered yet. Click Scan Network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(devices.take(6)) { device ->
                val isCurrent = device.isCurrentDevice || (device.ip == currentIp)
                DeviceListItem(
                    device = device.copy(
                        isCurrentDevice = isCurrent,
                        isProtected = device.isProtected || isCurrent || device.isGateway
                    ),
                    onClick = {
                        viewModel.selectDevice(device)
                        onNavigateToDevices()
                    }
                )
            }
        }
    }
}

@Composable
fun DeviceListItem(
    device: DiscoveredDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppleCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("device_item_${device.ip}"),
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Apple-style squircle hardware icon
            AppleDeviceIcon(
                deviceType = device.deviceType,
                isGateway = device.isGateway,
                isCurrentDevice = device.isCurrentDevice,
                size = 44.dp
            )

            // Device Information
            Column(modifier = Modifier.weight(1f)) {
                // Title row: Hostname or Device Name, with badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val unknownDeviceStr = stringResource(R.string.unknown_device)
                    val gatewayStr = stringResource(R.string.gateway_device)
                    val thisDeviceStr = stringResource(R.string.this_device)
                    val primaryName = DeviceFingerprintHelper.getPrimaryIdentity(
                        device = device,
                        unknownDeviceLabel = unknownDeviceStr,
                        gatewayLabel = gatewayStr,
                        thisDeviceLabel = thisDeviceStr
                    )

                    Text(
                        text = primaryName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (device.isCurrentDevice) {
                        ApplePillBadge(
                            text = stringResource(R.string.current_device),
                            textColor = AppleGreenLight,
                            containerColor = AppleGreenLight.copy(alpha = 0.12f)
                        )
                    } else if (device.isGateway) {
                        ApplePillBadge(
                            text = "GATEWAY",
                            textColor = AppleBlueLight,
                            containerColor = AppleBlueLight.copy(alpha = 0.12f)
                        )
                    }
                    if (device.isProtected) {
                        ApplePillBadge(
                            text = stringResource(R.string.protected_device),
                            textColor = AppleIndigoLight,
                            containerColor = AppleIndigoLight.copy(alpha = 0.12f)
                        )
                    }
                }

                // Subtitle: IP address and vendor/MAC
                val cleanVendor = DeviceFingerprintHelper.getCleanManufacturer(device.vendor)
                val hasHostname = DeviceFingerprintHelper.isReliableHostname(device.hostname)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (hasHostname && !cleanVendor.isNullOrBlank()) {
                        Text(
                            text = cleanVendor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Text(
                        text = device.ip,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (!hasHostname && cleanVendor.isNullOrBlank()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = stringResource(R.string.unknown_manufacturer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // MAC info (Honest representation: show real MAC or explanation)
                Text(
                    text = "MAC: ${device.displayMac}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Latency & Chevron Accessory
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (device.latencyMs != null) {
                    ApplePillBadge(
                        text = "${device.latencyMs}ms",
                        textColor = AppleBlueLight,
                        containerColor = AppleBlueLight.copy(alpha = 0.12f)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
