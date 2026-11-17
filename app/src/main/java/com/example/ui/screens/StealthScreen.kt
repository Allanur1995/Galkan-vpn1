package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.VpnUiState
import com.example.data.DefaultServers
import com.example.model.DnsProvider
import com.example.model.DpiBypassConfig
import com.example.model.VpnProtocol
import com.example.ui.AppStrings
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StealthScreen(
  uiState: VpnUiState,
  strings: AppStrings,
  onSelectProtocol: (VpnProtocol) -> Unit,
  onUpdateDpiConfig: ((DpiBypassConfig) -> DpiBypassConfig) -> Unit,
  onSelectDns: (DnsProvider) -> Unit,
  onToggleExcludedApp: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val dpi = uiState.prefs.dpiConfig
  var appSearch by remember { mutableStateOf("") }

  val filteredApps = remember(uiState.installedApps, appSearch) {
    if (appSearch.isBlank()) {
      uiState.installedApps.take(16)
    } else {
      uiState.installedApps.filter {
        it.label.contains(appSearch, ignoreCase = true) ||
          it.packageName.contains(appSearch, ignoreCase = true)
      }.take(20)
    }
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
        .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
      Text(
        text = strings.stealthTitle,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Visual Stealth Hero Banner
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(156.dp)
          .clip(RoundedCornerShape(20.dp))
          .border(1.dp, EmeraldPrimary.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_shield_hero_1794925557648),
          contentDescription = strings.stealthHeroTitle,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.25f), Color(0xFF070B14).copy(alpha = 0.92f))
              )
            )
            .padding(16.dp),
          contentAlignment = Alignment.BottomStart,
        ) {
          Column {
            Text(
              text = strings.stealthHeroTitle,
              style = MaterialTheme.typography.titleMedium,
              color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = strings.stealthHeroSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFCBD5E1),
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Stealth Protocol Picker
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Security, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.protocolLabel, style = MaterialTheme.typography.titleMedium)
      }
      Spacer(modifier = Modifier.height(10.dp))

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VpnProtocol.entries.forEach { protocol ->
          val selected = dpi.activeProtocol == protocol
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (selected) {
                EmeraldPrimary.copy(alpha = 0.1f)
              } else {
                MaterialTheme.colorScheme.surface
              }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) EmeraldPrimary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(14.dp),
              )
              .clip(RoundedCornerShape(14.dp))
              .clickable { onSelectProtocol(protocol) }
              .testTag("protocol_option_${protocol.name}"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = protocol.shortName,
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = protocol.fullName,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                  color = EmeraldPrimary.copy(alpha = 0.15f),
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = "DPI ${protocol.dpiBypassScore}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
                if (selected) {
                  Spacer(modifier = Modifier.width(8.dp))
                  Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = EmeraldPrimary,
                  )
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // TLS ClientHello Fragmentation + MTU Card
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
            Column(modifier = Modifier.weight(1f)) {
              Text(strings.tlsFragmentTitle, style = MaterialTheme.typography.titleSmall)
              Text(
                strings.tlsFragmentSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
              checked = dpi.tlsFragmentEnabled,
              onCheckedChange = { enabled ->
                onUpdateDpiConfig { it.copy(tlsFragmentEnabled = enabled) }
              },
              modifier = Modifier.testTag("tls_fragment_switch"),
            )
          }

          if (dpi.tlsFragmentEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(strings.fragmentBytesLabel, style = MaterialTheme.typography.labelMedium)
              Text(
                "${dpi.fragmentSizeBytes} Bytes",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldPrimary,
              )
            }
            Slider(
              value = dpi.fragmentSizeBytes.toFloat(),
              onValueChange = { v ->
                onUpdateDpiConfig { it.copy(fragmentSizeBytes = v.roundToInt()) }
              },
              valueRange = 10f..64f,
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // MTU Tuning
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Tune, contentDescription = null, tint = CyanSecondary, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(strings.mtuLabel, style = MaterialTheme.typography.labelMedium)
            }
            Text(
              "${dpi.mtu}",
              style = MaterialTheme.typography.labelSmall,
              color = CyanSecondary,
            )
          }
          Slider(
            value = dpi.mtu.toFloat(),
            onValueChange = { v ->
              onUpdateDpiConfig { it.copy(mtu = v.roundToInt()) }
            },
            valueRange = 1280f..1500f,
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // SNI Camouflage Domain
      Text(strings.sniCamouflageLabel, style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DefaultServers.recommendedSniDomains.forEach { domain ->
          FilterChip(
            selected = dpi.customSni == domain,
            onClick = { onUpdateDpiConfig { it.copy(customSni = domain) } },
            label = { Text(domain, style = MaterialTheme.typography.labelSmall) },
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedTextField(
        value = dpi.customSni,
        onValueChange = { value -> onUpdateDpiConfig { it.copy(customSni = value.trim()) } },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("custom_sni_textfield"),
      )

      Spacer(modifier = Modifier.height(18.dp))

      // Encrypted DNS (DoH)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Dns, contentDescription = null, tint = CyanSecondary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.dnsProviderLabel, style = MaterialTheme.typography.titleMedium)
      }
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DnsProvider.entries.forEach { provider ->
          FilterChip(
            selected = dpi.dnsProvider == provider,
            onClick = { onSelectDns(provider) },
            label = { Text("${provider.label} (${provider.primaryIp})") },
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Split Tunneling (App Bypass)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Apps, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.splitTunnelTitle, style = MaterialTheme.typography.titleMedium)
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = strings.splitTunnelSubtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(8.dp))

      OutlinedTextField(
        value = appSearch,
        onValueChange = { appSearch = it },
        placeholder = { Text(strings.appSearchHint) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.height(8.dp))

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
      ) {
        Column(modifier = Modifier.padding(8.dp)) {
          if (filteredApps.isEmpty()) {
            Text(
              text = strings.noAppsMatched,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(12.dp),
            )
          } else {
            filteredApps.forEach { app ->
              val isExcluded = app.packageName in uiState.prefs.excludedPackages
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .clickable { onToggleExcludedApp(app.packageName) }
                  .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = isExcluded,
                  onCheckedChange = { onToggleExcludedApp(app.packageName) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}
