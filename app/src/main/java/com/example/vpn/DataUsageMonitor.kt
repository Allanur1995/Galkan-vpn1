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
  val lifetimeReceivedBytes: Long = 0L,
  val lifetimeSentBytes: Long = 0L,
) {
  val totalBytes: Long
    get() = receivedBytes + sentBytes

  val lifetimeTotalBytes: Long
    get() = lifetimeReceivedBytes + lifetimeSentBytes
}

/**
 * Tracks active VPN session and cumulative service-lifetime data consumption
 * (bytes sent and received), computes live delta throughput rates (B/s, peak/avg rate,
 * hourly projection, DL/UL split), and preserves the most recent completed session summary
 * until a new connection begins.
 */
class DataUsageMonitor {

  companion object {
    private const val MAX_HISTORY_SAMPLES = 24
    private const val BYTES_PER_KB = 1024.0
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

    /**
     * Formats live traffic counters across B, KB, MB, and GB so even initial
     * handshake and small packet bursts visibly increment in real time.
     */
    fun formatLiveCounter(bytes: Long): String {
      val safe = bytes.coerceAtLeast(0L)
      return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / BYTES_PER_KB)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / BYTES_PER_MB)
        else -> String.format(Locale.US, "%.2f GB", safe / BYTES_PER_GB)
      }
    }
  }

  private val lock = Any()
  private val _snapshot = MutableStateFlow(DataUsageSnapshot())
  val snapshot: StateFlow<DataUsageSnapshot> = _snapshot.asStateFlow()

  private var activeConnectedSinceMs: Long? = null
  private var peakRateBps: Long = 0L
  private val rateSamples = ArrayDeque<Long>(MAX_HISTORY_SAMPLES)

  private var prevSampleRxBytes: Long = 0L
  private var prevSampleTxBytes: Long = 0L
  private var prevSampleTimeMs: Long = 0L

  private var accumulatedLifetimeRxBytes: Long = 0L
  private var accumulatedLifetimeTxBytes: Long = 0L
  private var currentSessionCommittedRx: Long = 0L
  private var currentSessionCommittedTx: Long = 0L

  fun beginSession(
    connectedSinceMs: Long,
    nowMs: Long = System.currentTimeMillis(),
  ): DataUsageSnapshot = synchronized(lock) {
    commitEndedSessionLocked()
    activeConnectedSinceMs = connectedSinceMs
    peakRateBps = 0L
    rateSamples.clear()
    prevSampleRxBytes = 0L
    prevSampleTxBytes = 0L
    prevSampleTimeMs = nowMs
    currentSessionCommittedRx = 0L
    currentSessionCommittedTx = 0L

    val next = DataUsageSnapshot(
      isActiveSession = true,
      hasSessionData = true,
      lifetimeReceivedBytes = accumulatedLifetimeRxBytes,
      lifetimeSentBytes = accumulatedLifetimeTxBytes,
    )
    _snapshot.value = next
    next
  }

  /**
   * Calculates instantaneous rx/tx rates and updates session + lifetime byte totals directly
   * from cumulative TUN/SOCKS5 byte counters inside [VpnService] / [HevVpnEngine].
   */
  fun recordTrafficCounters(
    rxBytes: Long,
    txBytes: Long,
    connectedSinceMs: Long,
    nowMs: Long = System.currentTimeMillis(),
  ): DataUsageSnapshot = synchronized(lock) {
    val safeRx = rxBytes.coerceAtLeast(0L)
    val safeTx = txBytes.coerceAtLeast(0L)

    if (activeConnectedSinceMs != connectedSinceMs) {
      commitEndedSessionLocked()
      activeConnectedSinceMs = connectedSinceMs
      peakRateBps = 0L
      rateSamples.clear()
      prevSampleRxBytes = 0L
      prevSampleTxBytes = 0L
      prevSampleTimeMs = connectedSinceMs
      currentSessionCommittedRx = 0L
      currentSessionCommittedTx = 0L
    }

    val deltaMs = (nowMs - prevSampleTimeMs).coerceAtLeast(250L)
    val deltaRx = (safeRx - prevSampleRxBytes).coerceAtLeast(0L)
    val deltaTx = (safeTx - prevSampleTxBytes).coerceAtLeast(0L)
    val elapsedSinceSampleSec = deltaMs / 1000.0
    val rxRate = (deltaRx / elapsedSinceSampleSec).toLong().coerceAtLeast(0L)
    val txRate = (deltaTx / elapsedSinceSampleSec).toLong().coerceAtLeast(0L)

    prevSampleRxBytes = safeRx
    prevSampleTxBytes = safeTx
    prevSampleTimeMs = nowMs
    currentSessionCommittedRx = safeRx
    currentSessionCommittedTx = safeTx

    updateActiveSnapshotLocked(
      rx = safeRx,
      tx = safeTx,
      rxRateBps = rxRate,
      txRateBps = txRate,
      connectedSinceMs = connectedSinceMs,
      nowMs = nowMs,
    )
  }

  fun onConnectionStateChanged(
    state: VpnConnectionState,
    nowMs: Long = System.currentTimeMillis(),
  ): DataUsageSnapshot = synchronized(lock) {
    when (state) {
      is VpnConnectionState.Connecting -> {
        commitEndedSessionLocked()
        activeConnectedSinceMs = null
        peakRateBps = 0L
        rateSamples.clear()
        prevSampleRxBytes = 0L
        prevSampleTxBytes = 0L
        prevSampleTimeMs = nowMs
        val next = DataUsageSnapshot(
          isActiveSession = false,
          hasSessionData = false,
          lifetimeReceivedBytes = accumulatedLifetimeRxBytes,
          lifetimeSentBytes = accumulatedLifetimeTxBytes,
        )
        _snapshot.value = next
        next
      }

      is VpnConnectionState.Connected -> {
        if (activeConnectedSinceMs != state.connectedSinceMs) {
          commitEndedSessionLocked()
          activeConnectedSinceMs = state.connectedSinceMs
          peakRateBps = 0L
          rateSamples.clear()
          prevSampleRxBytes = 0L
          prevSampleTxBytes = 0L
          prevSampleTimeMs = state.connectedSinceMs
          currentSessionCommittedRx = 0L
          currentSessionCommittedTx = 0L
        }

        val rx = state.rxBytes.coerceAtLeast(0L)
        val tx = state.txBytes.coerceAtLeast(0L)
        currentSessionCommittedRx = rx
        currentSessionCommittedTx = tx
        prevSampleRxBytes = rx
        prevSampleTxBytes = tx
        prevSampleTimeMs = nowMs

        updateActiveSnapshotLocked(
          rx = rx,
          tx = tx,
          rxRateBps = state.rxRateBps.coerceAtLeast(0L),
          txRateBps = state.txRateBps.coerceAtLeast(0L),
          connectedSinceMs = state.connectedSinceMs,
          nowMs = nowMs,
        )
      }

      is VpnConnectionState.Disconnected,
      is VpnConnectionState.Error -> {
        val current = _snapshot.value
        commitEndedSessionLocked()
        val ended = if (current.isActiveSession) {
          current.copy(
            isActiveSession = false,
            hasSessionData = current.totalBytes > 0L || current.sessionDurationMs > 0L,
            rxRateBps = 0L,
            txRateBps = 0L,
            lifetimeReceivedBytes = accumulatedLifetimeRxBytes,
            lifetimeSentBytes = accumulatedLifetimeTxBytes,
          )
        } else {
          current.copy(
            lifetimeReceivedBytes = accumulatedLifetimeRxBytes,
            lifetimeSentBytes = accumulatedLifetimeTxBytes,
          )
        }
        activeConnectedSinceMs = null
        _snapshot.value = ended
        ended
      }
    }
  }

  private fun updateActiveSnapshotLocked(
    rx: Long,
    tx: Long,
    rxRateBps: Long,
    txRateBps: Long,
    connectedSinceMs: Long,
    nowMs: Long,
  ): DataUsageSnapshot {
    val total = rx + tx
    val currentTotalRate = (rxRateBps + txRateBps).coerceAtLeast(0L)
    if (currentTotalRate > peakRateBps) {
      peakRateBps = currentTotalRate
    }

    if (rateSamples.size >= MAX_HISTORY_SAMPLES) {
      rateSamples.removeFirst()
    }
    rateSamples.addLast(currentTotalRate)

    val durationMs = (nowMs - connectedSinceMs).coerceAtLeast(0L)
    val elapsedSeconds = (durationMs / 1000.0).coerceAtLeast(1.0)
    val avgRate = (total / elapsedSeconds).toLong().coerceAtLeast(0L)
    val estHourly = if (total > 0L) avgRate * 3600L else 0L

    val (dlPct, ulPct) = if (total > 0L) {
      val dl = ((rx * 100.0) / total).toInt().coerceIn(0, 100)
      dl to (100 - dl)
    } else {
      50 to 50
    }

    val next = DataUsageSnapshot(
      isActiveSession = true,
      hasSessionData = true,
      receivedBytes = rx,
      sentBytes = tx,
      rxRateBps = rxRateBps,
      txRateBps = txRateBps,
      peakRateBps = peakRateBps,
      averageRateBps = avgRate,
      estimatedBytesPerHour = estHourly,
      sessionDurationMs = durationMs,
      downloadSharePercent = dlPct,
      uploadSharePercent = ulPct,
      rateHistoryBps = rateSamples.toList(),
      lifetimeReceivedBytes = accumulatedLifetimeRxBytes + rx,
      lifetimeSentBytes = accumulatedLifetimeTxBytes + tx,
    )
    _snapshot.value = next
    return next
  }

  private fun commitEndedSessionLocked() {
    if (currentSessionCommittedRx > 0L || currentSessionCommittedTx > 0L) {
      accumulatedLifetimeRxBytes += currentSessionCommittedRx
      accumulatedLifetimeTxBytes += currentSessionCommittedTx
      currentSessionCommittedRx = 0L
      currentSessionCommittedTx = 0L
    }
  }

  fun resetSummary() = synchronized(lock) {
    if (!_snapshot.value.isActiveSession) {
      peakRateBps = 0L
      rateSamples.clear()
      _snapshot.value = DataUsageSnapshot(
        lifetimeReceivedBytes = accumulatedLifetimeRxBytes,
        lifetimeSentBytes = accumulatedLifetimeTxBytes,
      )
    }
  }
}
