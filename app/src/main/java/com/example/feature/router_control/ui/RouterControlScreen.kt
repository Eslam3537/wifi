package com.example.feature.router_control.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterUiState
import com.example.ui.components.AppUpdateDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterControlScreen(
    onOpenClassicConfig: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: RouterControlViewModel = run {
        val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        viewModel(factory = RouterControlViewModel.provideFactory(context))
    }
) {
    val uiState by viewModel.uiState.collectAsState()
    val credsInput by viewModel.credentialsInput.collectAsState()
    val showPassword by viewModel.showPassword.collectAsState()
    val feedbackMsg by viewModel.userFeedbackMessage.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()

    var showRestartDialog by remember { mutableStateOf(false) }
    var showChangeWifiDialog by remember { mutableStateOf(false) }
    var newWifiPassInput by remember { mutableStateOf("") }
    var newSsidInput by remember { mutableStateOf("") }

    // Auto-dismiss user feedback after 4 seconds
    LaunchedEffect(feedbackMsg) {
        if (feedbackMsg != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearFeedbackMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Router Control banner
            item {
                RouterControlHeader(
                    onCheckUpdates = { viewModel.checkForAppUpdates() },
                    onOpenClassicConfig = onOpenClassicConfig
                )
            }

            // User Feedback or Update Status Banner
            if (feedbackMsg != null) {
                item {
                    FeedbackBanner(message = feedbackMsg!!)
                }
            }

            when (val state = uiState) {
                is RouterUiState.Idle -> {
                    item {
                        CredentialsInputCard(
                            gatewayIp = credsInput.gatewayIp,
                            username = credsInput.username,
                            password = credsInput.password,
                            remember = credsInput.remember,
                            showPassword = showPassword,
                            onIpChange = { viewModel.updateGatewayIp(it) },
                            onUserChange = { viewModel.updateUsername(it) },
                            onPassChange = { viewModel.updatePassword(it) },
                            onRememberChange = { viewModel.toggleRemember(it) },
                            onToggleShowPass = { viewModel.toggleShowPassword() },
                            onLogin = { viewModel.login() }
                        )
                    }
                    item {
                        SupportedVendorsCard()
                    }
                }

                is RouterUiState.Loading -> {
                    item {
                        LoadingCard(message = state.message)
                    }
                }

                is RouterUiState.Error -> {
                    item {
                        ErrorCard(
                            message = state.message,
                            isWifiDisconnected = state.isWifiDisconnected,
                            onRetry = { viewModel.login() }
                        )
                    }
                    item {
                        CredentialsInputCard(
                            gatewayIp = credsInput.gatewayIp,
                            username = credsInput.username,
                            password = credsInput.password,
                            remember = credsInput.remember,
                            showPassword = showPassword,
                            onIpChange = { viewModel.updateGatewayIp(it) },
                            onUserChange = { viewModel.updateUsername(it) },
                            onPassChange = { viewModel.updatePassword(it) },
                            onRememberChange = { viewModel.toggleRemember(it) },
                            onToggleShowPass = { viewModel.toggleShowPassword() },
                            onLogin = { viewModel.login() }
                        )
                    }
                }

                is RouterUiState.Connected -> {
                    // Status Overview Card
                    item {
                        ConnectedStatusCard(
                            status = state.status,
                            onLogout = { viewModel.logout() }
                        )
                    }

                    // Action Controls
                    item {
                        ActionControlsRow(
                            isBusy = state.isPerformingAction,
                            onRestart = { showRestartDialog = true },
                            onChangeWifi = {
                                newSsidInput = state.status.wifiSsid
                                newWifiPassInput = ""
                                showChangeWifiDialog = true
                            },
                            onRefresh = { viewModel.refreshData() },
                            onCheckUpdates = { viewModel.checkForAppUpdates() }
                        )
                    }

                    // Connected Devices Header
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "الأجهزة المتصلة بالراوتر (${state.devices.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    if (state.devices.isEmpty()) {
                        item {
                            EmptyDevicesCard()
                        }
                    } else {
                        items(state.devices, key = { it.mac }) { device ->
                            ConnectedDeviceItemCard(
                                device = device,
                                isBusy = state.isPerformingAction,
                                onToggleBlock = { viewModel.toggleDeviceBlock(device) }
                            )
                        }
                    }
                }
            }
        }

        // Restart Router Confirmation Dialog
        if (showRestartDialog) {
            AlertDialog(
                onDismissRequest = { showRestartDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "إعادة تشغيل الراوتر",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("هل أنت متأكد من رغبتك في إعادة تشغيل الراوتر الآن؟ سينقطع اتصال الإنترنت لجميع الأجهزة لمدة دقيقة تقريبًا حتى يكتمل الإقلاع.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRestartDialog = false
                            viewModel.restartRouter()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("نعم، إعادة التشغيل")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestartDialog = false }) {
                        Text("إلغاء")
                    }
                }
            )
        }

        // Change Wi-Fi Password Dialog
        if (showChangeWifiDialog) {
            AlertDialog(
                onDismissRequest = { showChangeWifiDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.LockReset,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "تغيير باسورد الواي فاي",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "أدخل اسم الشبكة وكلمة المرور الجديدة لشبكة الواي فاي للراوتر:",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OutlinedTextField(
                            value = newSsidInput,
                            onValueChange = { newSsidInput = it },
                            label = { Text("اسم الشبكة (SSID)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = newWifiPassInput,
                            onValueChange = { newWifiPassInput = it },
                            label = { Text("كلمة المرور الجديدة (8 أحرف على الأقل)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newWifiPassInput.length >= 8) {
                                showChangeWifiDialog = false
                                viewModel.changeWifiPassword(
                                    newPassword = newWifiPassInput,
                                    newSsid = newSsidInput.ifBlank { null }
                                )
                            }
                        },
                        enabled = newWifiPassInput.length >= 8
                    ) {
                        Text("حفظ التغييرات")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showChangeWifiDialog = false }) {
                        Text("إلغاء")
                    }
                }
            )
        }

        // In-App Update Dialog integration
        AppUpdateDialog(
            updateState = appUpdateState,
            onDownload = { url -> viewModel.downloadAppUpdate(url) },
            onInstall = { file -> viewModel.installAppUpdate(file) },
            onOpenPermissionSettings = { viewModel.openInstallPermissionSettings() },
            canRequestInstall = viewModel.canRequestPackageInstalls(),
            onDismiss = { viewModel.dismissAppUpdateDialog() }
        )
    }
}

@Composable
private fun RouterControlHeader(
    onCheckUpdates: () -> Unit,
    onOpenClassicConfig: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = "التحكم في الراوتر (Router Chef)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "إدارة إعدادات الراوتر والأجهزة دون فتح المتصفح",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Quick App Update check button in header
                IconButton(
                    onClick = onCheckUpdates,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "التحقق من تحديث التطبيق",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (onOpenClassicConfig != null) {
                    IconButton(
                        onClick = onOpenClassicConfig,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "اللوحة المتقدمة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CredentialsInputCard(
    gatewayIp: String,
    username: String,
    password: String,
    remember: Boolean,
    showPassword: Boolean,
    onIpChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPassChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onToggleShowPass: () -> Unit,
    onLogin: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "بيانات الدخول إلى صفحة الراوتر",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = gatewayIp,
                onValueChange = onIpChange,
                label = { Text("عنوان IP الراوتر (Gateway IP)") },
                placeholder = { Text("192.168.1.1 أو 192.168.0.1") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("router_ip_input"),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUserChange,
                label = { Text("اسم المستخدم (Username)") },
                placeholder = { Text("admin") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("router_user_input"),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPassChange,
                label = { Text("كلمة المرور (Password)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    IconButton(onClick = onToggleShowPass) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("router_pass_input"),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = remember,
                        onCheckedChange = onRememberChange,
                        modifier = Modifier.testTag("router_remember_checkbox")
                    )
                    Text(
                        text = "حفظ البيانات بشكل مشفر وآمن",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "AES-256",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("router_login_btn"),
                shape = RoundedCornerShape(14.dp),
                enabled = gatewayIp.isNotBlank() && username.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.Login,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تسجيل الدخول والتحكم بالراوتر",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun SupportedVendorsCard() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "الراوترات المدعومة تلقائيًا (Auto-Detect):",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VendorChip(name = "TP-Link", icon = Icons.Default.Wifi)
                VendorChip(name = "Huawei", icon = Icons.Default.Router)
                VendorChip(name = "ZTE", icon = Icons.Default.Sensors)
                VendorChip(name = "MikroTik", icon = Icons.Default.Hub)
            }

            Text(
                text = "يقوم التطبيق بالكشف الذكي عن نوع صفحة إدارة الراوتر واستخدام الـ Strategy المناسب لها تلقائياً.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VendorChip(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text = name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    isWifiDisconnected: Boolean,
    onRetry: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isWifiDisconnected) Icons.Default.WifiOff else Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (isWifiDisconnected) "تنبيه الاتصال بالواي فاي" else "فشل الاتصال بالراوتر",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إعادة المحاولة")
            }
        }
    }
}

@Composable
private fun ConnectedStatusCard(
    status: RouterStatusInfo,
    onLogout: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                    Text(
                        text = "متصل بالراوتر بنجاح",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF34C759)
                    )
                }

                TextButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("خروج", style = MaterialTheme.typography.labelMedium)
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Info Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMetricItem(
                    label = "نوع الراوتر",
                    value = status.vendor.displayName,
                    icon = Icons.Default.Router
                )
                StatusMetricItem(
                    label = "شبكة الواي فاي (SSID)",
                    value = status.wifiSsid,
                    icon = Icons.Default.Wifi
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMetricItem(
                    label = "عنوان IP الراوتر",
                    value = status.gatewayIp,
                    icon = Icons.Default.Dns
                )
                StatusMetricItem(
                    label = "الأجهزة المتصلة",
                    value = "${status.connectedDevicesCount} أجهزة",
                    icon = Icons.Default.Devices
                )
            }
        }
    }
}

@Composable
private fun StatusMetricItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(max = 160.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActionControlsRow(
    isBusy: Boolean,
    onRestart: () -> Unit,
    onChangeWifi: () -> Unit,
    onRefresh: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Restart Router button
            Button(
                onClick = onRestart,
                enabled = !isBusy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إعادة تشغيل", fontWeight = FontWeight.Bold)
            }

            // Change Wi-Fi Password button
            Button(
                onClick = onChangeWifi,
                enabled = !isBusy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تغيير الباسورد", fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Check App Updates button
            OutlinedButton(
                onClick = onCheckUpdates,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("التحقق من تحديث التطبيق", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // Refresh data button
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديث البيانات", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceItemCard(
    device: RouterConnectedDevice,
    isBusy: Boolean,
    onToggleBlock: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isBlocked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (device.isBlocked) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.isBlocked) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (device.isBlocked) Icons.Default.Block else Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = if (device.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = device.hostname.ifBlank { "جهاز متصل" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (device.isBlocked) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = "محظور",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "IP: ${device.ip}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "MAC: ${device.mac}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Block / Unblock button
            Button(
                onClick = onToggleBlock,
                enabled = !isBusy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (device.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (device.isBlocked) "إلغاء الحظر" else "حظر الجهاز",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DevicesOther,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "لا توجد أجهزة معروضة حاليًا",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "اضغط على 'تحديث البيانات' لإعادة فحص الأجهزة المتصلة بالراوتر.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
