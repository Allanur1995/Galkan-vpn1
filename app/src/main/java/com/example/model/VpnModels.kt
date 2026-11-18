package com.example.model

import kotlinx.serialization.Serializable

@Serializable
enum class VpnProtocol(
  val shortName: String,
  val fullName: String,
  val defaultPort: Int,
  val dpiBypassScore: Int,
  val supportsFragmentation: Boolean,
) {
  VLESS_REALITY("VLESS + Reality", "XTLS-Reality TLS 1.3 Camouflage", 443, 99, true),
  TROJAN_TLS("Trojan-GFW", "HTTPS / TLS 1.3 Mimicry", 443, 95, true),
  VMESS_WS_CDN("VMess + WS CDN", "WebSocket TLS over CDN Fronting", 443, 93, true),
  SHADOWSOCKS_OBFS("Shadowsocks AEAD", "ChaCha20-Poly1305 + SimpleObfs", 8388, 88, false),
  AMNEZIA_WG("AmneziaWG", "Obfuscated WireGuard Junk-Padding", 51820, 91, false),
}

@Serializable
enum class DnsProvider(
  val label: String,
  val primaryIp: String,
  val dohEndpoint: String,
) {
  CLOUDFLARE("Cloudflare DoH", "1.1.1.1", "https://cloudflare-dns.com/dns-query"),
  GOOGLE("Google DoH", "8.8.8.8", "https://dns.google/dns-query"),
  QUAD9("Quad9 Secure", "9.9.9.9", "https://dns.quad9.net/dns-query"),
  ADGUARD("AdGuard DNS", "94.140.14.14", "https://dns.adguard-dns.com/dns-query"),
}

@Serializable
enum class ServerNodeStatus {
  CONNECTED,
  ONLINE,
  HIGH_LOAD,
  CHECKING,
  OFFLINE,
  STANDBY,
}

@Serializable
data class VpnServer(
  val id: String,
  val name: String,
  val countryCode: String,
  val countryName: String = "",
  val flagEmoji: String,
  val city: String,
  val host: String,
  val port: Int,
  val protocol: VpnProtocol,
  val sni: String = "www.microsoft.com",
  val pingMs: Int? = null,
  val loadPercent: Int = 34,
  val isProOnly: Boolean = false,
  val isCustom: Boolean = false,
  val rawUri: String? = null,
  val supportsStealth: Boolean = true,
  val nodeStatus: ServerNodeStatus = ServerNodeStatus.STANDBY,
) {
  companion object {
    fun resolveStatus(
      pingMs: Int?,
      loadPercent: Int,
      wasProbed: Boolean,
      isPinging: Boolean,
      isConnected: Boolean,
    ): ServerNodeStatus = when {
      isConnected -> ServerNodeStatus.CONNECTED
      isPinging && !wasProbed -> ServerNodeStatus.CHECKING
      pingMs != null && loadPercent >= 80 -> ServerNodeStatus.HIGH_LOAD
      pingMs != null -> ServerNodeStatus.ONLINE
      wasProbed -> ServerNodeStatus.OFFLINE
      else -> ServerNodeStatus.STANDBY
    }
  }
}

@Serializable
data class DpiBypassConfig(
  val activeProtocol: VpnProtocol = VpnProtocol.VLESS_REALITY,
  val tlsFragmentEnabled: Boolean = true,
  val fragmentSizeBytes: Int = 24,
  val fragmentIntervalMs: Int = 15,
  val customSni: String = "www.microsoft.com",
  val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
  val mtu: Int = 1400,
  val autoReconnect: Boolean = true,
  val bypassLanAndLocalBanks: Boolean = true,
)

sealed interface VpnConnectionState {
  data object Disconnected : VpnConnectionState

  data class Connecting(
    val server: VpnServer,
    val stage: String,
  ) : VpnConnectionState

  data class Connected(
    val server: VpnServer,
    val connectedSinceMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBps: Long,
    val txRateBps: Long,
    val packetCount: Long,
    val tunnelAddress: String = "10.8.0.2",
  ) : VpnConnectionState

  data class Error(val message: String) : VpnConnectionState
}

@Serializable
enum class AppLanguage(val code: String, val nativeName: String) {
  TURKMEN("tk", "Türkmençe"),
  ENGLISH("en", "English"),
  RUSSIAN("ru", "Русский"),
}

data class ProPlan(
  val productId: String,
  val isSubscription: Boolean,
  val fallbackPrice: String,
  val badge: String?,
  val isBestValue: Boolean = false,
)

data class InstalledAppInfo(
  val packageName: String,
  val label: String,
)

data class NetworkDiagnosticResult(
  val publicIp: String?,
  val tcpReachable: Boolean,
  val latencyMs: Int?,
  val dnsResolved: Boolean,
  val timestampMs: Long,
  val note: String,
)
