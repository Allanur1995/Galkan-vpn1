package com.example

import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.vpn.DataUsageMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataUsageMonitorTest {

  private val sampleServer = VpnServer(
    id = "de_frankfurt",
    name = "Germany • Frankfurt",
    countryCode = "DE",
    countryName = "Germany",
    flagEmoji = "🇩🇪",
    city = "Frankfurt",
    host = "de-stealth.galkanvpn.net",
    port = 443,
    protocol = VpnProtocol.VLESS_REALITY,
  )

  @Test
  fun `formatMbOrGb renders MB under 1 GB and GB at or above 1 GB`() {
    assertEquals("0.00 MB", DataUsageMonitor.formatMbOrGb(0L))
    assertEquals("12.50 MB", DataUsageMonitor.formatMbOrGb((12.5 * 1024.0 * 1024.0).toLong()))
    assertEquals("1.50 GB", DataUsageMonitor.formatMbOrGb((1.5 * 1024.0 * 1024.0 * 1024.0).toLong()))
  }

  @Test
  fun `tracks active session totals, peak speed, average rate, and preserves summary after disconnect`() {
    val monitor = DataUsageMonitor()
    val startMs = 1_000_000L

    monitor.onConnectionStateChanged(
      state = VpnConnectionState.Connected(
        server = sampleServer,
        connectedSinceMs = startMs,
        rxBytes = 30L * 1024L * 1024L,
        txBytes = 10L * 1024L * 1024L,
        rxRateBps = 3_000_000L,
        txRateBps = 1_000_000L,
        packetCount = 1200L,
      ),
      nowMs = startMs + 10_000L,
    )

    val active = monitor.snapshot.value
    assertTrue(active.isActiveSession)
    assertTrue(active.hasSessionData)
    assertEquals(40L * 1024L * 1024L, active.totalBytes)
    assertEquals(75, active.downloadSharePercent)
    assertEquals(25, active.uploadSharePercent)
    assertEquals(4_000_000L, active.peakRateBps)
    assertEquals((40L * 1024L * 1024L) / 10L, active.averageRateBps)
    assertTrue(active.estimatedBytesPerHour > 0L)
    assertEquals(listOf(4_000_000L), active.rateHistoryBps)

    monitor.onConnectionStateChanged(VpnConnectionState.Disconnected, nowMs = startMs + 12_000L)
    val ended = monitor.snapshot.value
    assertFalse(ended.isActiveSession)
    assertTrue(ended.hasSessionData)
    assertEquals(40L * 1024L * 1024L, ended.totalBytes)
    assertEquals(0L, ended.rxRateBps)
    assertEquals(0L, ended.txRateBps)
  }

  @Test
  fun `connecting resets previous session counters`() {
    val monitor = DataUsageMonitor()
    val startMs = 2_000_000L

    monitor.onConnectionStateChanged(
      state = VpnConnectionState.Connected(
        server = sampleServer,
        connectedSinceMs = startMs,
        rxBytes = 5_000_000L,
        txBytes = 1_000_000L,
        rxRateBps = 500_000L,
        txRateBps = 100_000L,
        packetCount = 50L,
      ),
      nowMs = startMs + 5_000L,
    )
    monitor.onConnectionStateChanged(VpnConnectionState.Disconnected, nowMs = startMs + 6_000L)
    monitor.onConnectionStateChanged(VpnConnectionState.Connecting(sampleServer, "TLS"), nowMs = startMs + 7_000L)

    val reset = monitor.snapshot.value
    assertFalse(reset.isActiveSession)
    assertFalse(reset.hasSessionData)
    assertEquals(0L, reset.totalBytes)
    assertTrue(reset.rateHistoryBps.isEmpty())
  }

  @Test
  fun `recordTrafficCounters calculates live delta rates and accumulates lifetime bytes across sessions`() {
    val monitor = DataUsageMonitor()
    val s1Start = 10_000L

    monitor.beginSession(connectedSinceMs = s1Start, nowMs = s1Start)
    val tick1 = monitor.recordTrafficCounters(
      rxBytes = 4_000L,
      txBytes = 1_000L,
      connectedSinceMs = s1Start,
      nowMs = s1Start + 1_000L,
    )
    assertEquals(4_000L, tick1.receivedBytes)
    assertEquals(1_000L, tick1.sentBytes)
    assertEquals(5_000L, tick1.totalBytes)
    assertEquals(4_000L, tick1.rxRateBps)
    assertEquals(1_000L, tick1.txRateBps)
    assertEquals(5_000L, tick1.lifetimeTotalBytes)

    // Disconnect session 1
    monitor.onConnectionStateChanged(VpnConnectionState.Disconnected, nowMs = s1Start + 2_000L)

    // Start session 2 and send/receive more bytes
    val s2Start = 20_000L
    monitor.beginSession(connectedSinceMs = s2Start, nowMs = s2Start)
    val tick2 = monitor.recordTrafficCounters(
      rxBytes = 2_000L,
      txBytes = 500L,
      connectedSinceMs = s2Start,
      nowMs = s2Start + 1_000L,
    )
    assertEquals(2_500L, tick2.totalBytes)
    assertEquals(6_000L, tick2.lifetimeReceivedBytes)
    assertEquals(1_500L, tick2.lifetimeSentBytes)
    assertEquals(7_500L, tick2.lifetimeTotalBytes)

    com.example.vpn.VpnStateHolder.updateDataUsage(tick2)
    assertEquals(7_500L, com.example.vpn.VpnStateHolder.dataUsageState.value.lifetimeTotalBytes)
  }

  @Test
  fun `formatLiveCounter formats bytes, kilobytes, megabytes, and gigabytes`() {
    assertEquals("512 B", DataUsageMonitor.formatLiveCounter(512L))
    assertEquals("2.0 KB", DataUsageMonitor.formatLiveCounter(2048L))
    assertEquals("3.50 MB", DataUsageMonitor.formatLiveCounter((3.5 * 1024.0 * 1024.0).toLong()))
    assertEquals("1.25 GB", DataUsageMonitor.formatLiveCounter((1.25 * 1024.0 * 1024.0 * 1024.0).toLong()))
  }
}
