package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time connection status observer owned by [VpnService] / [HevVpnEngine].
 *
 * Monitors:
 * 1. Android system VPN transport registration (`NetworkCapabilities.TRANSPORT_VPN`) via
 *    `ConnectivityManager.NetworkCallback` (with `NET_CAPABILITY_NOT_VPN` removed).
 * 2. Underlying physical internet availability (Wi-Fi / Cellular / Ethernet) so the UI
 *    immediately detects degraded offline gaps vs active verified tunnel operation.
 * 3. In-process tunnel bridge health: TUN file descriptor validity, native `HEVTunnel`
 *    worker thread liveness, and `LocalSocks5ProxyServer` socket liveness.
 *
 * Broadcasts every state change to [VpnStateHolder.observerState] and notifies
 * [onUnexpectedTunnelLost] when a previously established VPN interface or bridge collapses.
 */
internal class TunnelStatusObserver(
  context: Context,
  private val serviceTag: String,
  private val onUnexpectedTunnelLost: (reason: String) -> Unit,
) {

  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  private val monitoring = AtomicBoolean(false)
  private val sessionActive = AtomicBoolean(false)
  private val hadSystemVpnSeen = AtomicBoolean(false)
  private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()
  private val physicalNetworks = ConcurrentHashMap.newKeySet<Network>()

  @Volatile
  private var sessionStartedAtMs: Long = 0L

  @Volatile
  private var lastTunFdValid: Boolean = false

  @Volatile
  private var lastNativeWorkerAlive: Boolean = false

  @Volatile
  private var lastSocksProxyAlive: Boolean = false

  @Volatile
  private var lastLoggedInternetLost: Boolean = false

  internal val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      vpnNetworks.add(network)
      hadSystemVpnSeen.set(true)
      if (sessionActive.get()) {
        VpnStateHolder.appendLog("Observer: System VPN transport active (TRANSPORT_VPN)")
        refreshObserverSnapshot()
      }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        vpnNetworks.add(network)
        hadSystemVpnSeen.set(true)
      }
      if (sessionActive.get()) {
        refreshObserverSnapshot()
      }
    }

    override fun onLost(network: Network) {
      vpnNetworks.remove(network)
      if (sessionActive.get() && hadSystemVpnSeen.get() && !hasSystemVpnTransport(excluding = network)) {
        VpnStateHolder.appendLog("Observer: System VPN transport lost")
        refreshObserverSnapshot()
        onUnexpectedTunnelLost("System VPN interface closed or revoked")
      }
    }
  }

  internal val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      physicalNetworks.add(network)
      if (sessionActive.get() && lastLoggedInternetLost) {
        lastLoggedInternetLost = false
        VpnStateHolder.appendLog("Observer: Underlying internet restored")
      }
      if (sessionActive.get()) {
        refreshObserverSnapshot()
      }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      ) {
        physicalNetworks.add(network)
      }
      if (sessionActive.get()) {
        refreshObserverSnapshot()
      }
    }

    override fun onLost(network: Network) {
      physicalNetworks.remove(network)
      if (sessionActive.get() && !hasPhysicalInternet(excluding = network) && !lastLoggedInternetLost) {
        lastLoggedInternetLost = true
        VpnStateHolder.appendLog("Observer: Underlying internet lost — tunnel degraded")
      }
      if (sessionActive.get()) {
        refreshObserverSnapshot()
      }
    }

    override fun onUnavailable() {
      physicalNetworks.clear()
      if (sessionActive.get()) {
        refreshObserverSnapshot()
      }
    }
  }

  fun startMonitoring() {
    if (!monitoring.compareAndSet(false, true)) return
    val cm = connectivityManager ?: return

    runCatching {
      val vpnRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
      cm.registerNetworkCallback(vpnRequest, vpnNetworkCallback)
    }

    runCatching {
      val physicalRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
      cm.registerNetworkCallback(physicalRequest, physicalNetworkCallback)
    }
  }

  fun markTunnelStarted(
    tunFdValid: Boolean,
    nativeWorkerAlive: Boolean,
    socksProxyAlive: Boolean,
  ): TunnelObserverSnapshot {
    sessionStartedAtMs = System.currentTimeMillis()
    hadSystemVpnSeen.set(hasSystemVpnTransport())
    lastLoggedInternetLost = false
    sessionActive.set(true)
    return evaluateLiveness(
      tunFdValid = tunFdValid,
      nativeWorkerAlive = nativeWorkerAlive,
      socksProxyAlive = socksProxyAlive,
    )
  }

  fun evaluateLiveness(
    tunFdValid: Boolean,
    nativeWorkerAlive: Boolean,
    socksProxyAlive: Boolean,
  ): TunnelObserverSnapshot {
    lastTunFdValid = tunFdValid
    lastNativeWorkerAlive = nativeWorkerAlive
    lastSocksProxyAlive = socksProxyAlive
    return refreshObserverSnapshot()
  }

  private fun refreshObserverSnapshot(): TunnelObserverSnapshot {
    val active = sessionActive.get()
    val systemVpnUp = hasSystemVpnTransport()
    if (systemVpnUp) {
      hadSystemVpnSeen.set(true)
    }
    val hasInternet = hasPhysicalInternet()
    val effectiveVpnUp = if (active && !systemVpnUp && !hadSystemVpnSeen.get()) {
      // Allow brief startup window before Android Netd completes TRANSPORT_VPN registration
      val withinGrace = (System.currentTimeMillis() - sessionStartedAtMs) < VPN_BRINGUP_GRACE_MS
      if (withinGrace && lastTunFdValid && lastNativeWorkerAlive && lastSocksProxyAlive) {
        true
      } else {
        false
      }
    } else {
      systemVpnUp
    }

    val phase = computeBridgePhase(
      sessionActive = active,
      isTunFdValid = lastTunFdValid,
      isNativeWorkerAlive = lastNativeWorkerAlive,
      isSocksProxyAlive = lastSocksProxyAlive,
      isSystemVpnUp = effectiveVpnUp,
      hasUnderlyingInternet = hasInternet,
    )

    val snapshot = TunnelObserverSnapshot(
      serviceTag = serviceTag,
      phase = phase,
      isSystemVpnInterfaceUp = effectiveVpnUp,
      isTunFdValid = lastTunFdValid,
      isNativeWorkerAlive = lastNativeWorkerAlive,
      isSocksProxyAlive = lastSocksProxyAlive,
      hasUnderlyingInternet = hasInternet,
      lastVerifiedAtMs = System.currentTimeMillis(),
      lastError = null,
    )
    VpnStateHolder.setObserverState(snapshot)
    return snapshot
  }

  fun stopMonitoring() {
    sessionActive.set(false)
    hadSystemVpnSeen.set(false)
    lastTunFdValid = false
    lastNativeWorkerAlive = false
    lastSocksProxyAlive = false
    vpnNetworks.clear()
    physicalNetworks.clear()

    if (!monitoring.compareAndSet(true, false)) return
    val cm = connectivityManager ?: return
    runCatching { cm.unregisterNetworkCallback(vpnNetworkCallback) }
    runCatching { cm.unregisterNetworkCallback(physicalNetworkCallback) }
  }

  @Suppress("DEPRECATION")
  fun hasSystemVpnTransport(excluding: Network? = null): Boolean {
    if (vpnNetworks.any { it != excluding }) return true
    val cm = connectivityManager ?: return false
    val activeNet = cm.activeNetwork
    if (activeNet != null && activeNet != excluding) {
      val activeCaps = cm.getNetworkCapabilities(activeNet)
      if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
        return true
      }
    }
    return runCatching {
      cm.allNetworks.any { net ->
        net != excluding &&
          cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
      }
    }.getOrDefault(false)
  }

  @Suppress("DEPRECATION")
  fun hasPhysicalInternet(excluding: Network? = null): Boolean {
    if (physicalNetworks.any { it != excluding }) return true
    val cm = connectivityManager ?: return true
    return runCatching {
      cm.allNetworks.any { net ->
        if (net == excluding) return@any false
        val caps = cm.getNetworkCapabilities(net) ?: return@any false
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      }
    }.getOrDefault(false)
  }

  companion object {
    private const val VPN_BRINGUP_GRACE_MS = 2500L

    fun computeBridgePhase(
      sessionActive: Boolean,
      isTunFdValid: Boolean,
      isNativeWorkerAlive: Boolean,
      isSocksProxyAlive: Boolean,
      isSystemVpnUp: Boolean,
      hasUnderlyingInternet: Boolean,
    ): TunnelBridgePhase {
      if (!sessionActive) return TunnelBridgePhase.DISCONNECTED
      if (!isTunFdValid || !isNativeWorkerAlive || !isSocksProxyAlive) {
        return TunnelBridgePhase.ERROR
      }
      if (!isSystemVpnUp) {
        return TunnelBridgePhase.OPENING_TUN
      }
      if (!hasUnderlyingInternet) {
        return TunnelBridgePhase.DEGRADED_NO_INTERNET
      }
      return TunnelBridgePhase.ACTIVE_VERIFIED
    }
  }
}
