package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.VpnUiState
import com.example.model.AppLanguage
import com.example.model.VpnConnectionState
import com.example.monetization.RewardedAdState
import com.example.ui.AppStrings
import com.example.ui.components.DataUsageMonitorCard
import com.example.ui.components.PulsingShieldButton
import com.example.ui.components.TierBadgeChip
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.vpn.NetworkTransport
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
  uiState: VpnUiState,
  strings: AppStrings,
  onToggleVpn: () -> Unit,
  onNavigateServers: () -> Unit,
  onNavigateStealth: () -> Unit,
  onNavigatePro: () -> Unit,
  onWatchAdFreeTime: (Activity) -> Unit,
  onWatchAdProTrial: (Activity) -> Unit,
  onRunDiagnostics: () -> Unit,
  onSelectLanguage: (AppLanguage) -> Unit,
  onRequestBatteryOptimization: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val conn = uiState.connectionState

  var liveElapsedMs by remember { mutableLongStateOf(0L) }
  LaunchedEffect(conn) {
    if (conn is VpnConnectionState.Connected) {
      while (true) {
        liveElapsedMs = (System.currentTimeMillis() - conn.connectedSinceMs).coerceAtLeast(0L)
        delay(1000L)
      }
    } else {
      liveElapsedMs = 0L
    }
  }

  val statusText = when {
    uiState.isCheckingReachability -> strings.networkChecking
    conn is VpnConnectionState.Disconnected -> strings.statusDisconnected
    conn is VpnConnectionState.Connecting -> "${strings.statusConnecting} (${conn.stage})"
    conn is VpnConnectionState.Connected -> strings.statusConnected
    conn is VpnConnectionState.Error -> "${strings.statusError}: ${conn.message}"
    else -> strings.statusDisconnected
  }

  val statusColor = when {
    uiState.isCheckingReachability -> CyanSecondary
    conn is VpnConnectionState.Connected -> EmeraldPrimary
    conn is VpnConnectionState.Connecting -> CyanSecondary
    conn is VpnConnectionState.Error -> CoralError
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 620.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Top Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Image(
            painter = painterResource(id = R.drawable.img_galkan_icon_1794925557579),
            contentDescription = "Galkan VPN logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .border(1.5.dp, EmeraldPrimary.copy(alpha = 0.6f), CircleShape),
          )
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "Galkan VPN",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = uiState.prefs.dpiConfig.activeProtocol.shortName,
              style = MaterialTheme.typography.labelSmall,
              color = EmeraldPrimary,
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          var langMenuExpanded by remember { mutableStateOf(false) }
          Box {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(50),
              modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { langMenuExpanded = true }
                .testTag("home_language_button"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Filled.Language,
                  contentDescription = strings.languageLabel,
                  tint = CyanSecondary,
                  modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = uiState.prefs.language.code.uppercase(),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
            DropdownMenu(
              expanded = langMenuExpanded,
              onDismissRequest = { langMenuExpanded = false },
            ) {
              AppLanguage.entries.forEach { lang ->
                DropdownMenuItem(
                  text = { Text(lang.nativeName) },
                  onClick = {
                    langMenuExpanded = false
                    onSelectLanguage(lang)
                  },
                )
              }
            }
          }
          Spacer(modifier = Modifier.width(6.dp))
          TierBadgeChip(
            isProActive = uiState.hasProAccess,
            adRemainingMs = uiState.adRemainingMs,
            strings = strings,
            onClick = onNavigatePro,
          )
          Spacer(modifier = Modifier.width(2.dp))
          IconButton(
            onClick = onNavigatePro,
            modifier = Modifier.testTag("home_pro_icon_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.WorkspacePremium,
              contentDescription = strings.navPro,
              tint = AmberPro,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Connection Status Pill
      Surface(
        color = statusColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
          .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(50))
          .testTag("connection_status_pill"),
      ) {
        Text(
          text = statusText,
          style = MaterialTheme.typography.labelLarge,
          color = statusColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Live Network Reachability Indicator
      val net = uiState.networkStatus
      val transportLabel = when (net.transport) {
        NetworkTransport.WIFI -> strings.transportWifi
        NetworkTransport.CELLULAR -> strings.transportCellular
        NetworkTransport.ETHERNET -> strings.transportEthernet
        else -> null
      }
      val reachabilityText = if (net.isNetworkAvailable) {
        if (transportLabel != null) "$transportLabel • ${strings.networkOnline}" else strings.networkOnline
      } else {
        strings.networkOffline
      }
      val reachabilityColor = if (net.isNetworkAvailable) EmeraldPrimary else CoralError
      Text(
        text = reachabilityText,
        style = MaterialTheme.typography.labelSmall,
        color = reachabilityColor,
        modifier = Modifier.testTag("network_reachability_label"),
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Central Shield Power Button
      PulsingShieldButton(
        connectionState = conn,
        strings = strings,
        onClick = onToggleVpn,
      )

      // Anti-DPI Quick Pill
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .clip(RoundedCornerShape(14.dp))
          .clickable(onClick = onNavigateStealth)
          .testTag("home_stealth_pill"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = strings.navStealth,
            tint = EmeraldPrimary,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "SNI: ${uiState.prefs.dpiConfig.customSni} • MTU ${uiState.prefs.dpiConfig.mtu}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Live Telemetry 2x2 Grid
      val connected = conn as? VpnConnectionState.Connected
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        TelemetryCard(
          label = strings.downloadLabel,
          value = connected?.let { formatSpeed(it.rxRateBps) } ?: "0 B/s",
          subValue = connected?.let { formatBytes(it.rxBytes) } ?: "0 B",
          icon = Icons.Filled.ArrowDownward,
          tint = EmeraldPrimary,
          modifier = Modifier.weight(1f),
        )
        TelemetryCard(
          label = strings.uploadLabel,
          value = connected?.let { formatSpeed(it.txRateBps) } ?: "0 B/s",
          subValue = connected?.let { formatBytes(it.txBytes) } ?: "0 B",
          icon = Icons.Filled.ArrowUpward,
          tint = CyanSecondary,
          modifier = Modifier.weight(1f),
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        TelemetryCard(
          label = strings.durationLabel,
          value = formatDuration(liveElapsedMs),
          subValue = connected?.tunnelAddress ?: "10.8.0.2",
          icon = Icons.Filled.Timer,
          tint = AmberPro,
          modifier = Modifier.weight(1f),
        )
        TelemetryCard(
          label = strings.packetsLabel,
          value = (connected?.packetCount ?: 0L).toString(),
          subValue = uiState.prefs.dpiConfig.dnsProvider.primaryIp,
          icon = Icons.Filled.Speed,
          tint = EmeraldPrimary,
          modifier = Modifier.weight(1f),
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      DataUsageMonitorCard(
        usage = uiState.dataUsage,
        strings = strings,
      )

      Spacer(modifier = Modifier.height(14.dp))

      // Active Server Selector Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
          .clip(RoundedCornerShape(18.dp))
          .clickable(onClick = onNavigateServers)
          .testTag("home_server_card"),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
              text = uiState.selectedServer.flagEmoji,
              style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = strings.activeServerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = uiState.selectedServer.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              val pingTxt = uiState.selectedServer.pingMs?.let { "${it} ms" } ?: "TCP 443"
              Text(
                text = "${uiState.selectedServer.protocol.shortName} • $pingTxt",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldPrimary,
              )
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = strings.changeServer,
              style = MaterialTheme.typography.labelLarge,
              color = EmeraldPrimary,
            )
            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = strings.changeServer,
              tint = EmeraldPrimary,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      if (!uiState.isBatteryOptimizationIgnored) {
        Card(
          colors = CardDefaults.cardColors(containerColor = AmberPro.copy(alpha = 0.1f)),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AmberPro.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .testTag("home_battery_opt_card"),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.BatteryAlert,
              contentDescription = strings.batteryOptTitle,
              tint = AmberPro,
              modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = strings.batteryOptTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = strings.batteryOptStatusRestricted,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            OutlinedButton(
              onClick = onRequestBatteryOptimization,
              modifier = Modifier.testTag("home_battery_opt_button"),
            ) {
              Text(strings.batteryOptBannerAction, color = AmberPro)
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
      }

      // Rewarded Ad Card (Free Access / Pro Trial)
      if (!uiState.prefs.isProActive) {
        Card(
          colors = CardDefaults.cardColors(containerColor = Color.Transparent),
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier
            .fillMaxWidth()
            .background(
              brush = Brush.linearGradient(
                colors = listOf(
                  EmeraldPrimary.copy(alpha = 0.16f),
                  CyanSecondary.copy(alpha = 0.12f),
                )
              ),
              shape = RoundedCornerShape(20.dp),
            )
            .border(1.dp, EmeraldPrimary.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Filled.PlayCircle,
                  contentDescription = strings.watchAdTitle,
                  tint = EmeraldPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = strings.watchAdTitle,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              if (uiState.adRemainingMs > 0L) {
                Text(
                  text = "${strings.adRemainingPrefix} ${formatDuration(uiState.adRemainingMs)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = EmeraldPrimary,
                )
              }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = strings.watchAdSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            val adLoading = uiState.rewardedAdState is RewardedAdState.Loading
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = { activity?.let { onWatchAdFreeTime(it) } },
                enabled = activity != null && !adLoading,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                  .weight(1f)
                  .testTag("watch_ad_free_button"),
              ) {
                if (adLoading) {
                  CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                  Text(strings.watchAdButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              FilledTonalButton(
                onClick = { activity?.let { onWatchAdProTrial(it) } },
                enabled = activity != null && !adLoading,
                modifier = Modifier
                  .weight(1f)
                  .testTag("watch_ad_pro_trial_button"),
              ) {
                Text(strings.watchAdProTrialButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
      }

      // Network Diagnostics Card
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
              Icon(
                imageVector = Icons.Filled.NetworkCheck,
                contentDescription = strings.runDiagnostic,
                tint = CyanSecondary,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = strings.runDiagnostic,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            OutlinedButton(
              onClick = onRunDiagnostics,
              enabled = !uiState.isRunningDiagnostics,
              modifier = Modifier.testTag("run_diagnostics_button"),
            ) {
              Text(
                if (uiState.isRunningDiagnostics) strings.diagnosticRunning else strings.runDiagnostic
              )
            }
          }

          uiState.diagnosticResult?.let { diag ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = diag.note,
              style = MaterialTheme.typography.bodySmall,
              color = if (diag.tcpReachable) EmeraldPrimary else AmberPro,
            )
            if (!diag.publicIp.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "IP: ${diag.publicIp} • DNS: ${if (diag.dnsResolved) "OK" else "Blocked"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun TelemetryCard(
  label: String,
  value: String,
  subValue: String,
  icon: ImageVector,
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = tint,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      Text(
        text = subValue,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}
