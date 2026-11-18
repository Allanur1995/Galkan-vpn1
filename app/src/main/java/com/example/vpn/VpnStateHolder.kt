package com.example.vpn

import com.example.model.VpnConnectionState
import com.example.model.VpnServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TunnelBridgePhase {
  IDLE,
  PROBING_UPSTREAM,
  OPENING_TUN,
  ACTIVE_VERIFIED,
  DEGRADED_NO_INTERNET,
  DISCONNECTED,
  ERROR,
}

data class TunnelObserverSnapshot(
  val serviceTag: String = "",
  val phase: TunnelBridgePhase = TunnelBridgePhase.IDLE,
  val isSystemVpnInterfaceUp: Boolean = false,
  val isTunFdValid: Boolean = false,
  val isNativeWorkerAlive: Boolean = false,
  val isSocksProxyAlive: Boolean = false,
  val hasUnderlyingInternet: Boolean = true,
  val lastVerifiedAtMs: Long = 0L,
  val lastError: String? = null,
) {
  val isTunnelVerifiedActive: Boolean
    get() = phase == TunnelBridgePhase.ACTIVE_VERIFIED &&
      isSystemVpnInterfaceUp &&
      isTunFdValid &&
      isNativeWorkerAlive &&
      isSocksProxyAlive
}

object VpnStateHolder {

  private val stateLock = Any()

  private val _connectionState =
    MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
  val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

  private val _observerState = MutableStateFlow(TunnelObserverSnapshot())
  val observerState: StateFlow<TunnelObserverSnapshot> = _observerState.asStateFlow()

  private val _dataUsageState = MutableStateFlow(DataUsageSnapshot())
  val dataUsageState: StateFlow<DataUsageSnapshot> = _dataUsageState.asStateFlow()

  private val _tunnelLogs = MutableStateFlow<List<String>>(emptyList())
  val tunnelLogs: StateFlow<List<String>> = _tunnelLogs.asStateFlow()

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  fun updateState(newState: VpnConnectionState) {
    synchronized(stateLock) {
      _connectionState.value = newState
    }
  }

  fun updateDataUsage(snapshot: DataUsageSnapshot) {
    synchronized(stateLock) {
      _dataUsageState.value = snapshot
    }
  }

  fun setObserverState(snapshot: TunnelObserverSnapshot) {
    synchronized(stateLock) {
      _observerState.value = snapshot
    }
  }

  fun updateObserverState(transform: (TunnelObserverSnapshot) -> TunnelObserverSnapshot) {
    synchronized(stateLock) {
      _observerState.update(transform)
    }
  }

  fun broadcastConnecting(
    server: VpnServer,
    stage: String,
    serviceTag: String = "",
    phase: TunnelBridgePhase = TunnelBridgePhase.PROBING_UPSTREAM,
  ) {
    synchronized(stateLock) {
      _connectionState.value = VpnConnectionState.Connecting(server, stage)
      _observerState.value = TunnelObserverSnapshot(
        serviceTag = serviceTag,
        phase = phase,
        isSystemVpnInterfaceUp = false,
        isTunFdValid = false,
        isNativeWorkerAlive = false,
        isSocksProxyAlive = false,
        hasUnderlyingInternet = true,
        lastVerifiedAtMs = System.currentTimeMillis(),
        lastError = null,
      )
    }
  }

  fun broadcastConnected(
    connected: VpnConnectionState.Connected,
    snapshot: TunnelObserverSnapshot,
  ) {
    synchronized(stateLock) {
      _connectionState.value = connected
      _observerState.value = snapshot
    }
  }

  fun broadcastDisconnected(serviceTag: String = "") {
    synchronized(stateLock) {
      _connectionState.value = VpnConnectionState.Disconnected
      _observerState.value = TunnelObserverSnapshot(
        serviceTag = serviceTag,
        phase = TunnelBridgePhase.DISCONNECTED,
        isSystemVpnInterfaceUp = false,
        isTunFdValid = false,
        isNativeWorkerAlive = false,
        isSocksProxyAlive = false,
        hasUnderlyingInternet = _observerState.value.hasUnderlyingInternet,
        lastVerifiedAtMs = System.currentTimeMillis(),
        lastError = null,
      )
    }
  }

  fun broadcastError(
    message: String,
    serviceTag: String = "",
    snapshot: TunnelObserverSnapshot? = null,
  ) {
    synchronized(stateLock) {
      _connectionState.value = VpnConnectionState.Error(message)
      _observerState.value = snapshot ?: TunnelObserverSnapshot(
        serviceTag = serviceTag,
        phase = TunnelBridgePhase.ERROR,
        isSystemVpnInterfaceUp = false,
        isTunFdValid = false,
        isNativeWorkerAlive = false,
        isSocksProxyAlive = false,
        hasUnderlyingInternet = _observerState.value.hasUnderlyingInternet,
        lastVerifiedAtMs = System.currentTimeMillis(),
        lastError = message,
      )
    }
  }

  fun appendLog(message: String) {
    val stamp = timeFormat.format(Date())
    _tunnelLogs.update { existing ->
      (listOf("[$stamp] $message") + existing).take(40)
    }
  }

  fun clearLogs() {
    _tunnelLogs.value = emptyList()
  }
}
