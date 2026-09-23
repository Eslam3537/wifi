package com.example.domain.protection

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.repository.DeviceRepository
import com.example.domain.router.HuaweiRouterController
import kotlinx.coroutines.*

class NetworkProtectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var ruleStateManager: RuleStateManager
    private lateinit var deviceRepository: DeviceRepository
    private val routerController = HuaweiRouterController()

    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ruleStateManager = RuleStateManager(applicationContext)
        val db = AppDatabase.getInstance(applicationContext)
        deviceRepository = DeviceRepository(db.deviceDao())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP_AND_RESTORE -> {
                serviceScope.launch {
                    ruleStateManager.rollbackAllAppRules(deviceRepository, routerController)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_REFRESH -> {
                ruleStateManager.setBackgroundProtectionEnabled(true)
                val notification = buildNotification("Active Network Protection", "Enforcing background rules...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startHealthMonitor()
            }
        }

        return START_STICKY
    }

    private fun startHealthMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val rules = ruleStateManager.activeRules.value
                if (rules.isEmpty()) {
                    // No rules left to enforce, stop gracefully
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val verification = ruleStateManager.verifyActiveRules()
                val title = getString(R.string.bg_protection_notification_title)
                val content = if (verification.missingOrDroppedCount > 0) {
                    getString(R.string.bg_protection_notification_warning, verification.verifiedActiveCount, verification.totalRules)
                } else {
                    getString(R.string.bg_protection_notification_active, verification.verifiedActiveCount)
                }

                updateNotification(title, content)
                delay(30_000L) // 30s low-overhead heartbeat
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bg_protection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.bg_protection_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(this, NetworkProtectionService::class.java).apply {
            action = ACTION_STOP_AND_RESTORE
        }
        val restorePendingIntent = PendingIntent.getService(
            this,
            1,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_revert,
                getString(R.string.exit_restore_title),
                restorePendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "netmanager_protection_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.example.action.START_PROTECTION"
        const val ACTION_STOP_AND_RESTORE = "com.example.action.STOP_AND_RESTORE"
        const val ACTION_REFRESH = "com.example.action.REFRESH"

        fun startService(context: Context) {
            val intent = Intent(context, NetworkProtectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopAndRestore(context: Context) {
            val intent = Intent(context, NetworkProtectionService::class.java).apply {
                action = ACTION_STOP_AND_RESTORE
            }
            context.startService(intent)
        }
    }
}
