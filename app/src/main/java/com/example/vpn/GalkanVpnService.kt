package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DefaultServers
import com.example.model.DpiBypassConfig
import com.example.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

class GalkanVpnService : VpnService() {

  companion object {
    const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
    const val ACTION_BIND_MONITOR = "com.example.vpn.ACTION_BIND_MONITOR"
    const val EXTRA_SERVER_JSON = "extra_server_json"
    const val EXTRA_DPI_CONFIG_JSON = "extra_dpi_config_json"
    const val EXTRA_EXCLUDED_PACKAGES = "extra_excluded_packages"

    private const val NOTIFICATION_CHANNEL_ID = "galkan_vpn_channel"
    private const val NOTIFICATION_ID = 7001
  }

  inner class LocalBinder : Binder() {
    fun getService(): GalkanVpnService = this@GalkanVpnService
  }

  private val localBinder = LocalBinder()
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true }
  private lateinit var engine: HevVpnEngine

  val dataUsageState: StateFlow<DataUsageSnapshot>
    get() = VpnStateHolder.dataUsageState

  fun currentDataUsage(): DataUsageSnapshot =
    if (::engine.isInitialized) engine.currentDataUsage() else VpnStateHolder.dataUsageState.value

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    engine = HevVpnEngine(
      service = this,
      serviceTag = "primary",
      scope = serviceScope,
      onConnectedNotification = { server -> updateConnectedNotification(server) },
      onTrafficUpdated = { server, usage -> updateConnectedNotification(server, usage) },
      onStopService = {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      },
    )
  }

  override fun onBind(intent: Intent?): IBinder? {
    return if (intent?.action == ACTION_BIND_MONITOR) localBinder else super.onBind(intent)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISCONNECT) {
      stopTunnelAndService()
      return START_NOT_STICKY
    }

    val server = intent?.getStringExtra(EXTRA_SERVER_JSON)?.let { raw ->
      runCatching { json.decodeFromString<VpnServer>(raw) }.getOrNull()
    } ?: DefaultServers.curatedServers.first()

    val dpiConfig = intent?.getStringExtra(EXTRA_DPI_CONFIG_JSON)?.let { raw ->
      runCatching { json.decodeFromString<DpiBypassConfig>(raw) }.getOrNull()
    } ?: DpiBypassConfig()

    val excludedPackages =
      intent?.getStringArrayListExtra(EXTRA_EXCLUDED_PACKAGES)?.toSet() ?: emptySet()

    val initialNotification = buildNotification(
      title = getString(R.string.app_name),
      content = getString(R.string.vpn_notification_connecting),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        initialNotification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, initialNotification)
    }

    engine.start(server, dpiConfig, excludedPackages)
    return START_STICKY
  }

  private fun stopTunnelAndService() {
    engine.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onRevoke() {
    if (::engine.isInitialized) {
      engine.stopWithError("VPN permission revoked by system")
    } else {
      VpnStateHolder.broadcastError("VPN permission revoked by system", "primary")
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    super.onRevoke()
  }

  override fun onDestroy() {
    if (::engine.isInitialized) {
      engine.stopIfStillActive()
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun updateConnectedNotification(
    server: VpnServer,
    usage: DataUsageSnapshot? = null,
  ) {
    val notificationManager = getSystemService(NotificationManager::class.java)
    val base = getString(
      R.string.vpn_notification_connected,
      server.name,
      server.protocol.shortName,
    )
    val content = if (usage != null && usage.totalBytes > 0L) {
      "$base • ↓ ${DataUsageMonitor.formatLiveCounter(usage.receivedBytes)} ↑ ${DataUsageMonitor.formatLiveCounter(usage.sentBytes)}"
    } else {
      base
    }
    notificationManager?.notify(
      NOTIFICATION_ID,
      buildNotification(
        title = getString(R.string.app_name),
        content = content,
      ),
    )
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        getString(R.string.vpn_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.vpn_notification_channel_desc)
        setShowBadge(false)
      }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(title: String, content: String): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val disconnectIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, GalkanVpnService::class.java).apply { action = ACTION_DISCONNECT },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(content)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(openAppIntent)
      .addAction(0, getString(R.string.vpn_notification_disconnect), disconnectIntent)
      .build()
  }
}
