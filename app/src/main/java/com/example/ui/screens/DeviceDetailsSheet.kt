package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.discovery.DeviceFingerprintHelper
import com.example.model.DeviceAccessStatus
import com.example.model.DeviceSpeedStatus
import com.example.model.DiscoveredDevice
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsSheet(
    device: DiscoveredDevice,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val operationFeedback by viewModel.operationFeedback.collectAsState()
    val routerCap by viewModel.routerCapability.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()
    val tcCap by viewModel.trafficControlCapability.collectAsState()
    val globalBwLimit by viewModel.globalBandwidthLimit.collectAsState()

    val currentIp = networkInfo?.ip
    val isCurrentDevice = device.isCurrentDevice || (device.ip == currentIp)
    val isGateway = device.isGateway
    val isProtected = device.isProtected || isCurrentDevice || isGateway
    val isBlocked = device.accessStatus == DeviceAccessStatus.BLOCKED

    // Local slider state for smooth UI interaction
    var currentSliderValue by remember(device.bandwidthLimitKbps) {
        mutableFloatStateOf((device.bandwidthLimitKbps ?: 0L).toFloat())
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clearFeedback()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Device Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AppleDeviceIcon(
                    deviceType = device.deviceType,
                    isGateway = isGateway,
                    isCurrentDevice = isCurrentDevice,
                    size = 54.dp
                )

                Column(modifier = Modifier.weight(1f)) {
                    val unknownDeviceStr = stringResource(R.string.unknown_device)
                    val gatewayStr = stringResource(R.string.gateway_device)
                    val thisDeviceStr = stringResource(R.string.this_device)
                    val primaryName = DeviceFingerprintHelper.getPrimaryIdentity(
                        device = device,
                        unknownDeviceLabel = unknownDeviceStr,
                        gatewayLabel = gatewayStr,
                        thisDeviceLabel = thisDeviceStr
                    )
                    val cleanVendor = DeviceFingerprintHelper.getCleanManufacturer(device.vendor)

                    Text(
                        text = primaryName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = device.ip,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = cleanVendor ?: stringResource(R.string.unknown_manufacturer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isCurrentDevice) {
                        ApplePillBadge(
                            text = stringResource(R.string.current_device),
                            textColor = AppleGreenLight,
                            containerColor = AppleGreenLight.copy(alpha = 0.12f)
                        )
                    }
                    if (isGateway) {
                        ApplePillBadge(
                            text = "GATEWAY",
                            textColor = AppleBlueLight,
                            containerColor = AppleBlueLight.copy(alpha = 0.12f)
                        )
                    }
                    if (isProtected) {
                        ApplePillBadge(
                            text = stringResource(R.string.protected_device),
                            textColor = AppleIndigoLight,
                            containerColor = AppleIndigoLight.copy(alpha = 0.12f)
                        )
                    }
                    if (isBlocked) {
                        ApplePillBadge(
                            text = "BLOCKED",
                            textColor = AppleRedLight,
                            containerColor = AppleRedLight.copy(alpha = 0.14f)
                        )
                    } else if (device.bandwidthLimitKbps != null && device.bandwidthLimitKbps > 0) {
                        ApplePillBadge(
                            text = "LIMITED (${device.formattedBandwidthLimit})",
                            textColor = AppleOrangeLight,
                            containerColor = AppleOrangeLight.copy(alpha = 0.14f)
                        )
                    }
                }
            }

            // Operation Feedback banner
            if (operationFeedback != null) {
                AppleCard(
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    cornerRadius = 12.dp,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            text = operationFeedback!!,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Section 1: Device Management & Access Control
            AppleSectionHeader(title = stringResource(R.string.device_management))

            AppleCard(
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Protect/Unprotect Action
                    if (!isCurrentDevice && !isGateway) {
                        OutlinedButton(
                            onClick = { viewModel.toggleDeviceProtection(device) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("btn_toggle_protect")
                        ) {
                            Icon(
                                imageVector = if (device.isProtected) Icons.Default.Security else Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (device.isProtected) stringResource(R.string.unprotect_device)
                                else stringResource(R.string.protect_device),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    // Block / Allow Action Button
                    Column {
                        if (isBlocked) {
                            Button(
                                onClick = { viewModel.attemptUnblockDevice(device) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("btn_unblock_device")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.unblock_device),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        } else {
                            val canAttemptBlock = !isCurrentDevice && !isGateway && !isProtected
                            Button(
                                onClick = { viewModel.attemptBlockDevice(device) },
                                enabled = canAttemptBlock,
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRedLight),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("btn_block_device")
                            ) {
                                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.block_device),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            if (isCurrentDevice) {
                                Text(
                                    text = stringResource(R.string.cannot_block_current_device),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppleGreenLight,
                                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                )
                            } else if (isGateway) {
                                Text(
                                    text = stringResource(R.string.cannot_block_gateway),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                )
                            } else if (isProtected) {
                                Text(
                                    text = stringResource(R.string.cannot_block_protected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppleIndigoLight,
                                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                )
                            }
                        }
                    }

                    // Restore Device (Single Device Reset)
                    if (isBlocked || (device.bandwidthLimitKbps != null && device.bandwidthLimitKbps > 0)) {
                        OutlinedButton(
                            onClick = { viewModel.restoreDevice(device) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("btn_restore_single_device")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Restore Device (Unblock & Unlimited)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            // Section 2: Real Bandwidth & Speed Control (Traffic Control / HTB)
            AppleSectionHeader(title = "Bandwidth & Speed Control")

            AppleCard(
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rate Limit (Download)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val displayVal = when {
                            currentSliderValue <= 0f -> "Unlimited"
                            currentSliderValue >= 1000f -> "${(currentSliderValue / 1000).toInt()} Mbps"
                            else -> "${currentSliderValue.toInt()} Kbps"
                        }
                        Text(
                            text = displayVal,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (currentSliderValue > 0f) AppleOrangeLight else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Preset Chips
                    val presets = listOf(
                        0L to "Unlimited",
                        512L to "512K",
                        1000L to "1 Mbps",
                        5000L to "5 Mbps",
                        10000L to "10 Mbps",
                        25000L to "25 Mbps"
                    )

                    val canModifySpeed = !isCurrentDevice && !isGateway && !isProtected && !isBlocked

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { (kbps, label) ->
                            val isSelected = currentSliderValue.toLong() == kbps
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = canModifySpeed) {
                                        currentSliderValue = kbps.toFloat()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Slider
                    Slider(
                        value = currentSliderValue,
                        onValueChange = { currentSliderValue = it },
                        valueRange = 0f..50000f,
                        steps = 99,
                        enabled = canModifySpeed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_bandwidth_limit")
                    )

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                currentSliderValue = 0f
                                viewModel.removeDeviceBandwidthLimit(device)
                            },
                            enabled = canModifySpeed && (device.bandwidthLimitKbps != null && device.bandwidthLimitKbps > 0),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("btn_clear_bandwidth_limit")
                        ) {
                            Text("Remove Limit", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = {
                                val limitToSet = currentSliderValue.toLong()
                                if (limitToSet <= 0L) {
                                    viewModel.removeDeviceBandwidthLimit(device)
                                } else {
                                    viewModel.setDeviceBandwidthLimit(device, limitToSet)
                                }
                            },
                            enabled = canModifySpeed && (currentSliderValue.toLong() != (device.bandwidthLimitKbps ?: 0L)),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(44.dp)
                                .testTag("btn_apply_bandwidth_limit")
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Apply Limit", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Information Note
                    if (isBlocked) {
                        Text(
                            text = "Device is completely blocked from the network. Unblock it to configure rate limits.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleRedLight
                        )
                    } else if (isProtected) {
                        Text(
                            text = "This device is marked protected (exclusion rule). Traffic shaping is bypassed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleIndigoLight
                        )
                    } else if (isCurrentDevice || isGateway) {
                        Text(
                            text = "Rate limiting local gateway/host is restricted to maintain network stability.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleBlueLight
                        )
                    } else {
                        Text(
                            text = "Controls downstream throughput using Linux Traffic Control (tc/HTB). Verified via real kernel qdiscs.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Section 3: Device Details
            AppleSectionHeader(title = stringResource(R.string.device_details))

            AppleCard(
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                AppleDetailRow(
                    title = "Hardware MAC Address",
                    value = device.displayMac,
                    icon = Icons.Default.Fingerprint
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Manufacturer",
                    value = DeviceFingerprintHelper.getCleanManufacturer(device.vendor) ?: device.displayVendor,
                    icon = Icons.Default.Business
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Hostname",
                    value = device.hostname ?: "None reported",
                    icon = Icons.Default.Dns
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Device Type",
                    value = device.deviceType.name,
                    icon = Icons.Default.Devices
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Access Status",
                    value = device.accessStatus.name,
                    icon = Icons.Default.Security
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Bandwidth Limit",
                    value = device.formattedBandwidthLimit,
                    icon = Icons.Default.Speed
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = "Latency",
                    value = if (device.latencyMs != null) "${device.latencyMs} ms" else "Not measured",
                    icon = Icons.Default.Speed
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = stringResource(R.string.discovery_methods),
                    value = device.discoveryMethods.joinToString(", ") { it.name },
                    icon = Icons.Default.Radar
                )
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                AppleDetailRow(
                    title = stringResource(R.string.open_ports),
                    value = if (device.openPorts.isNotEmpty()) device.openPorts.joinToString(", ") else "None detected",
                    icon = Icons.Default.DoorSliding
                )
            }

            // Section 4: Device Diagnostics
            AppleSectionHeader(title = stringResource(R.string.device_diagnostics))

            OutlinedButton(
                onClick = { viewModel.runDiagnostics(device.ip) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("btn_diagnose_device")
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Run Diagnostics on ${device.ip}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
