package com.example.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.theme.*

enum class AppNavDestination(val titleRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.nav_dashboard, Icons.Default.Dashboard),
    DEVICES(R.string.nav_devices, Icons.Default.Devices),
    DIAGNOSTICS(R.string.nav_diagnostics, Icons.Default.Speed),
    SECURITY(R.string.nav_security, Icons.Default.Shield),
    ROUTER(R.string.nav_router, Icons.Default.Router),
    COMPATIBILITY(R.string.nav_compatibility, Icons.Default.CheckCircleOutline),
    LOGS(R.string.nav_logs, Icons.Default.FormatListBulleted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit = {}
) {
    var currentDestination by remember { mutableStateOf(AppNavDestination.DASHBOARD) }
    val networkInfo by viewModel.networkInfo.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentThemeMode by viewModel.currentThemeMode.collectAsState()
    val showSmartExitDialog by viewModel.showSmartExitDialog.collectAsState()
    val activeRules by viewModel.activeEnforcedRules.collectAsState()
    val isBgProtectionEnabled by viewModel.isBackgroundProtectionEnabled.collectAsState()
    val baseContext = LocalContext.current

    val activity = remember(baseContext) {
        var ctx = baseContext
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return@remember ctx
            ctx = ctx.baseContext
        }
        null
    }

    // Intercept Back Press to show Smart Exit confirmation if rules are active
    BackHandler(enabled = true) {
        if (currentDestination != AppNavDestination.DASHBOARD) {
            currentDestination = AppNavDestination.DASHBOARD
        } else {
            if (viewModel.hasActiveRestrictions()) {
                viewModel.openSmartExitDialog()
            } else {
                activity?.finish()
            }
        }
    }

    val localizedContext = remember(currentLanguage, baseContext) {
        LanguageManager.applyLocale(baseContext, currentLanguage)
    }
    val localizedConfig = remember(currentLanguage, localizedContext) {
        localizedContext.resources.configuration
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        LocalLayoutDirection provides currentLanguage.layoutDirection
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 0.5.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        // Top App Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Logo icon capsule
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.2).sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AppleStatusDot(
                                            color = if (networkInfo != null) AppleGreenLight else AppleOrangeLight,
                                            size = 6.dp
                                        )
                                        Text(
                                            text = if (networkInfo != null) "${networkInfo?.interfaceName?.uppercase()} ${stringResource(R.string.status_connected)}" else stringResource(R.string.network_standby),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Active Protection Badge (if active)
                                if (activeRules.isNotEmpty() || isBgProtectionEnabled) {
                                    Surface(
                                        onClick = { viewModel.openSmartExitDialog() },
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                                        ),
                                        modifier = Modifier.testTag("protection_active_badge")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            AppleStatusDot(color = MaterialTheme.colorScheme.error, size = 6.dp)
                                            Icon(
                                                imageVector = Icons.Default.Shield,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "${activeRules.size}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                // Theme Switcher Pill Button
                                Surface(
                                    onClick = { viewModel.toggleNextThemeMode() },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                    modifier = Modifier.testTag("theme_toggle_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        AppleThemeIcon(
                                            mode = currentThemeMode,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            size = 15.dp
                                        )
                                        val themeText = when (currentThemeMode) {
                                            AppThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                            AppThemeMode.DARK -> stringResource(R.string.theme_dark)
                                            AppThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        }
                                        Text(
                                            text = themeText,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }

                                // Language Switcher Pill Button
                                Surface(
                                    onClick = {
                                        val nextLang = if (currentLanguage == AppLanguage.ENGLISH) AppLanguage.ARABIC else AppLanguage.ENGLISH
                                        viewModel.setLanguage(nextLang)
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.testTag("language_toggle_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = stringResource(R.string.select_language),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = if (currentLanguage == AppLanguage.ENGLISH) "العربية" else "English",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Refresh Icon Button
                                IconButton(
                                    onClick = { viewModel.refreshNetworkInfo() },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh Network Info",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Hairline bottom separator
                        Divider(
                            modifier = Modifier.fillMaxWidth(),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentDestination) {
                    AppNavDestination.DASHBOARD -> DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToDevices = { currentDestination = AppNavDestination.DEVICES },
                        onNavigateToDiagnostics = { currentDestination = AppNavDestination.DIAGNOSTICS },
                        onRequestPermissions = onRequestPermissions
                    )
                    AppNavDestination.DEVICES -> DeviceListScreen(viewModel = viewModel)
                    AppNavDestination.DIAGNOSTICS -> DiagnosticsScreen(viewModel = viewModel)
                    AppNavDestination.SECURITY -> SecurityScreen(viewModel = viewModel)
                    AppNavDestination.ROUTER -> RouterConfigScreen(viewModel = viewModel)
                    AppNavDestination.COMPATIBILITY -> CompatibilityScreen(viewModel = viewModel)
                    AppNavDestination.LOGS -> ActivityLogScreen(viewModel = viewModel)
                }

                FloatingLiquidGlassNavBar(
                    currentDestination = currentDestination,
                    onDestinationSelected = { currentDestination = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Smart Exit Dialog
            if (showSmartExitDialog) {
                SmartExitDialog(
                    activeRules = activeRules,
                    onExitAndRestore = {
                        viewModel.exitAndRestore(activity)
                    },
                    onExitAndKeepActive = {
                        viewModel.exitAndKeepProtection(activity)
                    },
                    onDismiss = {
                        viewModel.dismissSmartExitDialog()
                    }
                )
            }
        }
    }
}

@Composable
fun FloatingLiquidGlassNavBar(
    currentDestination: AppNavDestination,
    onDestinationSelected: (AppNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()

    // Smart Scrolling: Auto-scroll so selected destination is brought into view smoothly
    LaunchedEffect(currentDestination) {
        lazyListState.animateScrollToItem(
            index = currentDestination.ordinal
        )
    }

    val glassShape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = glassShape,
                    spotColor = Color.Black.copy(alpha = 0.22f),
                    ambientColor = Color.Black.copy(alpha = 0.12f)
                )
                .clip(glassShape)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.15f)
                        )
                    ),
                    shape = glassShape
                ),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 8.dp
        ) {
            LazyRow(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("main_nav_tabs"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(AppNavDestination.entries) { dest ->
                    val isSelected = currentDestination == dest

                    val animBgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                        animationSpec = tween(220)
                    )

                    val animContentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(220)
                    )

                    Surface(
                        onClick = { onDestinationSelected(dest) },
                        shape = RoundedCornerShape(22.dp),
                        color = animBgColor,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("nav_item_${dest.name.lowercase()}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = stringResource(dest.titleRes),
                                tint = animContentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(dest.titleRes),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = animContentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
