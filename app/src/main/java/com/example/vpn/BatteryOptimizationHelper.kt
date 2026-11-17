package com.example.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Helper for checking and requesting battery optimization exemption so Android Doze
 * and App Standby do not interrupt Galkan VPN's persistent background tunnel service.
 */
class BatteryOptimizationHelper(private val context: Context) {

  private val powerManager: PowerManager? =
    context.getSystemService(PowerManager::class.java)

  private val _isIgnoringBatteryOptimizations = MutableStateFlow(readIsIgnoringBatteryOptimizations())
  val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

  fun readIsIgnoringBatteryOptimizations(): Boolean {
    val pm = powerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
  }

  fun refresh(): Boolean {
    val current = readIsIgnoringBatteryOptimizations()
    _isIgnoringBatteryOptimizations.value = current
    return current
  }

  /**
   * Builds a system prompt intent requesting battery optimization exemption for this package,
   * falling back cleanly to the system Battery Optimization list or App Details screen
   * if a device/ROM does not expose the direct prompt handler.
   */
  @SuppressLint("BatteryLife")
  fun createIgnoreBatteryOptimizationIntent(): Intent {
    val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
    return if (directIntent.resolveActivity(context.packageManager) != null) {
      directIntent
    } else {
      createBatterySettingsIntent()
    }
  }

  /**
   * Builds an intent to open Android's system Battery Optimization settings list,
   * with App Details as a safe fallback.
   */
  fun createBatterySettingsIntent(): Intent {
    val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (listIntent.resolveActivity(context.packageManager) != null) {
      return listIntent
    }
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  }
}
