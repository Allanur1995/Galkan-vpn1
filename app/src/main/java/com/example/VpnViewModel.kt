package com.example

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ConfigUriParser
import com.example.data.DefaultServers
import com.example.data.UserPreferencesSnapshot
import com.example.data.VpnPreferencesRepository
import com.example.model.AppLanguage
import com.example.model.DnsProvider
import com.example.model.DpiBypassConfig
import com.example.model.InstalledAppInfo
import com.example.model.NetworkDiagnosticResult
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.monetization.AdManager
import com.example.monetization.BillingManager
import com.example.monetization.BillingStatus
import com.example.monetization.RewardedAdState
import com.example.vpn.BatteryOptimizationHelper
import com.example.vpn.DataUsageMonitor
import com.example.vpn.DataUsageSnapshot
import com.example.vpn.GalkanVpnService
import com.example.vpn.NetworkDiagnostics
import com.example.vpn.NetworkReachabilityHelper
import com.example.vpn.NetworkStatus
import com.example.vpn.ReachabilityResult
import com.example.vpn.StealthTunnelVpnService
import com.example.vpn.VpnStateHolder
import com.example.ui.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class VpnUiState(
  val prefs: UserPreferencesSnapshot = UserPreferencesSnapshot(),
  val servers: List<VpnServer> = DefaultServers.curatedServers,
  val selectedServer: VpnServer = DefaultServers.curatedServers.first(),
  val connectionState: VpnConnectionState = VpnConnectionState.Disconnected,
  val tunnelLogs: List<String> = emptyList(),
  val rewardedAdState: RewardedAdState = RewardedAdState.Idle,
  val billingStatus: BillingStatus = BillingStatus(),
  val installedApps: List<InstalledAppInfo> = emptyList(),
  val diagnosticResult: NetworkDiagnosticResult? = null,
  val isRunningDiagnostics: Boolean = false,
  val isPingingServers: Boolean = false,
  val isCheckingReachability: Boolean = false,
  val networkStatus: NetworkStatus = NetworkStatus(),
  val isBatteryOptimizationIgnored: Boolean = false,
  val dataUsage: DataUsageSnapshot = DataUsageSnapshot(),
  val adRemainingMs: Long = 0L,
  val proTrialRemainingMs: Long = 0L,
) {
  val hasProAccess: Boolean
    get() = prefs.isProActive || billingStatus.hasActivePurchase || proTrialRemainingMs > 0L

  val hasFreeAdPass: Boolean
    get() = adRemainingMs > 0L || hasProAccess
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {

  private val json = Json { ignoreUnknownKeys = true }
  private val repository = VpnPreferencesRepository(application)
  val adManager = AdManager(application)
  val billingManager = BillingManager(application) { isPro, planId ->
    repository.updateProEntitlement(isPro, planId)
  }

  private val reachabilityHelper = NetworkReachabilityHelper(application)
  private val batteryOptimizationHelper = BatteryOptimizationHelper(application)
  private val dataUsageMonitor = DataUsageMonitor()

  private val _pingOverrides = MutableStateFlow<Map<String, Int?>>(emptyMap())
  private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
  private val _diagnosticResult = MutableStateFlow<NetworkDiagnosticResult?>(null)
  private val _isRunningDiagnostics = MutableStateFlow(false)
  private val _isPingingServers = MutableStateFlow(false)
  private val _isCheckingReachability = MutableStateFlow(false)
  private val _nowMillis = MutableStateFlow(System.currentTimeMillis())

  private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

  private val baseStateFlow = combine(
    repository.preferencesFlow,
    VpnStateHolder.connectionState,
    VpnStateHolder.tunnelLogs,
    adManager.rewardedState,
    billingManager.billingStatus,
  ) { prefs, connState, logs, rewarded, billing ->
    BaseSnapshot(prefs, connState, logs, rewarded, billing)
  }

  private val auxStateFlow = combine(
    _pingOverrides,
    _installedApps,
    _diagnosticResult,
    _isRunningDiagnostics,
    _isPingingServers,
    _isCheckingReachability,
    reachabilityHelper.networkStatus,
    batteryOptimizationHelper.isIgnoringBatteryOptimizations,
    dataUsageMonitor.snapshot,
    _nowMillis,
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    val pings = values[0] as Map<String, Int?>
    @Suppress("UNCHECKED_CAST")
    val apps = values[1] as List<InstalledAppInfo>
    val diag = values[2] as NetworkDiagnosticResult?
    val runningDiag = values[3] as Boolean
    val pinging = values[4] as Boolean
    val checkingReachability = values[5] as Boolean
    val netStatus = values[6] as NetworkStatus
    val batteryExempt = values[7] as Boolean
    val dataUsage = values[8] as DataUsageSnapshot
    val now = values[9] as Long
    AuxSnapshot(pings, apps, diag, runningDiag, pinging, checkingReachability, netStatus, batteryExempt, dataUsage, now)
  }

  val uiState: StateFlow<VpnUiState> = combine(baseStateFlow, auxStateFlow) { base, aux ->
    val allServers = (base.prefs.customServers + DefaultServers.curatedServers).map { server ->
      if (aux.pings.containsKey(server.id)) server.copy(pingMs = aux.pings[server.id]) else server
    }
    val selected = allServers.find { it.id == base.prefs.selectedServerId } ?: allServers.first()

    VpnUiState(
      prefs = base.prefs,
      servers = allServers,
      selectedServer = selected,
      connectionState = base.connState,
      tunnelLogs = base.logs,
      rewardedAdState = base.rewarded,
      billingStatus = base.billing,
      installedApps = aux.apps,
      diagnosticResult = aux.diag,
      isRunningDiagnostics = aux.runningDiag,
      isPingingServers = aux.pinging,
      isCheckingReachability = aux.checkingReachability,
      networkStatus = aux.netStatus,
      isBatteryOptimizationIgnored = aux.batteryExempt,
      dataUsage = aux.dataUsage,
      adRemainingMs = (base.prefs.adUnlockExpiresAtMs - aux.now).coerceAtLeast(0L),
      proTrialRemainingMs = (base.prefs.proTrialExpiresAtMs - aux.now).coerceAtLeast(0L),
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000L),
    initialValue = VpnUiState(),
  )

  init {
    reachabilityHelper.startMonitoring()
    observeConnectionTelemetryForUsage()
    adManager.initialize()
    billingManager.connect()
    loadLaunchableApps()
    startClockTicker()
    pingAllServers()
  }

  private fun observeConnectionTelemetryForUsage() {
    viewModelScope.launch {
      VpnStateHolder.connectionState.collect { state ->
        dataUsageMonitor.onConnectionStateChanged(state)
      }
    }
  }

  private fun startClockTicker() {
    viewModelScope.launch {
      while (true) {
        _nowMillis.value = System.currentTimeMillis()
        delay(1000L)
      }
    }
  }

  private fun loadLaunchableApps() {
    viewModelScope.launch(Dispatchers.IO) {
      val pm = getApplication<Application>().packageManager
      val selfPkg = getApplication<Application>().packageName
      val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val resolved = pm.queryIntentActivities(launcherIntent, 0)
      val apps = resolved
        .mapNotNull { info ->
          val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
          if (pkg == selfPkg) return@mapNotNull null
          val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
          InstalledAppInfo(packageName = pkg, label = label)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
      _installedApps.value = apps
    }
  }

  fun prepareVpnConsentIntent(): Intent? {
    return VpnService.prepare(getApplication())
  }

  fun refreshBatteryOptimizationStatus(): Boolean {
    return batteryOptimizationHelper.refresh()
  }

  fun buildIgnoreBatteryOptimizationIntent(): Intent {
    return batteryOptimizationHelper.createIgnoreBatteryOptimizationIntent()
  }

  fun buildBatterySettingsIntent(): Intent {
    return batteryOptimizationHelper.createBatterySettingsIntent()
  }

  fun checkReachabilityAndPrepareConnect(
    strings: AppStrings,
    onReadyToConnect: () -> Unit,
  ) {
    if (_isCheckingReachability.value) return
    val server = uiState.value.selectedServer
    viewModelScope.launch {
      _isCheckingReachability.value = true
      VpnStateHolder.appendLog("Checking internet reachability (${server.host}:${server.port})…")
      when (val result = reachabilityHelper.verifyInternetBeforeConnect(server.host, server.port)) {
        is ReachabilityResult.Offline -> {
          VpnStateHolder.appendLog("Pre-connect check failed: device is offline")
          _uiMessages.tryEmit(strings.noInternetBeforeConnect)
        }
        is ReachabilityResult.LimitedOrRestricted -> {
          VpnStateHolder.appendLog("Limited/captive network on ${result.transport}; proceeding with Anti-DPI")
          _uiMessages.tryEmit(strings.limitedInternetWarning)
          onReadyToConnect()
        }
        is ReachabilityResult.Reachable -> {
          val rttInfo = result.latencyMs?.let { " (${it}ms)" }.orEmpty()
          VpnStateHolder.appendLog("Internet reachable via ${result.transport}$rttInfo")
          onReadyToConnect()
        }
      }
      _isCheckingReachability.value = false
    }
  }

  fun startVpnConnection() {
    val state = uiState.value
    val server = state.selectedServer
    val app = getApplication<Application>()
    val useStealthTunnel = server.protocol == VpnProtocol.VLESS_REALITY ||
      server.protocol == VpnProtocol.SHADOWSOCKS_OBFS

    val intent = if (useStealthTunnel) {
      Intent(app, StealthTunnelVpnService::class.java).apply {
        action = StealthTunnelVpnService.ACTION_CONNECT
        putExtra(StealthTunnelVpnService.EXTRA_SERVER_JSON, json.encodeToString(server))
        putExtra(StealthTunnelVpnService.EXTRA_DPI_CONFIG_JSON, json.encodeToString(state.prefs.dpiConfig))
        putStringArrayListExtra(
          StealthTunnelVpnService.EXTRA_EXCLUDED_PACKAGES,
          ArrayList(state.prefs.excludedPackages),
        )
      }
    } else {
      Intent(app, GalkanVpnService::class.java).apply {
        action = GalkanVpnService.ACTION_CONNECT
        putExtra(GalkanVpnService.EXTRA_SERVER_JSON, json.encodeToString(server))
        putExtra(GalkanVpnService.EXTRA_DPI_CONFIG_JSON, json.encodeToString(state.prefs.dpiConfig))
        putStringArrayListExtra(
          GalkanVpnService.EXTRA_EXCLUDED_PACKAGES,
          ArrayList(state.prefs.excludedPackages),
        )
      }
    }

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      app.startForegroundService(intent)
    } else {
      app.startService(intent)
    }
  }

  fun disconnectVpn(activity: Activity? = null) {
    val app = getApplication<Application>()
    val activeProtocol = when (val conn = uiState.value.connectionState) {
      is VpnConnectionState.Connected -> conn.server.protocol
      is VpnConnectionState.Connecting -> conn.server.protocol
      else -> uiState.value.selectedServer.protocol
    }
    val useStealthTunnel = activeProtocol == VpnProtocol.VLESS_REALITY ||
      activeProtocol == VpnProtocol.SHADOWSOCKS_OBFS

    val intent = if (useStealthTunnel) {
      Intent(app, StealthTunnelVpnService::class.java).apply {
        action = StealthTunnelVpnService.ACTION_DISCONNECT
      }
    } else {
      Intent(app, GalkanVpnService::class.java).apply {
        action = GalkanVpnService.ACTION_DISCONNECT
      }
    }
    app.startService(intent)
    if (activity != null) {
      adManager.maybeShowInterstitial(activity, uiState.value.hasProAccess)
    }
  }

  fun selectServer(server: VpnServer): Boolean {
    val state = uiState.value
    if (server.isProOnly && !state.hasProAccess) {
      return false
    }
    viewModelScope.launch {
      repository.selectServer(server.id)
      // Sync protocol to server's recommended protocol if not custom override
      val updatedDpi = state.prefs.dpiConfig.copy(
        activeProtocol = server.protocol,
        customSni = server.sni,
      )
      repository.updateDpiConfig(updatedDpi)
    }
    return true
  }

  fun selectFastestAvailableServer() {
    val state = uiState.value
    val available = state.servers.filter { !it.isProOnly || state.hasProAccess }
    val fastest = available.minByOrNull { it.pingMs ?: Int.MAX_VALUE } ?: return
    selectServer(fastest)
  }

  fun importServerUri(rawUri: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
    when (val result = ConfigUriParser.parse(rawUri)) {
      is ConfigUriParser.ParseResult.Success -> {
        viewModelScope.launch {
          repository.addCustomServer(result.server)
          val ping = NetworkDiagnostics.measureTcpPing(result.server.host, result.server.port)
          _pingOverrides.value = _pingOverrides.value + (result.server.id to ping)
          onSuccess()
        }
      }
      is ConfigUriParser.ParseResult.Error -> onError(result.reason)
    }
  }

  fun deleteCustomServer(serverId: String) {
    viewModelScope.launch { repository.removeCustomServer(serverId) }
  }

  fun pingAllServers() {
    if (_isPingingServers.value) return
    viewModelScope.launch {
      _isPingingServers.value = true
      val servers = uiState.value.servers
      val results = withContext(Dispatchers.IO) {
        servers.map { srv ->
          async { srv.id to NetworkDiagnostics.measureTcpPing(srv.host, srv.port, 1800) }
        }.awaitAll().toMap()
      }
      _pingOverrides.value = results
      _isPingingServers.value = false
    }
  }

  fun runNetworkDiagnostics() {
    if (_isRunningDiagnostics.value) return
    viewModelScope.launch {
      _isRunningDiagnostics.value = true
      val state = uiState.value
      val result = NetworkDiagnostics.runDiagnostics(state.selectedServer, state.prefs.dpiConfig.dnsProvider)
      _diagnosticResult.value = result
      _isRunningDiagnostics.value = false
    }
  }

  fun updateDpiConfig(transform: (DpiBypassConfig) -> DpiBypassConfig) {
    viewModelScope.launch {
      val updated = transform(uiState.value.prefs.dpiConfig)
      repository.updateDpiConfig(updated)
    }
  }

  fun selectProtocol(protocol: VpnProtocol) {
    updateDpiConfig { it.copy(activeProtocol = protocol) }
  }

  fun selectDnsProvider(provider: DnsProvider) {
    updateDpiConfig { it.copy(dnsProvider = provider) }
  }

  fun toggleExcludedApp(packageName: String) {
    viewModelScope.launch { repository.toggleExcludedPackage(packageName) }
  }

  fun setLanguage(language: AppLanguage) {
    viewModelScope.launch { repository.setLanguage(language) }
  }

  fun setDarkTheme(isDark: Boolean) {
    viewModelScope.launch { repository.setDarkTheme(isDark) }
  }

  fun watchRewardedAdForTime(activity: Activity, grantProTrial: Boolean, strings: AppStrings) {
    adManager.showRewardedAd(
      activity = activity,
      onRewardEarned = {
        viewModelScope.launch {
          repository.grantAdUnlock(
            durationMillis = 2 * 60 * 60 * 1000L, // +2 hours free VPN
            includesProTrial = grantProTrial,
          )
          val msg = if (grantProTrial) {
            strings.adProTrialUnlockedMsg
          } else {
            strings.adFreeUnlockedMsg
          }
          _uiMessages.tryEmit(msg)
        }
      },
      onNotReady = {
        _uiMessages.tryEmit(strings.adNotReadyMsg)
      },
    )
  }

  fun purchaseProPlan(activity: Activity, productId: String) {
    val launched = billingManager.launchPurchase(activity, productId)
    if (!launched) {
      billingManager.billingStatus.value.lastMessage?.let { _uiMessages.tryEmit(it) }
    }
  }

  fun restorePurchases() {
    billingManager.restorePurchases()
  }

  fun clearLogs() {
    VpnStateHolder.clearLogs()
  }

  override fun onCleared() {
    reachabilityHelper.stopMonitoring()
    billingManager.endConnection()
    super.onCleared()
  }
}

private data class BaseSnapshot(
  val prefs: UserPreferencesSnapshot,
  val connState: VpnConnectionState,
  val logs: List<String>,
  val rewarded: RewardedAdState,
  val billing: BillingStatus,
)

private data class AuxSnapshot(
  val pings: Map<String, Int?>,
  val apps: List<InstalledAppInfo>,
  val diag: NetworkDiagnosticResult?,
  val runningDiag: Boolean,
  val pinging: Boolean,
  val checkingReachability: Boolean,
  val netStatus: NetworkStatus,
  val batteryExempt: Boolean,
  val dataUsage: DataUsageSnapshot,
  val now: Long,
)
