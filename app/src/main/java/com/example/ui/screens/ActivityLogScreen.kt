package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.ActivityLogEntity
import com.example.model.CapabilityState
import com.example.model.RootStatus
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class LogFilterType {
    ALL,
    ERRORS,
    ACCESS,
    TRAFFIC,
    SYSTEM
}

@Composable
fun ActivityLogScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.logs.collectAsState(initial = emptyList())
    val rootStatus by viewModel.rootStatus.collectAsState()
    val kernelSuStatus by viewModel.kernelSuStatus.collectAsState()
    val capabilityAudit by viewModel.capabilityAuditResult.collectAsState()
    val routerCap by viewModel.routerCapability.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedFilter by remember { mutableStateOf(LogFilterType.ALL) }
    var showDiagnosticsSummary by remember { mutableStateOf(true) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // Count statistics
    val errorCount = remember(logs) { logs.count { it.level == "ERROR" || it.level == "WARN" } }
    val accessCount = remember(logs) { logs.count { it.component.contains("Access", ignoreCase = true) || it.component.contains("Firewall", ignoreCase = true) } }
    val trafficCount = remember(logs) { logs.count { it.component.contains("Traffic", ignoreCase = true) } }
    val systemCount = remember(logs) { logs.count { it.component.contains("System", ignoreCase = true) || it.component.contains("Capability", ignoreCase = true) || it.component.contains("Discovery", ignoreCase = true) } }

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            LogFilterType.ALL -> logs
            LogFilterType.ERRORS -> logs.filter { it.level == "ERROR" || it.level == "WARN" }
            LogFilterType.ACCESS -> logs.filter { it.component.contains("Access", ignoreCase = true) || it.component.contains("Firewall", ignoreCase = true) }
            LogFilterType.TRAFFIC -> logs.filter { it.component.contains("Traffic", ignoreCase = true) }
            LogFilterType.SYSTEM -> logs.filter { it.component.contains("System", ignoreCase = true) || it.component.contains("Capability", ignoreCase = true) || it.component.contains("Discovery", ignoreCase = true) || it.component.contains("Router", ignoreCase = true) }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.clear_logs)) },
            text = { Text("Are you sure you want to clear all activity and error logs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(stringResource(R.string.clear_logs), color = AppleRedLight)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.smart_exit_cancel_title))
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        // Master Header & Quick Actions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppleSectionHeader(
                    title = stringResource(R.string.logs_system_diagnostics) + " (${logs.size})",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (logs.isNotEmpty()) {
                                IconButton(
                                    onClick = { showClearConfirmDialog = true },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.clear_logs),
                                        tint = AppleRedLight,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.logs_diagnostics_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Primary "Copy All Logs & Diagnostics" Action Card
        item {
            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("copy_all_diagnostics_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = stringResource(R.string.copy_all_diagnostics),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${logs.size} logs • $errorCount recorded issues/limits",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (errorCount > 0) AppleOrangeLight else AppleGreenLight
                                )
                            }
                        }

                        if (errorCount > 0) {
                            ApplePillBadge(
                                text = "$errorCount Issue(s)",
                                textColor = AppleRedLight,
                                containerColor = AppleRedLight.copy(alpha = 0.12f)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.features_restricted_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val report = viewModel.buildDiagnosticReport(logs)
                                clipboardManager.setText(AnnotatedString(report))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.all_diagnostics_copied_toast),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(44.dp)
                                .testTag("copy_all_diagnostics_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.copy_all_diagnostics),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val report = viewModel.buildDiagnosticReport(logs)
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, report)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(
                                    sendIntent,
                                    context.getString(R.string.share_diagnostics)
                                )
                                context.startActivity(shareIntent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("share_diagnostics_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.share_diagnostics),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Feature Limitations & Diagnostics Card (Why features might not work)
        item {
            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("feature_limitations_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDiagnosticsSummary = !showDiagnosticsSummary },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.feature_status_summary),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Icon(
                            imageVector = if (showDiagnosticsSummary) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = showDiagnosticsSummary) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Divider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )

                            // 1. Root & Kernel Privileges
                            FeatureStatusRow(
                                title = "Root / KernelSU",
                                statusText = if (rootStatus == RootStatus.ROOT_AVAILABLE) "Available" else "Not Available",
                                isSuccess = rootStatus == RootStatus.ROOT_AVAILABLE,
                                explanation = if (rootStatus == RootStatus.ROOT_AVAILABLE) {
                                    "Verified superuser execution active."
                                } else {
                                    "Safe mode active: Hardware MAC inspection & raw packet interception require Root or KernelSU."
                                },
                                onCopyReason = {
                                    val r = "Root / KernelSU: Not Available. Hardware MAC inspection & raw packet interception require Root or KernelSU."
                                    clipboardManager.setText(AnnotatedString(r))
                                    Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
                                }
                            )

                            // 2. Client-side Remote Blocking
                            val blockCap = capabilityAudit?.capabilities?.get("REMOTE_CLIENT_BLOCKING")
                            val isBlockSupported = blockCap?.status == CapabilityState.SUPPORTED
                            FeatureStatusRow(
                                title = "Device Blocking",
                                statusText = if (isBlockSupported) "Supported" else "Restricted",
                                isSuccess = isBlockSupported,
                                explanation = blockCap?.reason ?: "Normal Wi-Fi Client mode: Local firewall intercepts phone traffic; blocking other devices directly requires Router API credentials or Hotspot mode.",
                                onCopyReason = {
                                    val r = "Device Blocking: ${blockCap?.reason ?: "Normal Wi-Fi Client mode: Local firewall intercepts phone traffic; blocking other devices directly requires Router API credentials or Hotspot mode."}"
                                    clipboardManager.setText(AnnotatedString(r))
                                    Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
                                }
                            )

                            // 3. Bandwidth / Traffic Control
                            val tcCap = capabilityAudit?.capabilities?.get("BANDWIDTH_CONTROL")
                            val isTcSupported = tcCap?.status == CapabilityState.SUPPORTED
                            FeatureStatusRow(
                                title = "Bandwidth Limiting",
                                statusText = if (isTcSupported) "Supported" else "Restricted",
                                isSuccess = isTcSupported,
                                explanation = tcCap?.reason ?: "Requires Linux kernel tc (traffic control) subsystem and root privileges.",
                                onCopyReason = {
                                    val r = "Bandwidth Limiting: ${tcCap?.reason ?: "Requires Linux kernel tc (traffic control) subsystem and root privileges."}"
                                    clipboardManager.setText(AnnotatedString(r))
                                    Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
                                }
                            )

                            // 4. Router API
                            val isRouterSupported = routerCap?.isSupported == true
                            FeatureStatusRow(
                                title = "Router Management API",
                                statusText = if (isRouterSupported) "Connected" else "Not Probed / Unsupported",
                                isSuccess = isRouterSupported,
                                explanation = if (isRouterSupported) {
                                    "Connected to ${routerCap?.detectedModel} at ${routerCap?.endpoint}."
                                } else {
                                    routerCap?.unsupportedReasons?.firstOrNull() ?: "Gateway web interface does not expose supported admin API. Tap Probe in Router tab."
                                },
                                onCopyReason = {
                                    val r = "Router API: ${routerCap?.unsupportedReasons?.firstOrNull() ?: "Gateway web interface does not expose supported admin API."}"
                                    clipboardManager.setText(AnnotatedString(r))
                                    Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Filter Chips Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterPillChip(
                    text = "${stringResource(R.string.filter_all)} (${logs.size})",
                    selected = selectedFilter == LogFilterType.ALL,
                    onClick = { selectedFilter = LogFilterType.ALL }
                )

                FilterPillChip(
                    text = "${stringResource(R.string.filter_errors)} ($errorCount)",
                    selected = selectedFilter == LogFilterType.ERRORS,
                    badgeColor = if (errorCount > 0) AppleRedLight else null,
                    onClick = { selectedFilter = LogFilterType.ERRORS }
                )

                FilterPillChip(
                    text = "${stringResource(R.string.filter_access)} ($accessCount)",
                    selected = selectedFilter == LogFilterType.ACCESS,
                    onClick = { selectedFilter = LogFilterType.ACCESS }
                )

                FilterPillChip(
                    text = "${stringResource(R.string.filter_traffic)} ($trafficCount)",
                    selected = selectedFilter == LogFilterType.TRAFFIC,
                    onClick = { selectedFilter = LogFilterType.TRAFFIC }
                )

                FilterPillChip(
                    text = "${stringResource(R.string.filter_system)} ($systemCount)",
                    selected = selectedFilter == LogFilterType.SYSTEM,
                    onClick = { selectedFilter = LogFilterType.SYSTEM }
                )
            }
        }

        // Logs List
        if (filteredLogs.isEmpty()) {
            item {
                AppleCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppleGreenLight,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (selectedFilter == LogFilterType.ERRORS) {
                                "No errors or warnings recorded! System operating normally."
                            } else {
                                stringResource(R.string.no_logs_recorded)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                AppleLogItemCard(log = log)
            }
        }

        // Official Developer Information Card (Mandatory as per Project Instructions)
        item {
            Spacer(modifier = Modifier.height(12.dp))
            AppleSectionHeader(title = "About & Developer")

            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("developer_info_card"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Developer Information",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "إسلام رمضان ربيع",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.facebook.com/share/1CeNDG6hML/")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            "Facebook",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://wa.me/qr/K7C6TJZJIS72H1")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleGreenLight),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            "WhatsApp",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Eslam3537")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            "GitHub",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureStatusRow(
    title: String,
    statusText: String,
    isSuccess: Boolean,
    explanation: String,
    onCopyReason: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            ApplePillBadge(
                text = statusText,
                textColor = if (isSuccess) AppleGreenLight else AppleOrangeLight,
                containerColor = (if (isSuccess) AppleGreenLight else AppleOrangeLight).copy(alpha = 0.12f)
            )
        }

        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onCopyReason,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.copy_reason),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun FilterPillChip(
    text: String,
    selected: Boolean,
    badgeColor: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (badgeColor != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
fun AppleLogItemCard(log: ActivityLogEntity) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    val isErrorOrWarn = log.level == "ERROR" || log.level == "WARN"
    val hasExplicitError = !log.errorDetails.isNullOrBlank()

    val (badgeText, badgeColor) = when (log.level) {
        "ERROR" -> "ERROR" to AppleRedLight
        "WARN" -> "WARN" to AppleOrangeLight
        else -> log.level to AppleBlueLight
    }

    AppleCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log_item_${log.id}"),
        cornerRadius = 14.dp,
        borderColor = if (isErrorOrWarn) badgeColor.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header Row: Badge, Component, Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplePillBadge(
                        text = badgeText,
                        textColor = badgeColor,
                        containerColor = badgeColor.copy(alpha = 0.12f)
                    )
                    Text(
                        text = log.component,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Operation
            Text(
                text = log.operation,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Result
            Text(
                text = log.result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Reason / Error Details Box (Highlighting why feature failed or didn't work)
            if (hasExplicitError || isErrorOrWarn) {
                val reasonText = log.errorDetails ?: log.result
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.08f))
                        .border(0.5.dp, badgeColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (log.level == "ERROR") Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.cause_reason_label),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = badgeColor
                            )
                        }

                        // "Copy Reason" Button ("نسخ السبب")
                        Surface(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(reasonText))
                                Toast.makeText(
                                    context,
                                    "${context.getString(R.string.copied_toast)}: $reasonText",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(R.string.copy_reason),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = badgeColor
                                )
                            }
                        }
                    }

                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Bottom Actions: Copy Entire Log
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val timeStr = dateFormat.format(Date(log.timestamp))
                        val logText = "[$timeStr] [${log.level}] [${log.component}] ${log.operation}\nResult: ${log.result}" +
                                if (!log.errorDetails.isNullOrBlank()) "\nReason / Error: ${log.errorDetails}" else ""
                        clipboardManager.setText(AnnotatedString(logText))
                        Toast.makeText(
                            context,
                            context.getString(R.string.copied_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.copy_log),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun LogItemCard(log: ActivityLogEntity) {
    AppleLogItemCard(log = log)
}
