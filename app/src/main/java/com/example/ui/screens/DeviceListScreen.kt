package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.model.DeviceAccessStatus
import com.example.model.DiscoveredDevice
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsState(initial = emptyList())
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val selectedIps by viewModel.selectedDeviceIps.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()
    val operationFeedback by viewModel.operationFeedback.collectAsState()
    val globalBwLimit by viewModel.globalBandwidthLimit.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showBlockAllConfirmDialog by remember { mutableStateOf(false) }
    var showUnblockAllConfirmDialog by remember { mutableStateOf(false) }
    var showGlobalSpeedLimitDialog by remember { mutableStateOf(false) }
    var showEmergencyResetConfirmDialog by remember { mutableStateOf(false) }

    val currentIp = networkInfo?.ip
    val hasActiveRestrictions = remember(devices, globalBwLimit) {
        devices.any { it.accessStatus == DeviceAccessStatus.BLOCKED || (it.bandwidthLimitKbps != null && it.bandwidthLimitKbps > 0) } || globalBwLimit != null
    }

    val filteredDevices = remember(devices, searchQuery) {
        if (searchQuery.isBlank()) devices
        else devices.filter {
            it.ip.contains(searchQuery, ignoreCase = true) ||
            (it.vendor?.contains(searchQuery, ignoreCase = true) == true) ||
            (it.hostname?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    val isSelectionMode = selectedIps.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Apple-style Inset Search Bar
                AppleSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_devices_placeholder),
                    modifier = Modifier.testTag("device_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action Bar: Selection Controls & Block All Users
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Button(
                            onClick = { viewModel.blockSelectedDevices() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRedLight),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("btn_block_selected")
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${stringResource(R.string.block_selected)} (${selectedIps.size})",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.allowSelectedDevices() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("btn_allow_selected")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.allow_selected),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.clearDeviceSelection() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection", modifier = Modifier.size(18.dp))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.selectAllEligibleDevices(devices) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("btn_select_all")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }

                        Button(
                            onClick = { showBlockAllConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRedLight),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("btn_block_all")
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.block_all_users),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Network Controls Quick Strip: Global Speed Limit & Restore All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showGlobalSpeedLimitDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .testTag("btn_global_speed_limit")
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        val label = if (globalBwLimit != null && globalBwLimit!! > 0) {
                            if (globalBwLimit!! >= 1000) "Global: ${globalBwLimit!! / 1000}M" else "Global: ${globalBwLimit}K"
                        } else {
                            "Global Limit"
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }

                    if (hasActiveRestrictions) {
                        Button(
                            onClick = { viewModel.restoreAllNetworkControls() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("btn_restore_all_controls")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore All", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }

                        IconButton(
                            onClick = { showEmergencyResetConfirmDialog = true },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppleRedLight.copy(alpha = 0.12f))
                                .testTag("btn_emergency_reset")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Emergency Reset", tint = AppleRedLight, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Operation Feedback notification banner
                if (operationFeedback != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AppleCard(
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        cornerRadius = 12.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
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
                            IconButton(onClick = { viewModel.clearFeedback() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Header separator
                Spacer(modifier = Modifier.height(6.dp))
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!isScanning) viewModel.startScan() else viewModel.stopScan() },
                icon = {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.Radar,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = if (isScanning) stringResource(R.string.stop_scan) else stringResource(R.string.scan_network),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                containerColor = if (isScanning) AppleRedLight else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .testTag("fab_scan_network")
            )
        }
    ) { padding ->
        if (filteredDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(bottom = 90.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = if (isScanning) stringResource(R.string.scanning) else "No devices found. Click Scan Network.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
            ) {
                item {
                    AppleSectionHeader(
                        title = "${stringResource(R.string.nav_devices)} (${filteredDevices.size})"
                    )
                }

                items(filteredDevices, key = { it.ip }) { device ->
                    val isCurrent = device.isCurrentDevice || (device.ip == currentIp)
                    val isProt = device.isProtected || isCurrent || device.isGateway
                    val isSelected = selectedIps.contains(device.ip)

                    SelectableDeviceListItem(
                        device = device.copy(
                            isCurrentDevice = isCurrent,
                            isProtected = isProt
                        ),
                        isSelected = isSelected,
                        onToggleSelect = {
                            if (!isCurrent && !device.isGateway && !isProt) {
                                viewModel.toggleDeviceSelection(device.ip)
                            }
                        },
                        onCardClick = { viewModel.selectDevice(device) },
                        onToggleProtect = { viewModel.toggleDeviceProtection(device) }
                    )
                }
            }
        }

        // Details Modal Bottom Sheet
        if (selectedDevice != null) {
            DeviceDetailsSheet(
                device = selectedDevice!!,
                viewModel = viewModel,
                onDismiss = { viewModel.selectDevice(null) }
            )
        }

        // Confirmation Dialog for Block All Users
        if (showBlockAllConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showBlockAllConfirmDialog = false },
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AppleRedLight) },
                title = {
                    Text(
                        text = stringResource(R.string.block_all_confirm_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.block_all_confirm_msg),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBlockAllConfirmDialog = false
                            viewModel.blockAllUsers()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRedLight)
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBlockAllConfirmDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Global Speed Limit Configuration Dialog
        if (showGlobalSpeedLimitDialog) {
            var selectedLimit by remember { mutableStateOf(globalBwLimit ?: 0L) }
            AlertDialog(
                onDismissRequest = { showGlobalSpeedLimitDialog = false },
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = {
                    Text(
                        text = "Global Bandwidth Control",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Sets a default maximum throughput rate for all connected client devices using Linux Traffic Control (tc). Excluded and protected devices remain unlimited.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val globalPresets = listOf(
                            0L to "Unlimited",
                            1000L to "1 Mbps",
                            5000L to "5 Mbps",
                            10000L to "10 Mbps",
                            25000L to "25 Mbps"
                        )

                        globalPresets.forEach { (rate, label) ->
                            val isSel = selectedLimit == rate
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .clickable { selectedLimit = rate }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal))
                                if (isSel) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGlobalSpeedLimitDialog = false
                            if (selectedLimit <= 0L) {
                                viewModel.removeGlobalBandwidthLimit()
                            } else {
                                viewModel.setGlobalBandwidthLimit(selectedLimit)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Apply Global Limit")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGlobalSpeedLimitDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Emergency Reset Confirmation Dialog
        if (showEmergencyResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showEmergencyResetConfirmDialog = false },
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = AppleRedLight) },
                title = {
                    Text(
                        text = "Reset App Network Controls?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Text(
                        text = "This will immediately remove all app-created firewall rules, traffic shaping qdiscs, router blocks, and reset local restriction state. It does NOT touch unrelated system rules.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showEmergencyResetConfirmDialog = false
                            viewModel.emergencyResetNetworkControls()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRedLight)
                    ) {
                        Text("Reset All Controls")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEmergencyResetConfirmDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun SelectableDeviceListItem(
    device: DiscoveredDevice,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onCardClick: () -> Unit,
    onToggleProtect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBlocked = device.accessStatus == DeviceAccessStatus.BLOCKED
    val isEligibleForSelection = !device.isGateway && !device.isCurrentDevice && !device.isProtected
    val hasBandwidthLimit = device.bandwidthLimitKbps != null && device.bandwidthLimitKbps > 0

    AppleCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("device_item_${device.ip}"),
        backgroundColor = if (isBlocked) AppleRedLight.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surface,
        borderColor = if (isBlocked) AppleRedLight.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.outline,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Multi-Select Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                enabled = isEligibleForSelection,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.testTag("checkbox_${device.ip}")
            )

            // Apple Hardware Squircle Icon
            AppleDeviceIcon(
                deviceType = device.deviceType,
                isGateway = device.isGateway,
                isCurrentDevice = device.isCurrentDevice,
                size = 44.dp
            )

            // Device Specs & Information
            Column(modifier = Modifier.weight(1f)) {
                // Name & Badges
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

                    if (isBlocked) {
                        ApplePillBadge(
                            text = "BLOCKED",
                            textColor = AppleRedLight,
                            containerColor = AppleRedLight.copy(alpha = 0.14f)
                        )
                    } else if (hasBandwidthLimit) {
                        ApplePillBadge(
                            text = "LIMITED (${device.formattedBandwidthLimit})",
                            textColor = AppleOrangeLight,
                            containerColor = AppleOrangeLight.copy(alpha = 0.14f)
                        )
                    }
                }

                // IP and Vendor
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

                // Honest MAC info & Speed status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "MAC: ${device.displayMac}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (hasBandwidthLimit) {
                        Text(
                            text = "• Limit: ${device.formattedBandwidthLimit}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AppleOrangeLight
                        )
                    }
                }
            }

            // Protect Button toggle
            if (!device.isCurrentDevice && !device.isGateway) {
                IconButton(
                    onClick = { onToggleProtect() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.isProtected) AppleIndigoLight.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .testTag("btn_protect_${device.ip}")
                ) {
                    Icon(
                        imageVector = if (device.isProtected) Icons.Default.Shield else Icons.Default.Security,
                        contentDescription = if (device.isProtected) "Unprotect" else "Protect",
                        tint = if (device.isProtected) AppleIndigoLight else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
