package com.example.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.model.DpiBypassConfig
import com.example.model.VpnConnectionState
import com.example.model.VpnServer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.hevtunnel.TProxyService

/**
 * Shared native hev-socks5-tunnel (lwIP TUN-to-SOCKS5) engine for both
 * GalkanVpnService and StealthTunnelVpnService, bridging all IPv4/IPv6 TCP and UDP
 * traffic from tun0 into LocalSocks5ProxyServer with real-time liveness & data usage tracking.
 */
internal class HevVpnEngine(
  private val service: VpnService,
  private val serviceTag: String,
  private val scope: CoroutineScope,
  private val onConnectedNotification: (VpnServer) -> Unit,
  private val onTrafficUpdated: (VpnServer, DataUsageSnapshot) -> Unit = { _, _ -> },
  private val onStopService: () -> Unit,
) {

  private val isStopping = AtomicBoolean(false)
  private var sessionJob: Job? = null
  private var statsJob: Job? = null
  private var proxyServer: LocalSocks5ProxyServer? = null
  private var tunInterface: ParcelFileDescriptor? = null
  private var hevThread: Thread? = null

  val dataUsageMonitor = DataUsageMonitor()

  private val statusObserver = TunnelStatusObserver(
    context = service,
    serviceTag = serviceTag,
    onUnexpectedTunnelLost = { reason -> handleUnexpectedDrop(reason) },
  )

  fun currentDataUsage(): DataUsageSnapshot = dataUsageMonitor.snapshot.value

  fun start(
    server: VpnServer,
    dpiConfig: DpiBypassConfig,
    excludedPackages: Set<String>,
  ) {
    stopResourcesOnly()
    isStopping.set(false)
    statusObserver.startMonitoring()

    sessionJob = scope.launch(Dispatchers.IO) {
      try {
        val stageLabel = if (server.rawUri.isNullOrBlank()) {
          "Anti-DPI + DoH Setup"
        } else {
          "${server.protocol.shortName} Handshake"
        }
        VpnStateHolder.updateDataUsage(
          dataUsageMonitor.onConnectionStateChanged(
            VpnConnectionState.Connecting(server, stageLabel),
          ),
        )
        VpnStateHolder.broadcastConnecting(
          server = server,
          stage = stageLabel,
          serviceTag = serviceTag,
          phase = TunnelBridgePhase.PROBING_UPSTREAM,
        )
        VpnStateHolder.appendLog(
          "Starting HEVTunnel (${if (server.rawUri.isNullOrBlank()) "Anti-DPI Direct + DoH" else server.protocol.shortName}) DNS=${dpiConfig.dnsProvider.label}",
        )

        val protectTcp: (java.net.Socket) -> Boolean = { sock -> service.protect(sock) }
        val protectUdp: (java.net.DatagramSocket) -> Boolean = { ds -> service.protect(ds) }

        val dnsResolver = DohDnsResolver(
          protectSocket = protectTcp,
          protectDatagram = protectUdp,
          fragmentDohTls = dpiConfig.tlsFragmentEnabled,
          onLog = { msg -> VpnStateHolder.appendLog(msg) },
        )

        val dialer = UpstreamProtocolDialer(
          dnsResolver = dnsResolver,
          protectSocket = protectTcp,
          onLog = { msg -> VpnStateHolder.appendLog(msg) },
        )

        when (val check = dialer.verifyUpstreamReachable(server, dpiConfig)) {
          is UpstreamCheckResult.Unreachable -> {
            isStopping.set(true)
            stopResourcesOnly()
            VpnStateHolder.updateDataUsage(
              dataUsageMonitor.onConnectionStateChanged(
                VpnConnectionState.Error(check.errorMessage),
              ),
            )
            VpnStateHolder.broadcastError(check.errorMessage, serviceTag)
            VpnStateHolder.appendLog("ERROR: ${check.errorMessage}")
            onStopService()
            return@launch
          }
          is UpstreamCheckResult.Reachable -> {
            VpnStateHolder.appendLog("Upstream OK (${check.latencyMs}ms): ${check.modeDescription}")
          }
        }

        VpnStateHolder.broadcastConnecting(
          server = server,
          stage = "Opening TUN & SOCKS5 Bridge",
          serviceTag = serviceTag,
          phase = TunnelBridgePhase.OPENING_TUN,
        )

        val proxy = LocalSocks5ProxyServer(
          server = server,
          dpiConfig = dpiConfig,
          dnsResolver = dnsResolver,
          dialer = dialer,
          protectDatagram = protectUdp,
          onLog = { msg -> VpnStateHolder.appendLog(msg) },
        )
        proxy.start()
        proxyServer = proxy

        val mtu = dpiConfig.mtu.coerceIn(1280, 1500)
        val builder = service.Builder()
          .setSession("Galkan VPN — ${server.name}")
          .addAddress(TUN_IPV4_ADDRESS, 24)
          .addRoute("0.0.0.0", 0)
          .addAddress(TUN_IPV6_ADDRESS, 128)
          .addRoute("::", 0)
          .addDnsServer(dpiConfig.dnsProvider.primaryIp)
          .setMtu(mtu)
          .allowBypass()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          builder.setMetered(false)
        }

        // Apply user split-tunnel exclusions; Galkan's own outbound sockets use VpnService.protect()
        excludedPackages.forEach { pkg ->
          if (pkg != service.packageName) {
            runCatching { builder.addDisallowedApplication(pkg) }
          }
        }

        val established = builder.establish()
        if (established == null) {
          isStopping.set(true)
          stopResourcesOnly()
          val errMsg = "VPN permission revoked or TUN interface unavailable"
          VpnStateHolder.updateDataUsage(
            dataUsageMonitor.onConnectionStateChanged(VpnConnectionState.Error(errMsg)),
          )
          VpnStateHolder.broadcastError(errMsg, serviceTag)
          VpnStateHolder.appendLog("ERROR: Builder.establish() returned null")
          onStopService()
          return@launch
        }
        tunInterface = established

        val configFile = File(service.filesDir, "hev_${serviceTag}.yml")
        configFile.writeText(buildHevConfigYaml(mtu = mtu, socksPort = proxy.port))

        val worker = Thread(
          {
            val startResult = runCatching {
              TProxyService.TProxyStartService(configFile.absolutePath, established.fd)
            }
            if (!isStopping.get()) {
              val dropReason = startResult.exceptionOrNull()?.message
                ?: "HEVTunnel bridge terminated unexpectedly"
              handleUnexpectedDrop(dropReason)
            }
          },
          "HevSocks5Tunnel-$serviceTag",
        ).apply {
          isDaemon = true
          start()
        }
        hevThread = worker

        val connectedAt = System.currentTimeMillis()
        onConnectedNotification(server)
        VpnStateHolder.appendLog(
          "HEVTunnel bridge active (tun0 $TUN_IPV4_ADDRESS + IPv6 -> 127.0.0.1:${proxy.port}, TCP+UDP, DoH: ${dpiConfig.dnsProvider.primaryIp})",
        )

        val initialUsage = dataUsageMonitor.beginSession(connectedSinceMs = connectedAt, nowMs = connectedAt)
        VpnStateHolder.updateDataUsage(initialUsage)

        val initialSnapshot = statusObserver.markTunnelStarted(
          tunFdValid = runCatching { established.fileDescriptor?.valid() == true }.getOrDefault(false),
          nativeWorkerAlive = worker.isAlive,
          socksProxyAlive = proxy.isRunning(),
        )

        VpnStateHolder.broadcastConnected(
          connected = VpnConnectionState.Connected(
            server = server,
            connectedSinceMs = connectedAt,
            rxBytes = 0L,
            txBytes = 0L,
            rxRateBps = 0L,
            txRateBps = 0L,
            packetCount = 0L,
          ),
          snapshot = initialSnapshot,
        )

        launchStatsLoop(server, proxy, connectedAt)
      } catch (e: Exception) {
        if (!isStopping.get()) {
          handleUnexpectedDrop(e.message ?: "Tunnel initialization error")
        } else {
          stopResourcesOnly()
        }
      }
    }
  }

  private fun launchStatsLoop(
    server: VpnServer,
    proxy: LocalSocks5ProxyServer,
    connectedAt: Long,
  ) {
    statsJob?.cancel()
    statsJob = scope.launch(Dispatchers.IO) {
      var tickCount = 0

      while (isActive && !isStopping.get() && tunInterface != null) {
        delay(1000L)
        if (isStopping.get()) break

        val fdValid = runCatching { tunInterface?.fileDescriptor?.valid() == true }.getOrDefault(false)
        val workerAlive = hevThread?.isAlive == true
        val proxyAlive = proxy.isRunning()

        val snapshot = statusObserver.evaluateLiveness(
          tunFdValid = fdValid,
          nativeWorkerAlive = workerAlive,
          socksProxyAlive = proxyAlive,
        )

        if (!fdValid) {
          handleUnexpectedDrop("TUN file descriptor closed")
          break
        }
        if (!workerAlive) {
          handleUnexpectedDrop("HEVTunnel native worker stopped")
          break
        }
        if (!proxyAlive) {
          handleUnexpectedDrop("Local SOCKS5 proxy stopped")
          break
        }

        val nativeStats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
        val nativeTxPackets = nativeStats?.getOrNull(0) ?: 0L
        val nativeTxBytes = nativeStats?.getOrNull(1) ?: 0L
        val nativeRxPackets = nativeStats?.getOrNull(2) ?: 0L
        val nativeRxBytes = nativeStats?.getOrNull(3) ?: 0L

        val totalRx = maxOf(nativeRxBytes, proxy.rxBytes.get())
        val totalTx = maxOf(nativeTxBytes, proxy.txBytes.get())
        val totalPackets = maxOf(nativeTxPackets + nativeRxPackets, proxy.packetCount.get())

        val usageSnapshot = dataUsageMonitor.recordTrafficCounters(
          rxBytes = totalRx,
          txBytes = totalTx,
          connectedSinceMs = connectedAt,
        )
        VpnStateHolder.updateDataUsage(usageSnapshot)

        VpnStateHolder.broadcastConnected(
          connected = VpnConnectionState.Connected(
            server = server,
            connectedSinceMs = connectedAt,
            rxBytes = usageSnapshot.receivedBytes,
            txBytes = usageSnapshot.sentBytes,
            rxRateBps = usageSnapshot.rxRateBps,
            txRateBps = usageSnapshot.txRateBps,
            packetCount = totalPackets,
          ),
          snapshot = snapshot,
        )

        tickCount++
        if (tickCount % 3 == 0) {
          onTrafficUpdated(server, usageSnapshot)
        }
      }
    }
  }

  private fun handleUnexpectedDrop(reason: String) {
    if (!isStopping.compareAndSet(false, true)) return
    VpnStateHolder.appendLog("Tunnel observer detected failure: $reason")
    stopResourcesOnly()
    VpnStateHolder.updateDataUsage(
      dataUsageMonitor.onConnectionStateChanged(VpnConnectionState.Error(reason)),
    )
    VpnStateHolder.broadcastError(reason, serviceTag)
    onStopService()
  }

  fun stop() {
    isStopping.set(true)
    stopResourcesOnly()
    VpnStateHolder.updateDataUsage(
      dataUsageMonitor.onConnectionStateChanged(VpnConnectionState.Disconnected),
    )
    VpnStateHolder.broadcastDisconnected(serviceTag)
    VpnStateHolder.appendLog("Tunnel disconnected cleanly")
  }

  fun stopWithError(reason: String) {
    isStopping.set(true)
    stopResourcesOnly()
    VpnStateHolder.updateDataUsage(
      dataUsageMonitor.onConnectionStateChanged(VpnConnectionState.Error(reason)),
    )
    VpnStateHolder.broadcastError(reason, serviceTag)
    VpnStateHolder.appendLog("ERROR: $reason")
  }

  fun stopIfStillActive() {
    if (isStopping.compareAndSet(false, true)) {
      stopResourcesOnly()
      VpnStateHolder.updateDataUsage(
        dataUsageMonitor.onConnectionStateChanged(VpnConnectionState.Disconnected),
      )
      VpnStateHolder.broadcastDisconnected(serviceTag)
    } else {
      stopResourcesOnly()
    }
  }

  private fun stopResourcesOnly() {
    statusObserver.stopMonitoring()
    statsJob?.cancel()
    statsJob = null
    sessionJob?.cancel()
    sessionJob = null

    val worker = hevThread
    hevThread = null
    if (worker != null) {
      runCatching { TProxyService.TProxyStopService() }
      if (Thread.currentThread() !== worker) {
        runCatching { worker.join(1200L) }
      }
    }
    runCatching { proxyServer?.close() }
    proxyServer = null
    runCatching { tunInterface?.close() }
    tunInterface = null
  }

  companion object {
    const val TUN_IPV4_ADDRESS = "10.8.0.2"
    const val TUN_IPV6_ADDRESS = "fc00::1"

    fun buildHevConfigYaml(mtu: Int, socksPort: Int): String = """
      tunnel:
        mtu: $mtu
        ipv4: $TUN_IPV4_ADDRESS
        ipv6: '$TUN_IPV6_ADDRESS'
      socks5:
        port: $socksPort
        address: 127.0.0.1
        udp: 'udp'
      misc:
        task-stack-size: 81920
        tcp-buffer-size: 65536
        connect-timeout: 8000
        read-write-timeout: 120000
        log-level: warn
    """.trimIndent()
  }
}
