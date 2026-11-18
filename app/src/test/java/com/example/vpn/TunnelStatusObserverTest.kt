package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.data.DefaultServers
import com.example.model.VpnConnectionState
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TunnelStatusObserverTest {

  @After
  fun tearDown() {
    VpnStateHolder.broadcastDisconnected("test")
  }

  @Test
  fun `VpnStateHolder broadcasts connecting, connected, error, and disconnected atomically`() {
    val server = DefaultServers.curatedServers.first()

    VpnStateHolder.broadcastConnecting(
      server = server,
      stage = "Anti-DPI Setup",
      serviceTag = "primary",
      phase = TunnelBridgePhase.PROBING_UPSTREAM,
    )
    assertTrue(VpnStateHolder.connectionState.value is VpnConnectionState.Connecting)
    assertEquals(TunnelBridgePhase.PROBING_UPSTREAM, VpnStateHolder.observerState.value.phase)
    assertFalse(VpnStateHolder.observerState.value.isTunnelVerifiedActive)

    val verifiedSnapshot = TunnelObserverSnapshot(
      serviceTag = "primary",
      phase = TunnelBridgePhase.ACTIVE_VERIFIED,
      isSystemVpnInterfaceUp = true,
      isTunFdValid = true,
      isNativeWorkerAlive = true,
      isSocksProxyAlive = true,
      hasUnderlyingInternet = true,
      lastVerifiedAtMs = 123456L,
    )
    VpnStateHolder.broadcastConnected(
      connected = VpnConnectionState.Connected(
        server = server,
        connectedSinceMs = 1000L,
        rxBytes = 1024L,
        txBytes = 512L,
        rxRateBps = 256L,
        txRateBps = 128L,
        packetCount = 12L,
      ),
      snapshot = verifiedSnapshot,
    )
    assertTrue(VpnStateHolder.connectionState.value is VpnConnectionState.Connected)
    assertTrue(VpnStateHolder.observerState.value.isTunnelVerifiedActive)
    assertEquals(TunnelBridgePhase.ACTIVE_VERIFIED, VpnStateHolder.observerState.value.phase)

    VpnStateHolder.broadcastError("HEVTunnel bridge stopped", serviceTag = "primary")
    val errState = VpnStateHolder.connectionState.value
    assertTrue(errState is VpnConnectionState.Error)
    assertEquals("HEVTunnel bridge stopped", (errState as VpnConnectionState.Error).message)
    assertEquals(TunnelBridgePhase.ERROR, VpnStateHolder.observerState.value.phase)
    assertFalse(VpnStateHolder.observerState.value.isTunnelVerifiedActive)

    VpnStateHolder.broadcastDisconnected("primary")
    assertEquals(VpnConnectionState.Disconnected, VpnStateHolder.connectionState.value)
    assertEquals(TunnelBridgePhase.DISCONNECTED, VpnStateHolder.observerState.value.phase)
    assertNull(VpnStateHolder.observerState.value.lastError)
  }

  @Test
  fun `computeBridgePhase covers disconnected, error, opening, degraded, and verified phases`() {
    assertEquals(
      TunnelBridgePhase.DISCONNECTED,
      TunnelStatusObserver.computeBridgePhase(
        sessionActive = false,
        isTunFdValid = true,
        isNativeWorkerAlive = true,
        isSocksProxyAlive = true,
        isSystemVpnUp = true,
        hasUnderlyingInternet = true,
      ),
    )
    assertEquals(
      TunnelBridgePhase.ERROR,
      TunnelStatusObserver.computeBridgePhase(
        sessionActive = true,
        isTunFdValid = true,
        isNativeWorkerAlive = false,
        isSocksProxyAlive = true,
        isSystemVpnUp = true,
        hasUnderlyingInternet = true,
      ),
    )
    assertEquals(
      TunnelBridgePhase.OPENING_TUN,
      TunnelStatusObserver.computeBridgePhase(
        sessionActive = true,
        isTunFdValid = true,
        isNativeWorkerAlive = true,
        isSocksProxyAlive = true,
        isSystemVpnUp = false,
        hasUnderlyingInternet = true,
      ),
    )
    assertEquals(
      TunnelBridgePhase.DEGRADED_NO_INTERNET,
      TunnelStatusObserver.computeBridgePhase(
        sessionActive = true,
        isTunFdValid = true,
        isNativeWorkerAlive = true,
        isSocksProxyAlive = true,
        isSystemVpnUp = true,
        hasUnderlyingInternet = false,
      ),
    )
    assertEquals(
      TunnelBridgePhase.ACTIVE_VERIFIED,
      TunnelStatusObserver.computeBridgePhase(
        sessionActive = true,
        isTunFdValid = true,
        isNativeWorkerAlive = true,
        isSocksProxyAlive = true,
        isSystemVpnUp = true,
        hasUnderlyingInternet = true,
      ),
    )
  }

  @Test
  fun `TunnelStatusObserver reacts in real time to TRANSPORT_VPN loss, internet loss, and worker drop`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)

    val unexpectedLossReason = AtomicReference<String?>(null)
    val observer = TunnelStatusObserver(
      context = context,
      serviceTag = "stealth",
      onUnexpectedTunnelLost = { reason -> unexpectedLossReason.set(reason) },
    )

    // Register a mock physical network and VPN network in ShadowConnectivityManager
    val physNet: Network = cm.activeNetwork ?: org.robolectric.shadows.ShadowNetwork.newInstance(100)
    val vpnNet: Network = org.robolectric.shadows.ShadowNetwork.newInstance(200)

    val physCaps = ShadowNetworkCapabilities.newInstance().apply {
      Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    shadowCm.setNetworkCapabilities(physNet, physCaps)

    observer.startMonitoring()
    observer.markTunnelStarted(
      tunFdValid = true,
      nativeWorkerAlive = true,
      socksProxyAlive = true,
    )

    // Simulate OS registering TRANSPORT_VPN + physical Wi-Fi
    observer.physicalNetworkCallback.onAvailable(physNet)
    observer.vpnNetworkCallback.onAvailable(vpnNet)

    assertEquals(TunnelBridgePhase.ACTIVE_VERIFIED, VpnStateHolder.observerState.value.phase)
    assertTrue(VpnStateHolder.observerState.value.isTunnelVerifiedActive)

    // Simulate physical Wi-Fi drop while VPN TUN stays open -> DEGRADED_NO_INTERNET
    shadowCm.clearAllNetworks()
    observer.physicalNetworkCallback.onLost(physNet)
    assertEquals(TunnelBridgePhase.DEGRADED_NO_INTERNET, VpnStateHolder.observerState.value.phase)
    assertFalse(VpnStateHolder.observerState.value.hasUnderlyingInternet)

    // Physical Wi-Fi returns -> ACTIVE_VERIFIED
    shadowCm.setNetworkCapabilities(physNet, physCaps)
    observer.physicalNetworkCallback.onAvailable(physNet)
    assertEquals(TunnelBridgePhase.ACTIVE_VERIFIED, VpnStateHolder.observerState.value.phase)

    // Native worker dies -> liveness probe flags ERROR
    val deadWorkerSnapshot = observer.evaluateLiveness(
      tunFdValid = true,
      nativeWorkerAlive = false,
      socksProxyAlive = true,
    )
    assertEquals(TunnelBridgePhase.ERROR, deadWorkerSnapshot.phase)
    assertFalse(deadWorkerSnapshot.isTunnelVerifiedActive)

    // Restore worker then simulate system VPN interface torn down unexpectedly -> callback fires
    observer.evaluateLiveness(
      tunFdValid = true,
      nativeWorkerAlive = true,
      socksProxyAlive = true,
    )
    observer.vpnNetworkCallback.onLost(vpnNet)
    assertEquals("System VPN interface closed or revoked", unexpectedLossReason.get())

    observer.stopMonitoring()
  }
}
