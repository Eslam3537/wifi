package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
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
import com.example.model.AlertSeverity
import com.example.model.SecurityAlert
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SecurityScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val alerts by viewModel.securityAlerts.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()
    val arpReport by viewModel.arpIntegrityReport.collectAsState()
    val arpEvents by viewModel.arpAnomalyEvents.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        // Gateway & DNS Watcher Card
        item {
            AppleCard(
                modifier = Modifier.fillMaxWidth().testTag("security_monitor_header"),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppleGreenLight.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = AppleGreenLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Active Network Security Watcher",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Continuous ARP Cache & Gateway Tampering Inspection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.runArpAudit() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_run_arp_audit")
                    ) {
                        Text("Audit ARP", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    AppleDetailRow(
                        title = "Monitored Gateway",
                        value = networkInfo?.gatewayIp ?: "None"
                    )
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(
                        title = "Active DNS Servers",
                        value = networkInfo?.dnsServers?.joinToString(", ") ?: "Default"
                    )
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    AppleDetailRow(
                        title = "ARP Table Inspection",
                        value = "Real neighbor table verification (Zero spoofing)"
                    )
                }
            }
        }

        // Real ARP Integrity Report Card (if run)
        if (arpReport != null) {
            val report = arpReport!!
            item {
                AppleCard(
                    modifier = Modifier.fillMaxWidth().testTag("arp_integrity_card"),
                    backgroundColor = if (report.isConsistent) AppleGreenLight.copy(alpha = 0.08f) else AppleRedLight.copy(alpha = 0.08f),
                    borderColor = if (report.isConsistent) AppleGreenLight.copy(alpha = 0.3f) else AppleRedLight.copy(alpha = 0.3f),
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ARP Cache Integrity Audit",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            ApplePillBadge(
                                text = if (report.isConsistent) "HEALTHY" else "ANOMALIES DETECTED",
                                textColor = if (report.isConsistent) AppleGreenLight else AppleRedLight,
                                containerColor = (if (report.isConsistent) AppleGreenLight else AppleRedLight).copy(alpha = 0.15f)
                            )
                        }

                        Text(
                            text = report.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Gateway: ${report.gatewayIp} (${report.gatewayMac ?: "Unresolved"}) | Verified Entries: ${report.entriesCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (report.conflictsDetected.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                report.conflictsDetected.forEach { conflict ->
                                    Text(
                                        text = "• $conflict",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = AppleRedLight
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section Header with Clear button
        item {
            AppleSectionHeader(
                title = "${stringResource(R.string.security_alerts)} (${alerts.size})",
                trailing = {
                    if (alerts.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearAlerts() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Alerts",
                                tint = AppleRedLight,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )
        }

        if (alerts.isEmpty()) {
            item {
                AppleCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(28.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AppleGreenLight.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AppleGreenLight,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.no_threats),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "No ARP spoofing or DNS poisoning anomalies detected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(alerts, key = { it.id }) { alert ->
                AppleAlertItemCard(alert = alert)
            }
        }
    }
}

@Composable
fun AppleAlertItemCard(alert: SecurityAlert) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val (severityColor, containerBg) = when (alert.severity) {
        AlertSeverity.CRITICAL -> AppleRedLight to AppleRedLight.copy(alpha = 0.08f)
        AlertSeverity.WARNING -> AppleOrangeLight to AppleOrangeLight.copy(alpha = 0.08f)
        AlertSeverity.INFO -> AppleBlueLight to AppleBlueLight.copy(alpha = 0.08f)
    }

    AppleCard(
        modifier = Modifier.fillMaxWidth().testTag("security_alert_item"),
        backgroundColor = containerBg,
        borderColor = severityColor.copy(alpha = 0.25f),
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = severityColor
                    )
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = dateFormat.format(Date(alert.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "${stringResource(R.string.evidence)}: ${alert.evidence}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ApplePillBadge(
                    text = "${stringResource(R.string.confidence)}: ${alert.confidence.name}",
                    textColor = severityColor,
                    containerColor = severityColor.copy(alpha = 0.12f)
                )
                ApplePillBadge(
                    text = "Severity: ${alert.severity.name}",
                    textColor = severityColor,
                    containerColor = severityColor.copy(alpha = 0.12f)
                )
            }

            Text(
                text = "${stringResource(R.string.recommended_action)}: ${alert.recommendedAction}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AlertItemCard(alert: SecurityAlert) {
    AppleAlertItemCard(alert = alert)
}
