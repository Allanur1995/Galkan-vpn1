package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.VpnConnectionState
import com.example.monetization.AdManager
import com.example.ui.AppStrings
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.vpn.DataUsageMonitor
import com.example.vpn.DataUsageSnapshot
import java.util.Locale

@Composable
fun PulsingShieldButton(
  connectionState: VpnConnectionState,
  strings: AppStrings,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isConnected = connectionState is VpnConnectionState.Connected
  val isConnecting = connectionState is VpnConnectionState.Connecting
  val isError = connectionState is VpnConnectionState.Error

  val accentColor by animateColorAsState(
    targetValue = when {
      isConnected -> EmeraldPrimary
      isConnecting -> CyanSecondary
      isError -> CoralError
      else -> MaterialTheme.colorScheme.outline
    },
    animationSpec = tween(400),
    label = "shield_accent",
  )

  val buttonScale by animateFloatAsState(
    targetValue = if (isConnecting) 0.96f else 1f,
    animationSpec = spring(),
    label = "shield_scale",
  )

  val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
  val pulseProgress by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = if (isConnecting) 1100 else 2200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "pulse_progress",
  )

  val orbitRotation by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 3600, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "orbit_rotation",
  )

  Box(
    modifier = modifier.size(216.dp),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val baseRadius = size.minDimension * 0.34f

      if (isConnected || isConnecting) {
        val expandingRadius = baseRadius + (size.minDimension * 0.16f * pulseProgress)
        drawCircle(
          color = accentColor.copy(alpha = (1f - pulseProgress) * 0.45f),
          radius = expandingRadius,
          center = center,
          style = Stroke(width = 3.dp.toPx()),
        )
      }

      drawCircle(
        color = accentColor.copy(alpha = if (isConnected) 0.25f else 0.14f),
        radius = baseRadius * 1.24f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
      )

      if (isConnecting) {
        drawArc(
          color = CyanSecondary,
          startAngle = orbitRotation,
          sweepAngle = 90f,
          useCenter = false,
          style = Stroke(width = 3.5.dp.toPx()),
        )
      }
    }

    val coreBrush = Brush.radialGradient(
      colors = if (isConnected) {
        listOf(EmeraldPrimary.copy(alpha = 0.28f), MaterialTheme.colorScheme.surface)
      } else if (isConnecting) {
        listOf(CyanSecondary.copy(alpha = 0.24f), MaterialTheme.colorScheme.surface)
      } else {
        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
      }
    )

    Column(
      modifier = Modifier
        .size(148.dp)
        .scale(buttonScale)
        .clip(CircleShape)
        .background(coreBrush)
        .border(
          width = 2.5.dp,
          color = if (isConnected || isConnecting) accentColor else MaterialTheme.colorScheme.outline,
          shape = CircleShape,
        )
        .minimumInteractiveComponentSize()
        .clickable(onClick = onClick)
        .testTag("vpn_power_button"),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = when {
          isConnected -> Icons.Filled.GppGood
          isConnecting -> Icons.Filled.Shield
          else -> Icons.Filled.PowerSettingsNew
        },
        contentDescription = if (isConnected) strings.tapToDisconnect else strings.tapToConnect,
        tint = if (isConnected || isConnecting) accentColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(46.dp),
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = if (isConnected || isConnecting) strings.tapToDisconnect else strings.tapToConnect,
        style = MaterialTheme.typography.labelMedium,
        color = if (isConnected || isConnecting) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun TierBadgeChip(
  isProActive: Boolean,
  adRemainingMs: Long,
  strings: AppStrings,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val (label, bg, fg, icon) = when {
    isProActive -> Quadruple(
      strings.tierProActive,
      AmberPro.copy(alpha = 0.16f),
      AmberPro,
      Icons.Filled.WorkspacePremium,
    )
    adRemainingMs > 0L -> Quadruple(
      "${strings.tierAdPass} ${formatDuration(adRemainingMs)}",
      EmeraldPrimary.copy(alpha = 0.16f),
      EmeraldPrimary,
      Icons.Filled.Shield,
    )
    else -> Quadruple(
      strings.tierFreeReady,
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
      Icons.Filled.Lock,
    )
  }

  Surface(
    color = bg,
    shape = RoundedCornerShape(50),
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(50))
      .minimumInteractiveComponentSize()
      .clickable(onClick = onClick)
      .testTag("tier_badge_chip"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = fg,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = fg,
      )
    }
  }
}

@Composable
fun AdBannerFooter(
  adManager: AdManager,
  isProActive: Boolean,
  modifier: Modifier = Modifier,
) {
  if (isProActive) return

  val reloadTick by adManager.bannerReloadTick.collectAsStateWithLifecycle()
  var bannerLoaded by remember { mutableStateOf(false) }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = modifier
      .fillMaxWidth()
      .testTag("admob_banner_container"),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = if (bannerLoaded) 4.dp else 0.dp),
      contentAlignment = Alignment.Center,
    ) {
      key(reloadTick) {
        AndroidView(
          factory = { ctx ->
            adManager.createBannerAdView(ctx) { loaded ->
              bannerLoaded = loaded
            }
          },
          onRelease = { adView -> adView.destroy() },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

fun formatDuration(millis: Long): String {
  val totalSec = (millis / 1000L).coerceAtLeast(0L)
  val h = totalSec / 3600L
  val m = (totalSec % 3600L) / 60L
  val s = totalSec % 60L
  return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

fun formatBytes(bytes: Long): String {
  return when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }
}

fun formatSpeed(bps: Long): String {
  return when {
    bps < 1024L -> "$bps B/s"
    bps < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
    else -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
  }
}

@Composable
fun DataUsageMonitorCard(
  usage: DataUsageSnapshot,
  strings: AppStrings,
  modifier: Modifier = Modifier,
) {
  val badgeLabel = when {
    usage.isActiveSession -> strings.dataUsageActiveBadge
    usage.hasSessionData -> strings.dataUsageLastSessionBadge
    else -> strings.dataUsageIdleBadge
  }
  val badgeColor = when {
    usage.isActiveSession -> EmeraldPrimary
    usage.hasSessionData -> CyanSecondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(18.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
      .testTag("data_usage_monitor_card"),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
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
            imageVector = Icons.Filled.DataUsage,
            contentDescription = strings.dataUsageTitle,
            tint = CyanSecondary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = strings.dataUsageTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
          color = badgeColor.copy(alpha = 0.14f),
          shape = RoundedCornerShape(50),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (usage.isActiveSession) {
              Box(
                modifier = Modifier
                  .size(7.dp)
                  .clip(CircleShape)
                  .background(EmeraldPrimary)
                  .testTag("data_usage_live_indicator"),
              )
            }
            Text(
              text = badgeLabel,
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
              color = badgeColor,
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text(
            text = strings.dataUsageTotalLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (usage.lifetimeTotalBytes > usage.totalBytes) {
            Text(
              text = "${strings.dataUsageCumulativeLabel}: ${DataUsageMonitor.formatLiveCounter(usage.lifetimeTotalBytes)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("data_usage_lifetime_text"),
            )
          }
        }
        Text(
          text = DataUsageMonitor.formatLiveCounter(usage.totalBytes),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("data_usage_total_text"),
        )
      }

      // Received vs Sent live byte breakdown
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          color = EmeraldPrimary.copy(alpha = 0.08f),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .border(1.dp, EmeraldPrimary.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = strings.dataUsageReceivedLabel,
                tint = EmeraldPrimary,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = strings.dataUsageReceivedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = DataUsageMonitor.formatLiveCounter(usage.receivedBytes),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = EmeraldPrimary,
              modifier = Modifier.testTag("data_usage_received_text"),
            )
            Text(
              text = "${usage.downloadSharePercent}% • ${formatSpeed(usage.rxRateBps)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Surface(
          color = CyanSecondary.copy(alpha = 0.08f),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .border(1.dp, CyanSecondary.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = strings.dataUsageSentLabel,
                tint = CyanSecondary,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = strings.dataUsageSentLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = DataUsageMonitor.formatLiveCounter(usage.sentBytes),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = CyanSecondary,
              modifier = Modifier.testTag("data_usage_sent_text"),
            )
            Text(
              text = "${usage.uploadSharePercent}% • ${formatSpeed(usage.txRateBps)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      // Download vs Upload ratio bar
      val dlRatio = if (usage.totalBytes > 0L) {
        (usage.receivedBytes.toFloat() / usage.totalBytes.toFloat()).coerceIn(0.05f, 0.95f)
      } else {
        0.5f
      }
      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp),
      ) {
        val dlWidth = size.width * dlRatio
        val gap = 3.dp.toPx()
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
          color = EmeraldPrimary,
          topLeft = Offset.Zero,
          size = Size((dlWidth - gap / 2f).coerceAtLeast(0f), size.height),
          cornerRadius = radius,
        )
        drawRoundRect(
          color = CyanSecondary,
          topLeft = Offset(dlWidth + gap / 2f, 0f),
          size = Size((size.width - dlWidth - gap / 2f).coerceAtLeast(0f), size.height),
          cornerRadius = radius,
        )
      }

      // Live throughput sparkline when active/history has samples
      if (usage.rateHistoryBps.size >= 2) {
        val maxSample = usage.rateHistoryBps.maxOrNull()?.coerceAtLeast(1024L) ?: 1024L
        val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        Canvas(
          modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        ) {
          drawLine(
            color = trackColor,
            start = Offset(0f, size.height - 1.dp.toPx()),
            end = Offset(size.width, size.height - 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
          )
          val stepX = size.width / (usage.rateHistoryBps.size - 1).coerceAtLeast(1)
          val linePath = Path()
          usage.rateHistoryBps.forEachIndexed { idx, bps ->
            val x = idx * stepX
            val ratio = (bps.toFloat() / maxSample.toFloat()).coerceIn(0f, 1f)
            val y = size.height - (ratio * (size.height - 4.dp.toPx())) - 2.dp.toPx()
            if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
          }
          drawPath(
            path = linePath,
            brush = Brush.horizontalGradient(listOf(EmeraldPrimary, CyanSecondary)),
            style = Stroke(
              width = 2.dp.toPx(),
              cap = StrokeCap.Round,
              join = StrokeJoin.Round,
            ),
          )
        }
      }

      // Consumption Insights Footer (Peak, Avg Rate, Estimated/Hour)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        UsageInsightMetric(
          label = strings.dataUsagePeakSpeedLabel,
          value = formatSpeed(usage.peakRateBps),
          modifier = Modifier.weight(1f),
        )
        UsageInsightMetric(
          label = strings.dataUsageAvgSpeedLabel,
          value = formatSpeed(usage.averageRateBps),
          modifier = Modifier.weight(1f),
        )
        UsageInsightMetric(
          label = strings.dataUsageHourlyEstLabel,
          value = DataUsageMonitor.formatMbOrGb(usage.estimatedBytesPerHour),
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun UsageInsightMetric(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
