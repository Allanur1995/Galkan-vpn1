package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.model.ServerNodeStatus
import com.example.model.VpnServer
import com.example.ui.AppStrings
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary

private enum class DrawerNodeFilter { ALL, FREE, PRO }

/**
 * Interactive Modal Bottom Sheet drawer allowing users to quickly inspect and switch
 * among pre-defined and custom server nodes with live latency and node status badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectionDrawer(
  uiState: VpnUiState,
  strings: AppStrings,
  onDismiss: () -> Unit,
  onSelectServer: (VpnServer) -> Unit,
  onAutoSelectFastest: () -> Unit,
  onPingAll: () -> Unit,
  onOpenFullServerManager: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedFilter by remember { mutableStateOf(DrawerNodeFilter.ALL) }
  var sortByLatency by remember { mutableStateOf(true) }

  val displayedServers = remember(uiState.servers, selectedFilter, sortByLatency) {
    val filtered = uiState.servers.filter { srv ->
      when (selectedFilter) {
        DrawerNodeFilter.ALL -> true
        DrawerNodeFilter.FREE -> !srv.isProOnly
        DrawerNodeFilter.PRO -> srv.isProOnly
      }
    }
    if (sortByLatency) {
      filtered.sortedWith(
        compareBy<VpnServer> { it.id != uiState.selectedServer.id }
          .thenBy { it.pingMs ?: Int.MAX_VALUE },
      )
    } else {
      filtered
    }
  }

  val onlineCount = remember(uiState.servers) {
    uiState.servers.count {
      it.nodeStatus == ServerNodeStatus.ONLINE ||
        it.nodeStatus == ServerNodeStatus.CONNECTED ||
        it.nodeStatus == ServerNodeStatus.HIGH_LOAD
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = Modifier.testTag("server_selection_drawer"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 18.dp),
    ) {
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
            imageVector = Icons.Filled.Public,
            contentDescription = strings.serverDrawerTitle,
            tint = EmeraldPrimary,
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = strings.serverDrawerTitle,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "$onlineCount / ${uiState.servers.size} ${strings.nodeStatusOnline}",
              style = MaterialTheme.typography.labelSmall,
              color = EmeraldPrimary,
            )
          }
        }

        FilledTonalButton(
          onClick = onPingAll,
          enabled = !uiState.isPingingServers,
          modifier = Modifier.testTag("drawer_ping_all_button"),
        ) {
          if (uiState.isPingingServers) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
          } else {
            Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = strings.pingAll,
              modifier = Modifier.size(16.dp),
            )
          }
          Spacer(modifier = Modifier.width(6.dp))
          Text(strings.pingAll)
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
          FilterChip(
            selected = selectedFilter == DrawerNodeFilter.ALL,
            onClick = { selectedFilter = DrawerNodeFilter.ALL },
            label = { Text(strings.filterAll) },
          )
        }
        item {
          FilterChip(
            selected = selectedFilter == DrawerNodeFilter.FREE,
            onClick = { selectedFilter = DrawerNodeFilter.FREE },
            label = { Text(strings.filterFree) },
          )
        }
        item {
          FilterChip(
            selected = selectedFilter == DrawerNodeFilter.PRO,
            onClick = { selectedFilter = DrawerNodeFilter.PRO },
            label = { Text(strings.filterPro) },
          )
        }
        item {
          FilterChip(
            selected = sortByLatency,
            onClick = { sortByLatency = !sortByLatency },
            leadingIcon = {
              Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
            },
            label = { Text(strings.sortByPing) },
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      FilledTonalButton(
        onClick = {
          onAutoSelectFastest()
          onDismiss()
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("drawer_auto_fastest_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Bolt,
          contentDescription = strings.autoFastest,
          tint = EmeraldPrimary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(strings.autoFastest)
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyColumn(
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 380.dp)
          .testTag("server_drawer_list"),
      ) {
        items(displayedServers, key = { it.id }) { server ->
          val isSelected = server.id == uiState.selectedServer.id
          val isLocked = server.isProOnly && !uiState.hasProAccess
          ServerNodeCard(
            server = server,
            strings = strings,
            isSelected = isSelected,
            isLocked = isLocked,
            onClick = {
              onSelectServer(server)
              onDismiss()
            },
            itemTestTag = "drawer_server_item_${server.id}",
          )
        }
      }

      OutlinedButton(
        onClick = {
          onDismiss()
          onOpenFullServerManager()
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 14.dp)
          .testTag("drawer_open_full_list_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Tune,
          contentDescription = strings.serverDrawerFullList,
          modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.serverDrawerFullList)
      }
    }
  }
}

@Composable
fun ServerNodeCard(
  server: VpnServer,
  strings: AppStrings,
  isSelected: Boolean,
  isLocked: Boolean,
  onClick: () -> Unit,
  onDelete: (() -> Unit)? = null,
  itemTestTag: String = "server_item_${server.id}",
) {
  val borderColor = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline

  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        EmeraldPrimary.copy(alpha = 0.08f)
      } else {
        MaterialTheme.colorScheme.surface
      },
    ),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .testTag(itemTestTag),
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = server.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (server.isProOnly) {
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
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          NodeStatusPill(
            status = server.nodeStatus,
            strings = strings,
            modifier = Modifier.testTag("server_status_${server.id}"),
          )
          Text(
            text = "${server.protocol.shortName} • ${strings.loadLabel} ${server.loadPercent}%",
            style = MaterialTheme.typography.labelSmall,
            color = CyanSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column(horizontalAlignment = Alignment.End) {
        LatencySignalIndicator(
          pingMs = server.pingMs,
          nodeStatus = server.nodeStatus,
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
fun NodeStatusPill(
  status: ServerNodeStatus,
  strings: AppStrings,
  modifier: Modifier = Modifier,
) {
  val (label, color) = when (status) {
    ServerNodeStatus.CONNECTED -> strings.nodeStatusConnected to EmeraldPrimary
    ServerNodeStatus.ONLINE -> strings.nodeStatusOnline to EmeraldPrimary
    ServerNodeStatus.HIGH_LOAD -> strings.nodeStatusHighLoad to AmberPro
    ServerNodeStatus.CHECKING -> strings.nodeStatusChecking to CyanSecondary
    ServerNodeStatus.OFFLINE -> strings.nodeStatusOffline to CoralError
    ServerNodeStatus.STANDBY -> strings.nodeStatusStandby to MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    color = color.copy(alpha = 0.13f),
    shape = RoundedCornerShape(50),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(color),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun LatencySignalIndicator(
  pingMs: Int?,
  nodeStatus: ServerNodeStatus,
  strings: AppStrings,
) {
  val (activeBars, color, qualityLabel) = when {
    nodeStatus == ServerNodeStatus.CHECKING -> Triple(1, CyanSecondary, strings.nodeStatusChecking)
    nodeStatus == ServerNodeStatus.OFFLINE -> Triple(0, CoralError, strings.nodeStatusOffline)
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
        text = when {
          pingMs != null -> "${pingMs}ms"
          nodeStatus == ServerNodeStatus.OFFLINE -> "timeout"
          else -> "— ms"
        },
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
