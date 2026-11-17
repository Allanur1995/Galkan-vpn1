package com.example.vpn

import com.example.model.VpnConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {

  private val _connectionState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
  val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

  private val _tunnelLogs = MutableStateFlow<List<String>>(emptyList())
  val tunnelLogs: StateFlow<List<String>> = _tunnelLogs.asStateFlow()

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  fun updateState(newState: VpnConnectionState) {
    _connectionState.value = newState
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
