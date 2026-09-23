package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
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
import com.example.model.DiagnosticStatus
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun DiagnosticsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val diagnostics by viewModel.diagnostics.collectAsState()
    val isDiagnosing by viewModel.isDiagnosing.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()

    var targetIpInput by remember { mutableStateOf(networkInfo?.gatewayIp ?: "8.8.8.8") }

    LaunchedEffect(networkInfo) {
        if (targetIpInput.isBlank() && networkInfo?.gatewayIp != null) {
            targetIpInput = networkInfo!!.gatewayIp
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(2.dp))

        // Target Input & Action Card
        AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Text(
                text = "Diagnostics Target",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = targetIpInput,
                onValueChange = { targetIpInput = it },
                label = { Text("Target Host / IP") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnostic_target_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.runDiagnostics(targetIpInput) },
                enabled = !isDiagnosing && targetIpInput.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("run_diagnostics_button")
            ) {
                if (isDiagnosing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Measuring Latency & Throughput…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.run_diagnostics),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Live Network Metrics Section
        AppleSectionHeader(title = "Live Network Telemetry")

        val diag = diagnostics

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Ping Latency Card
            AppleMetricCard(
                title = "Ping Latency",
                value = if (diag?.pingMs != null) "${diag.pingMs} ms" else "—",
                status = when (diag?.status) {
                    DiagnosticStatus.MEASURED -> stringResource(R.string.measured)
                    DiagnosticStatus.TIMED_OUT -> stringResource(R.string.timed_out)
                    DiagnosticStatus.BLOCKED -> stringResource(R.string.blocked)
                    else -> stringResource(R.string.unavailable)
                },
                statusColor = when (diag?.status) {
                    DiagnosticStatus.MEASURED -> AppleGreenLight
                    DiagnosticStatus.TIMED_OUT -> AppleOrangeLight
                    DiagnosticStatus.BLOCKED -> AppleRedLight
                    else -> AppleTextSecondaryLight
                },
                modifier = Modifier.weight(1f)
            )

            // Jitter Card
            AppleMetricCard(
                title = "Jitter",
                value = if (diag?.jitterMs != null) "${diag.jitterMs} ms" else "—",
                status = if (diag?.jitterMs != null) stringResource(R.string.measured) else stringResource(R.string.unavailable),
                statusColor = if (diag?.jitterMs != null) AppleGreenLight else AppleTextSecondaryLight,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Packet Loss Card
            AppleMetricCard(
                title = "Packet Loss",
                value = if (diag?.packetLossPercent != null) "${diag.packetLossPercent}%" else "—",
                status = if (diag?.packetLossPercent != null) stringResource(R.string.measured) else stringResource(R.string.unavailable),
                statusColor = if (diag?.packetLossPercent != null && diag.packetLossPercent == 0.0) AppleGreenLight
                else if (diag?.packetLossPercent != null) AppleRedLight else AppleTextSecondaryLight,
                modifier = Modifier.weight(1f)
            )

            // DNS Latency Card
            AppleMetricCard(
                title = "DNS Benchmark",
                value = if (diag?.dnsLatencyMs != null) "${diag.dnsLatencyMs} ms" else "—",
                status = if (diag?.dnsLatencyMs != null) stringResource(R.string.measured) else stringResource(R.string.unavailable),
                statusColor = if (diag?.dnsLatencyMs != null) AppleGreenLight else AppleTextSecondaryLight,
                modifier = Modifier.weight(1f)
            )
        }

        // Speed Test Card
        AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cloudflare Throughput Speed",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                ApplePillBadge(
                    text = if (diag?.downloadSpeedMbps != null) stringResource(R.string.measured) else "Requires WAN",
                    textColor = if (diag?.downloadSpeedMbps != null) AppleGreenLight else AppleOrangeLight,
                    containerColor = (if (diag?.downloadSpeedMbps != null) AppleGreenLight else AppleOrangeLight).copy(alpha = 0.12f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (diag?.downloadSpeedMbps != null) "${diag.downloadSpeedMbps} Mbps" else stringResource(R.string.unavailable),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Upload speed: Unsupported — Requires authenticated server socket endpoint.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Section: Defensive ARP & Gateway Diagnostics
        AppleSectionHeader(title = "Defensive ARP & Gateway Audit")

        val arpReport by viewModel.arpIntegrityReport.collectAsState()

        AppleCard(
            modifier = Modifier.fillMaxWidth().testTag("gateway_arp_diagnostic_card"),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gateway Identity & ARP Cache",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Verifies router MAC mapping & detects cache anomalies",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { viewModel.auditGatewayIntegrity() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("btn_verify_gateway_arp")
                ) {
                    Text("Verify Gateway", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }

            if (arpReport != null) {
                val report = arpReport!!
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (report.isConsistent) AppleGreenLight.copy(alpha = 0.08f)
                            else AppleRedLight.copy(alpha = 0.08f)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (report.isConsistent) "Status: Consistent & Verified" else "Status: Anomalies Detected",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (report.isConsistent) AppleGreenLight else AppleRedLight
                        )
                        ApplePillBadge(
                            text = "${report.entriesCount} Entries",
                            textColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }

                    Text(
                        text = report.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Gateway: ${report.gatewayIp} • MAC: ${report.gatewayMac ?: "Unresolved"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AppleMetricCard(
    title: String,
    value: String,
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    AppleCard(
        modifier = modifier,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        ApplePillBadge(
            text = status,
            textColor = statusColor,
            containerColor = statusColor.copy(alpha = 0.12f)
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    status: String,
    modifier: Modifier = Modifier
) {
    AppleMetricCard(
        title = title,
        value = value,
        status = status,
        statusColor = AppleBlueLight,
        modifier = modifier
    )
}
