package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.VpnUiState
import com.example.model.AppLanguage
import com.example.model.DpiBypassConfig
import com.example.ui.AppStrings
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  uiState: VpnUiState,
  strings: AppStrings,
  isUsingTestAdIds: Boolean,
  onSetLanguage: (AppLanguage) -> Unit,
  onSetDarkTheme: (Boolean) -> Unit,
  onUpdateDpiConfig: ((DpiBypassConfig) -> DpiBypassConfig) -> Unit,
  onRequestBatteryOptimization: () -> Unit,
  onOpenBatterySettings: () -> Unit,
  onClearLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 620.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = strings.settingsTitle,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
      )

      // Language Selector
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.languageLabel, style = MaterialTheme.typography.titleSmall)
          }
          Spacer(modifier = Modifier.height(10.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { lang ->
              FilterChip(
                selected = uiState.prefs.language == lang,
                onClick = { onSetLanguage(lang) },
                label = { Text(lang.nativeName) },
                modifier = Modifier.testTag("lang_chip_${lang.code}"),
              )
            }
          }
        }
      }

      // Appearance: Light & Dark Mode Toggle + Auto-Reconnect
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
          .testTag("settings_theme_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          val isDark = uiState.prefs.isDarkTheme
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = if (isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = strings.themeSectionTitle,
                tint = if (isDark) AmberPro else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(strings.themeSectionTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (isDark) strings.themeDarkSubtitle else strings.themeLightSubtitle,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
              checked = isDark,
              onCheckedChange = onSetDarkTheme,
              modifier = Modifier.testTag("dark_theme_switch"),
            )
          }

          SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
          ) {
            SegmentedButton(
              selected = !isDark,
              onClick = { onSetDarkTheme(false) },
              shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
              icon = {
                Icon(
                  imageVector = Icons.Filled.LightMode,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.testTag("theme_light_button"),
            ) {
              Text(strings.themeLightOption)
            }
            SegmentedButton(
              selected = isDark,
              onClick = { onSetDarkTheme(true) },
              shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
              icon = {
                Icon(
                  imageVector = Icons.Filled.DarkMode,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.testTag("theme_dark_button"),
            ) {
              Text(strings.themeDarkOption)
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = strings.autoReconnectLabel,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = uiState.prefs.dpiConfig.autoReconnect,
              onCheckedChange = { enabled ->
                onUpdateDpiConfig { it.copy(autoReconnect = enabled) }
              },
            )
          }
        }
      }

      // Background Persistence & Battery Optimization
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
          .testTag("settings_battery_opt_card"),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val exempt = uiState.isBatteryOptimizationIgnored
          val statusTint = if (exempt) EmeraldPrimary else AmberPro
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = if (exempt) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryAlert,
              contentDescription = strings.batteryOptTitle,
              tint = statusTint,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.batteryOptTitle, style = MaterialTheme.typography.titleSmall)
          }
          Text(
            text = if (exempt) strings.batteryOptStatusExempt else strings.batteryOptStatusRestricted,
            style = MaterialTheme.typography.labelMedium,
            color = statusTint,
            modifier = Modifier.testTag("settings_battery_opt_status"),
          )
          Text(
            text = strings.batteryOptSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (!exempt) {
              Button(
                onClick = onRequestBatteryOptimization,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_battery_opt_button"),
              ) {
                Text(strings.batteryOptAllowButton)
              }
            }
            OutlinedButton(
              onClick = onOpenBatterySettings,
              modifier = Modifier
                .weight(1f)
                .testTag("settings_battery_settings_button"),
            ) {
              Text(strings.batteryOptSettingsButton)
            }
          }
        }
      }

      // AdMob & GitHub APK Info Cards
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AdsClick, contentDescription = null, tint = CyanSecondary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.admobStatusTitle, style = MaterialTheme.typography.titleSmall)
          }
          Text(
            text = if (isUsingTestAdIds) strings.admobStatusTest else strings.admobStatusProd,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Spacer(modifier = Modifier.height(4.dp))

          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BuildCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.githubBuildTitle, style = MaterialTheme.typography.titleSmall)
          }
          Text(
            text = strings.githubBuildSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Tunnel Telemetry Logs
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Terminal, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(strings.logsTitle, style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onClearLogs) {
              Text(strings.clearLogs)
            }
          }

          if (uiState.tunnelLogs.isEmpty()) {
            Text(
              text = "—",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            uiState.tunnelLogs.take(12).forEach { line ->
              Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}
