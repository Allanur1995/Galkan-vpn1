package com.example.vpn

import com.example.model.DnsProvider
import com.example.model.NetworkDiagnosticResult
import com.example.model.VpnServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object NetworkDiagnostics {

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

  suspend fun measureTcpPing(host: String, port: Int, timeoutMs: Int = 2500): Int? =
    withContext(Dispatchers.IO) {
      try {
        Socket().use { socket ->
          val start = System.currentTimeMillis()
          socket.connect(InetSocketAddress(host, port), timeoutMs)
          (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        }
      } catch (_: Exception) {
        null
      }
    }

  suspend fun runDiagnostics(
    server: VpnServer,
    dnsProvider: DnsProvider,
  ): NetworkDiagnosticResult = withContext(Dispatchers.IO) {
    val tcpPing = measureTcpPing(server.host, server.port)
    val dnsOk = runCatching {
      InetAddress.getByName("cloudflare.com").hostAddress != null
    }.getOrDefault(false)

    val publicIp = runCatching {
      val request = Request.Builder().url("https://api.ipify.org").build()
      httpClient.newCall(request).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string()?.trim() else null
      }
    }.getOrNull()

    val note = when {
      tcpPing != null && dnsOk ->
        "${server.host}:${server.port} reachable (${tcpPing}ms) • ${dnsProvider.label}"
      dnsOk ->
        "DNS OK, direct TCP port ${server.port} filtered — TLS Fragmentation recommended"
      else ->
        "Restricted network detected — try VLESS + Reality or CDN WebSocket port 443"
    }

    NetworkDiagnosticResult(
      publicIp = publicIp,
      tcpReachable = tcpPing != null,
      latencyMs = tcpPing,
      dnsResolved = dnsOk,
      timestampMs = System.currentTimeMillis(),
      note = note,
    )
  }
}
