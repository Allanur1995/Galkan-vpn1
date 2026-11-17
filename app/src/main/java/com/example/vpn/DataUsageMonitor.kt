package com.example.vpn

import com.example.model.VpnConnectionState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DataUsageSnapshot(
  val isActiveSession: Boolean = false,
  val hasSessionData: Boolean = false,
  val receivedBytes: Long = 0L,
  val sentBytes: Long = 0L,
  val rxRateBps: Long = 0L,
  val txRateBps: Long = 0L,
  val peakRateBps: Long = 0L,
  val averageRateBps: Long = 0L,
  val estimatedBytesPerHour: Long = 0L,
  val sessionDurationMs: Long = 0L,
  val downloadSharePercent: Int = 50,
  val uploadSharePercent: Int = 50,
  val rateHistoryBps: List<Long> = emptyList(),
) {
  val totalBytes: Long
    get() = receivedBytes + sentBytes
}

/**
 * Tracks active VPN session data consumption (MB/GB sent and received),
 * computes throughput insights (peak/average rate, hourly projection, DL/UL split),
 * and preserves the most recent completed session summary until a new connection begins.
 */
class DataUsageMonitor {

  companion object {
    private const val MAX_HISTORY_SAMPLES = 24
    private const val BYTES_PER_MB = 1024.0 * 1024.0
    private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    /**
     * Formats byte counts explicitly in MB (below 1 GB) or GB (>= 1 GB)
     * so users immediately see session MB/GB sent and received.
     */
    fun formatMbOrGb(bytes: Long): String {
      val safeBytes = bytes.coerceAtLeast(0L)
      return if (safeBytes >= 1024L * 1024L * 1024L) {
        String.format(Locale.US, "%.2f GB", safeBytes / BYTES_PER_GB)
      } else {
        String.format(Locale.US, "%.2f MB", safeBytes / BYTES_PER_MB)
      }
    }
  }

  private val _snapshot = MutableStateFlow(DataUsageSnapshot())
  val snapshot: StateFlow<DataUsageSnapshot> = _snapshot.asStateFlow()

  private var activeConnectedSinceMs: Long? = null
  private var peakRateBps: Long = 0L
  private val rateSamples = ArrayDeque<Long>(MAX_HISTORY_SAMPLES)

  fun onConnectionStateChanged(
    state: VpnConnectionState,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    when (state) {
      is VpnConnectionState.Connecting -> {
        activeConnectedSinceMs = null
        peakRateBps = 0L
        rateSamples.clear()
        _snapshot.value = DataUsageSnapshot(isActiveSession = false, hasSessionData = false)
      }

      is VpnConnectionState.Connected -> {
        if (activeConnectedSinceMs != state.connectedSinceMs) {
          activeConnectedSinceMs = state.connectedSinceMs
          peakRateBps = 0L
          rateSamples.clear()
        }

        val rx = state.rxBytes.coerceAtLeast(0L)
        val tx = state.txBytes.coerceAtLeast(0L)
        val total = rx + tx
        val currentTotalRate = (state.rxRateBps + state.txRateBps).coerceAtLeast(0L)
        if (currentTotalRate > peakRateBps) {
          peakRateBps = currentTotalRate
        }

        if (rateSamples.size >= MAX_HISTORY_SAMPLES) {
          rateSamples.removeFirst()
        }
        rateSamples.addLast(currentTotalRate)

        val durationMs = (nowMs - state.connectedSinceMs).coerceAtLeast(0L)
        val elapsedSeconds = (durationMs / 1000.0).coerceAtLeast(1.0)
        val avgRate = (total / elapsedSeconds).toLong().coerceAtLeast(0L)
        val estHourly = if (total > 0L) avgRate * 3600L else 0L

        val (dlPct, ulPct) = if (total > 0L) {
          val dl = ((rx * 100.0) / total).toInt().coerceIn(0, 100)
          dl to (100 - dl)
        } else {
          50 to 50
        }

        _snapshot.value = DataUsageSnapshot(
          isActiveSession = true,
          hasSessionData = true,
          receivedBytes = rx,
          sentBytes = tx,
          rxRateBps = state.rxRateBps.coerceAtLeast(0L),
          txRateBps = state.txRateBps.coerceAtLeast(0L),
          peakRateBps = peakRateBps,
          averageRateBps = avgRate,
          estimatedBytesPerHour = estHourly,
          sessionDurationMs = durationMs,
          downloadSharePercent = dlPct,
          uploadSharePercent = ulPct,
          rateHistoryBps = rateSamples.toList(),
        )
      }

      is VpnConnectionState.Disconnected,
      is VpnConnectionState.Error -> {
        val current = _snapshot.value
        if (current.isActiveSession) {
          _snapshot.value = current.copy(
            isActiveSession = false,
            hasSessionData = current.totalBytes > 0L || current.sessionDurationMs > 0L,
            rxRateBps = 0L,
            txRateBps = 0L,
          )
        }
        activeConnectedSinceMs = null
      }
    }
  }

  fun resetSummary() {
    if (!_snapshot.value.isActiveSession) {
      peakRateBps = 0L
      rateSamples.clear()
      _snapshot.value = DataUsageSnapshot()
    }
  }
}
