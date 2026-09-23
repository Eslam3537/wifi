package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.router.model.RouterConnectedDevice
import com.example.domain.router.model.RouterConnectionState
import com.example.domain.router.model.RouterDashboardData
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

private enum class RouterTab {
    OVERVIEW,
    WIFI,
    DEVICES
}

@Composable
fun RouterConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.routerConnectionState.collectAsState()
    val dashboardData by viewModel.routerDashboardData.collectAsState()
    val lastError by viewModel.routerLastErrorMessage.collectAsState()
    val isBusy by viewModel.isRouterBusy.collectAsState()
    val isRouterDemoMode by viewModel.isRouterDemoMode.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()
    val routerCap by viewModel.routerCapability.collectAsState()
    val isProbing by viewModel.isProbingRouter.collectAsState()

    var selectedTab by remember { mutableStateOf(RouterTab.OVERVIEW) }

    var gatewayIpInput by remember { mutableStateOf(networkInfo?.gatewayIp ?: "192.168.1.1") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var saveInKeystore by remember { mutableStateOf(true) }

    // Dialog States
    var showWifiEditDialog by remember { mutableStateOf(false) }
    var showWifiConfirmDialog by remember { mutableStateOf(false) }
    var showRebootConfirmDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }

    var newSsid by remember { mutableStateOf("") }
    var newWifiPassword by remember { mutableStateOf("") }
    var newWifiEnabled by remember { mutableStateOf(true) }
    var newWifiChannel by remember { mutableStateOf("Auto") }

    // Auto load saved credentials on initial composition
    LaunchedEffect(Unit) {
        val saved = viewModel.routerSettingsManager.getRouterCredentials()
        if (saved != null) {
            username = saved.first
            password = saved.second
        }
        val savedIp = viewModel.routerSettingsManager.getSavedGatewayIp()
        if (!savedIp.isNullOrBlank()) {
            gatewayIpInput = savedIp
        }
    }

    LaunchedEffect(networkInfo) {
        if (networkInfo?.gatewayIp != null && gatewayIpInput == "192.168.1.1") {
            gatewayIpInput = networkInfo!!.gatewayIp
        }
    }

    // Wi-Fi Confirmation Dialog
    if (showWifiConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWifiConfirmDialog = false },
            icon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.router_confirm_change_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.router_confirm_change_desc))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• SSID: $newSsid", fontWeight = FontWeight.Bold)
                    if (newWifiPassword.isNotBlank()) {
                        Text("• Password: •••••••• (Updated)", fontWeight = FontWeight.Bold)
                    }
                    Text("• Radio: ${if (newWifiEnabled) "Enabled" else "Disabled"}")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWifiConfirmDialog = false
                        viewModel.updateRouterWifi(
                            ssid = newSsid,
                            password = newWifiPassword.ifBlank { null },
                            enabled = newWifiEnabled,
                            channel = newWifiChannel
                        )
                    }
                ) {
                    Text(stringResource(R.string.router_apply_changes))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWifiConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Reboot Confirmation Dialog
    if (showRebootConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRebootConfirmDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.router_reboot_confirm_title)) },
            text = { Text(stringResource(R.string.router_reboot_confirm_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootConfirmDialog = false
                        viewModel.rebootRouter()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.router_reboot_button))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRebootConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Disconnect Dialog
    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = { Text(stringResource(R.string.router_disconnect_button)) },
            text = { Text("Are you sure you want to end the active session on the router?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        viewModel.disconnectRouter()
                    }
                ) {
                    Text(stringResource(R.string.router_disconnect_button))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Wi-Fi Edit Sheet / Dialog
    if (showWifiEditDialog && dashboardData != null) {
        val currentWifi = dashboardData!!.wifiInfo
        AlertDialog(
            onDismissRequest = { showWifiEditDialog = false },
            title = { Text(stringResource(R.string.router_change_wifi_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = newSsid,
                        onValueChange = { newSsid = it },
                        label = { Text(stringResource(R.string.router_new_ssid)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("router_edit_ssid_input")
                    )

                    OutlinedTextField(
                        value = newWifiPassword,
                        onValueChange = { newWifiPassword = it },
                        label = { Text(stringResource(R.string.router_new_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("router_edit_password_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.router_wifi_enable), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = newWifiEnabled,
                            onCheckedChange = { newWifiEnabled = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWifiEditDialog = false
                        showWifiConfirmDialog = true
                    },
                    enabled = newSsid.isNotBlank()
                ) {
                    Text(stringResource(R.string.router_apply_changes))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWifiEditDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Main Layout: Authenticated Dashboard vs Connection Screen
    if (connectionState == RouterConnectionState.CONNECTED && dashboardData != null) {
        // Authenticated Dashboard
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isRouterDemoMode) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().testTag("router_demo_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.router_demo_banner_title),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.router_demo_banner_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Top Status Bar
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().testTag("router_authenticated_header")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AppleGreenLight.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Router, contentDescription = null, tint = AppleGreenLight, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = dashboardData!!.deviceInfo.model,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(AppleGreenLight))
                                Text(
                                    text = "${stringResource(R.string.router_connected_badge)} (${gatewayIpInput})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { viewModel.refreshRouterDashboard() },
                            enabled = !isBusy,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.router_refresh_data), modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = { showDisconnectConfirmDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = stringResource(R.string.router_disconnect_button), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Segmented Tabs (Overview / Wi-Fi / Connected Devices)
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color.Transparent,
                divider = {},
                indicator = {}
            ) {
                listOf(
                    RouterTab.OVERVIEW to stringResource(R.string.router_tab_overview),
                    RouterTab.WIFI to stringResource(R.string.router_tab_wifi),
                    RouterTab.DEVICES to stringResource(R.string.router_tab_devices)
                ).forEachIndexed { index, (tab, title) ->
                    val isSelected = selectedTab == tab
                    Surface(
                        onClick = { selectedTab = tab },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .padding(horizontal = 3.dp, vertical = 2.dp)
                            .height(38.dp)
                            .testTag("router_tab_${tab.name.lowercase()}")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                RouterTab.OVERVIEW -> RouterOverviewTab(
                    data = dashboardData!!,
                    onRebootClick = { showRebootConfirmDialog = true }
                )
                RouterTab.WIFI -> RouterWifiTab(
                    wifi = dashboardData!!.wifiInfo,
                    onEditWifiClick = {
                        newSsid = dashboardData!!.wifiInfo.ssid.ifBlank { "Huawei_HG630" }
                        newWifiPassword = ""
                        newWifiEnabled = dashboardData!!.wifiInfo.enabled
                        newWifiChannel = dashboardData!!.wifiInfo.channel
                        showWifiEditDialog = true
                    }
                )
                RouterTab.DEVICES -> RouterConnectedDevicesTab(
                    devices = dashboardData!!.connectedDevices
                )
            }
        }
    } else {
        // Connection & Login Screen
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Connection Card
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_login_card"),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.router_connection_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.router_connection_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Router IP
                OutlinedTextField(
                    value = gatewayIpInput,
                    onValueChange = { gatewayIpInput = it },
                    label = { Text(stringResource(R.string.router_ip_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("router_ip_input"),
                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Username
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.router_username_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("router_username_input"),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Password
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.router_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("router_password_input"),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Remember Credentials Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.router_remember_creds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = saveInKeystore,
                        onCheckedChange = { saveInKeystore = it }
                    )
                }

                // Error Banner if present
                if (lastError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth().testTag("router_error_banner")
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                                Text(
                                    text = lastError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.connectRouterDemoMode() },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.fillMaxWidth().height(38.dp).testTag("router_error_launch_demo_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.router_demo_mode_button),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connect Button
                Button(
                    onClick = {
                        viewModel.connectRouter(
                            gatewayIp = gatewayIpInput,
                            username = username,
                            password = password,
                            saveCredentials = saveInKeystore
                        )
                    },
                    enabled = !isBusy && gatewayIpInput.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("router_connect_button")
                ) {
                    if (connectionState == RouterConnectionState.CONNECTING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.router_connecting_state))
                    } else if (connectionState == RouterConnectionState.AUTHENTICATING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.router_authenticating_state))
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.router_connect_button),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Demo Mode Shortcut
                OutlinedButton(
                    onClick = { viewModel.connectRouterDemoMode() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("router_demo_mode_shortcut_button")
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.router_demo_mode_button),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Probe Card for Diagnostic / Interface Check
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_probe_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.router_detection),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Probe gateway headers and firmware response prior to authenticating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.probeRouter(gatewayIpInput) },
                    enabled = !isProbing && gatewayIpInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    if (isProbing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.probing_gateway))
                    } else {
                        Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.probe_router))
                    }
                }

                if (routerCap != null) {
                    val cap = routerCap!!
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(12.dp)
                    ) {
                        AppleDetailRow(title = stringResource(R.string.router_model), value = cap.detectedModel)
                        Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                        AppleDetailRow(title = "Protocol", value = cap.protocol)
                    }
                }
            }
        }
    }
}

// =========================================================================
// Tab 1: Overview
// =========================================================================

@Composable
private fun RouterOverviewTab(
    data: RouterDashboardData,
    onRebootClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // WAN Status Card
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_wan_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = AppleBlueLight, modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(R.string.router_wan_status),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    ApplePillBadge(
                        text = data.wanInfo.status,
                        textColor = if (data.wanInfo.status.contains("Connect", ignoreCase = true)) AppleGreenLight else AppleRedLight,
                        containerColor = (if (data.wanInfo.status.contains("Connect", ignoreCase = true)) AppleGreenLight else AppleRedLight).copy(alpha = 0.12f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(title = stringResource(R.string.router_wan_ip), value = data.wanInfo.ip)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = "WAN Gateway", value = data.wanInfo.gateway)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_dns_primary), value = data.wanInfo.primaryDns)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_dns_secondary), value = data.wanInfo.secondaryDns)
                }
            }
        }

        // Hardware & Firmware System Info Card
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_system_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        text = "System & Hardware Specifications",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(title = stringResource(R.string.router_model), value = data.deviceInfo.model)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_firmware), value = data.deviceInfo.firmwareVersion)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_hardware), value = data.deviceInfo.hardwareVersion)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_serial), value = data.deviceInfo.serialNumber)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_uptime), value = data.deviceInfo.formattedUptime)
                }
            }
        }

        // LAN Network Card
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_lan_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lan, contentDescription = null, tint = ApplePurple, modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.router_lan_status),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(title = stringResource(R.string.router_lan_ip), value = data.lanInfo.ip)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = "Subnet Mask", value = data.lanInfo.subnet)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_dhcp_range), value = "${data.lanInfo.dhcpStart} - ${data.lanInfo.dhcpEnd}")
                }
            }
        }

        // Gateway Operations (Reboot)
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    text = "Gateway Maintenance",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRebootClick,
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("router_reboot_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.router_reboot_button), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =========================================================================
// Tab 2: Wi-Fi Management
// =========================================================================

@Composable
private fun RouterWifiTab(
    wifi: com.example.domain.router.model.RouterWifiInfo,
    onEditWifiClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("router_wifi_management_card"),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = AppleBlueLight, modifier = Modifier.size(22.dp))
                        Text(
                            text = stringResource(R.string.router_tab_wifi),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    ApplePillBadge(
                        text = if (wifi.enabled) "Active Radio" else "Radio Disabled",
                        textColor = if (wifi.enabled) AppleGreenLight else AppleRedLight,
                        containerColor = (if (wifi.enabled) AppleGreenLight else AppleRedLight).copy(alpha = 0.12f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(title = stringResource(R.string.router_wifi_ssid), value = wifi.ssid)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_wifi_channel), value = wifi.channel)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = stringResource(R.string.router_wifi_security), value = wifi.securityMode)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(title = "Wi-Fi Standard", value = wifi.standard)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onEditWifiClick,
                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("router_open_edit_wifi_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.router_edit_wifi),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

// =========================================================================
// Tab 3: Connected Devices
// =========================================================================

@Composable
private fun RouterConnectedDevicesTab(
    devices: List<RouterConnectedDevice>
) {
    if (devices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.DevicesOther, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Text(
                    text = "No Connected Clients Detected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Router reported 0 active DHCP/WLAN leases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Clients Connected to HG630 V2 (${devices.size})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(devices) { device ->
                AppleCard(
                    modifier = Modifier.fillMaxWidth().testTag("router_device_card_${device.ip}"),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(12.dp)
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
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (device.connectionType.contains("Wi-Fi", ignoreCase = true)) Icons.Default.Wifi else Icons.Default.Lan,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${device.ip} • ${device.mac}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            ApplePillBadge(
                                text = if (device.isOnline) "Active" else "Offline",
                                textColor = if (device.isOnline) AppleGreenLight else AppleRedLight,
                                containerColor = (if (device.isOnline) AppleGreenLight else AppleRedLight).copy(alpha = 0.12f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = device.connectionType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
