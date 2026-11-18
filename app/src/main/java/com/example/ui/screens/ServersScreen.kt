package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.VpnUiState
import com.example.model.VpnServer
import com.example.ui.AppStrings
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary

private enum class ServerFilter { ALL, FREE, PRO, CUSTOM }

@Composable
fun ServersScreen(
  uiState: VpnUiState,
  strings: AppStrings,
  onSelectServer: (VpnServer) -> Unit,
  onAutoSelectFastest: () -> Unit,
  onPingAll: () -> Unit,
  onImportUri: (String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
  onDeleteCustomServer: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf(ServerFilter.ALL) }
  var sortByLatency by remember { mutableStateOf(false) }
  var showImportDialog by remember { mutableStateOf(false) }

  val filteredServers = remember(uiState.servers, searchQuery, selectedFilter, sortByLatency) {
    val base = uiState.servers.filter { srv ->
      val matchesFilter = when (selectedFilter) {
        ServerFilter.ALL -> true
        ServerFilter.FREE -> !srv.isProOnly
        ServerFilter.PRO -> srv.isProOnly
        ServerFilter.CUSTOM -> srv.isCustom
      }
      val matchesSearch = searchQuery.isBlank() ||
        srv.name.contains(searchQuery, ignoreCase = true) ||
        srv.city.contains(searchQuery, ignoreCase = true) ||
        srv.countryName.contains(searchQuery, ignoreCase = true) ||
        srv.countryCode.contains(searchQuery, ignoreCase = true) ||
        srv.protocol.shortName.contains(searchQuery, ignoreCase = true)
      matchesFilter && matchesSearch
    }
    if (sortByLatency) {
      base.sortedBy { it.pingMs ?: Int.MAX_VALUE }
    } else {
      base
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
        .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = strings.serversTitle,
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilledTonalButton(
            onClick = onPingAll,
            enabled = !uiState.isPingingServers,
            modifier = Modifier.testTag("ping_all_servers_button"),
          ) {
            if (uiState.isPingingServers) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Filled.Refresh, contentDescription = strings.pingAll, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.pingAll)
          }

          Button(
            onClick = { showImportDialog = true },
            modifier = Modifier.testTag("open_import_uri_button"),
          ) {
            Icon(Icons.Filled.AddLink, contentDescription = strings.importConfig, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.importConfig)
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(strings.serverSearchHint) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("server_search_input"),
      )

      Spacer(modifier = Modifier.height(10.dp))

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
          FilterChip(
            selected = selectedFilter == ServerFilter.ALL,
            onClick = { selectedFilter = ServerFilter.ALL },
            label = { Text(strings.filterAll) },
          )
        }
        item {
          FilterChip(
            selected = selectedFilter == ServerFilter.FREE,
            onClick = { selectedFilter = ServerFilter.FREE },
            label = { Text(strings.filterFree) },
          )
        }
        item {
          FilterChip(
            selected = selectedFilter == ServerFilter.PRO,
            onClick = { selectedFilter = ServerFilter.PRO },
            label = { Text(strings.filterPro) },
          )
        }
        item {
          FilterChip(
            selected = selectedFilter == ServerFilter.CUSTOM,
            onClick = { selectedFilter = ServerFilter.CUSTOM },
            label = { Text(strings.filterCustom) },
          )
        }
        item {
          FilterChip(
            selected = sortByLatency,
            onClick = { sortByLatency = !sortByLatency },
            leadingIcon = {
              Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            label = { Text(strings.sortByPing) },
            modifier = Modifier.testTag("sort_by_latency_chip"),
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      FilledTonalButton(
        onClick = onAutoSelectFastest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("auto_fastest_server_button"),
      ) {
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(strings.autoFastest)
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item(key = "anti_dpi_mode_banner") {
          Surface(
            color = CyanSecondary.copy(alpha = 0.1f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, CyanSecondary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
              .testTag("anti_dpi_preset_banner"),
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = strings.antiDpiPresetBannerTitle,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanSecondary,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = strings.antiDpiPresetBannerBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        items(filteredServers, key = { it.id }) { server ->
          val isSelected = server.id == uiState.selectedServer.id
          val isLocked = server.isProOnly && !uiState.hasProAccess
          ServerCard(
            server = server,
            strings = strings,
            isSelected = isSelected,
            isLocked = isLocked,
            onClick = { onSelectServer(server) },
            onDelete = if (server.isCustom) {
              { onDeleteCustomServer(server.id) }
            } else {
              null
            },
          )
        }
      }
    }
  }

  if (showImportDialog) {
    ImportConfigDialog(
      strings = strings,
      onDismiss = { showImportDialog = false },
      onImport = { uri, setError ->
        onImportUri(
          uri,
          { showImportDialog = false },
          { err -> setError(err) },
        )
      },
    )
  }
}

@Composable
private fun ServerCard(
  server: VpnServer,
  strings: AppStrings,
  isSelected: Boolean,
  isLocked: Boolean,
  onClick: () -> Unit,
  onDelete: (() -> Unit)?,
) {
  val borderColor = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline

  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        EmeraldPrimary.copy(alpha = 0.08f)
      } else {
        MaterialTheme.colorScheme.surface
      }
    ),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .testTag("server_item_${server.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = server.flagEmoji, style = MaterialTheme.typography.headlineMedium)
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = server.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (server.isProOnly) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
              color = AmberPro.copy(alpha = 0.16f),
              shape = RoundedCornerShape(6.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Filled.WorkspacePremium,
                  contentDescription = "Pro",
                  tint = AmberPro,
                  modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = "PRO",
                  style = MaterialTheme.typography.labelSmall,
                  color = AmberPro,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))
        val locationText = if (server.countryName.isNotBlank()) {
          "${server.countryName} • ${server.city}"
        } else {
          server.city
        }
        Text(
          text = locationText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${server.protocol.shortName} • ${strings.loadLabel} ${server.loadPercent}%",
          style = MaterialTheme.typography.labelSmall,
          color = CyanSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column(horizontalAlignment = Alignment.End) {
        LatencySignalIndicator(
          pingMs = server.pingMs,
          strings = strings,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
              Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Delete",
                tint = CoralError,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          if (isLocked) {
            Icon(
              imageVector = Icons.Filled.Lock,
              contentDescription = "Pro locked",
              tint = AmberPro,
              modifier = Modifier.size(20.dp),
            )
          } else if (isSelected) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = "Selected",
              tint = EmeraldPrimary,
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LatencySignalIndicator(
  pingMs: Int?,
  strings: AppStrings,
) {
  val (activeBars, color, qualityLabel) = when {
    pingMs == null -> Triple(0, MaterialTheme.colorScheme.onSurfaceVariant, null)
    pingMs < 95 -> Triple(4, EmeraldPrimary, strings.latencyFast)
    pingMs < 190 -> Triple(3, AmberPro, strings.latencyModerate)
    else -> Triple(2, CoralError, strings.latencySlow)
  }

  Column(horizontalAlignment = Alignment.End) {
    Row(
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      for (barIndex in 1..4) {
        val barColor = if (barIndex <= activeBars) {
          color
        } else {
          MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        }
        Box(
          modifier = Modifier
            .width(3.5.dp)
            .height((4 + barIndex * 3).dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(barColor),
        )
      }
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = pingMs?.let { "${it}ms" } ?: "— ms",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = color,
      )
    }
    if (qualityLabel != null) {
      Text(
        text = qualityLabel,
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = 0.85f),
      )
    }
  }
}

@Composable
private fun ImportConfigDialog(
  strings: AppStrings,
  onDismiss: () -> Unit,
  onImport: (String, setError: (String) -> Unit) -> Unit,
) {
  var uriInput by remember { mutableStateOf("") }
  var errorText by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.importDialogTitle, style = MaterialTheme.typography.titleMedium) },
    text = {
      Column {
        OutlinedTextField(
          value = uriInput,
          onValueChange = {
            uriInput = it
            errorText = null
          },
          placeholder = { Text(strings.importDialogHint) },
          minLines = 3,
          maxLines = 5,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("import_uri_textfield"),
        )
        if (errorText != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorText!!,
            style = MaterialTheme.typography.bodySmall,
            color = CoralError,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onImport(uriInput) { errorText = it } },
        modifier = Modifier.testTag("confirm_import_uri_button"),
      ) {
        Text(strings.importAction)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(strings.cancelAction)
      }
    },
  )
}
