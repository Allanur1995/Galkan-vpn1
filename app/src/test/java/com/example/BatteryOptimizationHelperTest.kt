package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.vpn.BatteryOptimizationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatteryOptimizationHelperTest {

  @Test
  fun `tracks power manager ignoring battery optimizations status and refreshes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val powerManager = context.getSystemService(PowerManager::class.java)
    val shadowPowerManager = Shadows.shadowOf(powerManager)

    shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, false)
    val helper = BatteryOptimizationHelper(context)
    assertFalse(helper.readIsIgnoringBatteryOptimizations())
    assertFalse(helper.isIgnoringBatteryOptimizations.value)

    shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
    assertTrue(helper.refresh())
    assertTrue(helper.isIgnoringBatteryOptimizations.value)
  }

  @Test
  fun `builds direct battery exemption prompt intent when handler exists`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val shadowPm = Shadows.shadowOf(context.packageManager)

    val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
    val resolveInfo = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply {
        packageName = "com.android.settings"
        name = "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations"
      }
    }
    shadowPm.addResolveInfoForIntent(directIntent, resolveInfo)

    val helper = BatteryOptimizationHelper(context)
    val intent = helper.createIgnoreBatteryOptimizationIntent()
    assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
    assertEquals("package:${context.packageName}", intent.dataString)
  }

  @Test
  fun `falls back to battery optimization settings or app details when direct prompt missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val shadowPm = Shadows.shadowOf(context.packageManager)

    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val resolveInfo = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply {
        packageName = "com.android.settings"
        name = "com.android.settings.Settings\$HighPowerApplicationsActivity"
      }
    }
    shadowPm.addResolveInfoForIntent(settingsIntent, resolveInfo)

    val helper = BatteryOptimizationHelper(context)
    val intent = helper.createIgnoreBatteryOptimizationIntent()
    assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
  }
}
