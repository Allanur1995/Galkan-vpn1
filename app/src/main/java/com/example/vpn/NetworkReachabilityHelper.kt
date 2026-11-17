package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class NetworkTransport {
  WIFI,
  CELLULAR,
  ETHERNET,
  OTHER,
  NONE,
}

data class NetworkStatus(
  val isNetworkAvailable: Boolean = false,
  val isInternetValidated: Boolean = false,
  val transport: NetworkTransport = NetworkTransport.NONE,
)

sealed interface ReachabilityResult {
  data class Reachable(
    val transport: NetworkTransport,
    val latencyMs: Int?,
  ) : ReachabilityResult

  data class LimitedOrRestricted(
    val transport: NetworkTransport,
  ) : ReachabilityResult

  data object Offline : ReachabilityResult
}

class NetworkReachabilityHelper(context: Context) {

  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  private val _networkStatus = MutableStateFlow(readCurrentNetworkStatus())
  val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

  private var callbackRegistered = false

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      refreshStatus()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      refreshStatus()
    }

    override fun onLost(network: Network) {
      refreshStatus()
    }

    override fun onUnavailable() {
      _networkStatus.value = NetworkStatus(
        isNetworkAvailable = false,
        isInternetValidated = false,
        transport = NetworkTransport.NONE,
      )
    }
  }

  fun startMonitoring() {
    if (callbackRegistered) return
    val cm = connectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(networkCallback)
      callbackRegistered = true
      refreshStatus()
    }
  }

  fun stopMonitoring() {
    if (!callbackRegistered) return
    val cm = connectivityManager ?: return
    runCatching {
      cm.unregisterNetworkCallback(networkCallback)
    }
    callbackRegistered = false
  }

  fun refreshStatus(): NetworkStatus {
    val current = readCurrentNetworkStatus()
    _networkStatus.value = current
    return current
  }

  fun readCurrentNetworkStatus(): NetworkStatus {
    val cm = connectivityManager ?: return NetworkStatus()
    val activeNetwork = cm.activeNetwork ?: return NetworkStatus()
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkStatus()

    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val transport = when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
      else -> NetworkTransport.OTHER
    }

    return NetworkStatus(
      isNetworkAvailable = hasInternet,
      isInternetValidated = validated,
      transport = if (hasInternet) transport else NetworkTransport.NONE,
    )
  }

  /**
   * Performs a pre-flight reachability check before starting the VPN tunnel:
   * 1. Checks local network capabilities via ConnectivityManager.
   * 2. Probes TCP reachability to the selected server or well-known public resolvers.
   */
  suspend fun verifyInternetBeforeConnect(
    targetHost: String,
    targetPort: Int,
  ): ReachabilityResult = withContext(Dispatchers.IO) {
    val status = refreshStatus()
    if (!status.isNetworkAvailable) {
      return@withContext ReachabilityResult.Offline
    }

    val targetRtt = probeTcpSocket(targetHost, targetPort, timeoutMs = 1800)
    if (targetRtt != null) {
      return@withContext ReachabilityResult.Reachable(
        transport = status.transport,
        latencyMs = targetRtt,
      )
    }

    val fallbackRtt = probeTcpSocket("1.1.1.1", 443, timeoutMs = 1800)
      ?: probeTcpSocket("8.8.8.8", 443, timeoutMs = 1800)

    if (fallbackRtt != null || status.isInternetValidated) {
      return@withContext ReachabilityResult.Reachable(
        transport = status.transport,
        latencyMs = fallbackRtt,
      )
    }

    ReachabilityResult.LimitedOrRestricted(transport = status.transport)
  }

  private fun probeTcpSocket(host: String, port: Int, timeoutMs: Int): Int? {
    return try {
      Socket().use { socket ->
        val start = System.currentTimeMillis()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
      }
    } catch (_: Exception) {
      null
    }
  }
}
