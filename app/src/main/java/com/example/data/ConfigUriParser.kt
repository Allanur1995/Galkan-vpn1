package com.example.data

import android.net.Uri
import android.util.Base64
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ConfigUriParser {

  private val json = Json { ignoreUnknownKeys = true }

  sealed interface ParseResult {
    data class Success(val server: VpnServer) : ParseResult
    data class Error(val reason: String) : ParseResult
  }

  fun parse(rawInput: String): ParseResult {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      return ParseResult.Error("URI is empty")
    }
    return when {
      trimmed.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(trimmed, VpnProtocol.VLESS_REALITY)
      trimmed.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(trimmed, VpnProtocol.TROJAN_TLS)
      trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
      trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
      else -> ParseResult.Error("Unsupported scheme. Use vless://, vmess://, trojan://, or ss://")
    }
  }

  private fun parseVlessOrTrojan(raw: String, defaultProtocol: VpnProtocol): ParseResult {
    return try {
      val uri = Uri.parse(raw)
      val userInfo = uri.userInfo
      val host = uri.host
      val port = if (uri.port > 0) uri.port else 443
      if (userInfo.isNullOrBlank() || host.isNullOrBlank()) {
        return ParseResult.Error("Missing credentials or host in URI")
      }
      val type = uri.getQueryParameter("type")?.lowercase()
      val security = uri.getQueryParameter("security")?.lowercase()
      val sni = uri.getQueryParameter("sni")
        ?: uri.getQueryParameter("host")
        ?: "www.microsoft.com"
      val remark = uri.fragment?.let { decodeUrl(it) }?.takeIf { it.isNotBlank() }
        ?: "$host:$port"

      val resolvedProtocol = when {
        defaultProtocol == VpnProtocol.TROJAN_TLS -> VpnProtocol.TROJAN_TLS
        type == "ws" -> VpnProtocol.VMESS_WS_CDN
        security == "reality" -> VpnProtocol.VLESS_REALITY
        else -> VpnProtocol.VLESS_REALITY
      }

      ParseResult.Success(
        VpnServer(
          id = "custom-${System.currentTimeMillis()}",
          name = remark,
          countryCode = "CUSTOM",
          flagEmoji = "🛰️",
          city = host,
          host = host,
          port = port,
          protocol = resolvedProtocol,
          sni = sni,
          loadPercent = 15,
          isProOnly = false,
          isCustom = true,
          rawUri = raw,
          supportsStealth = true,
        )
      )
    } catch (e: Exception) {
      ParseResult.Error("Invalid URI format: ${e.message ?: "parse error"}")
    }
  }

  private fun parseVmess(raw: String): ParseResult {
    return try {
      val payload = raw.substringAfter("vmess://").trim()
      val decoded = String(decodeBase64Flexible(payload), Charsets.UTF_8)
      val obj = json.parseToJsonElement(decoded).jsonObject
      val host = obj["add"]?.jsonPrimitive?.content.orEmpty()
      val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 443
      val ps = obj["ps"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "$host:$port"
      val sni = obj["sni"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: obj["host"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: "www.cloudflare.com"
      if (host.isBlank()) {
        return ParseResult.Error("VMess JSON missing 'add' host")
      }
      ParseResult.Success(
        VpnServer(
          id = "custom-${System.currentTimeMillis()}",
          name = ps,
          countryCode = "CUSTOM",
          flagEmoji = "🛰️",
          city = host,
          host = host,
          port = port,
          protocol = VpnProtocol.VMESS_WS_CDN,
          sni = sni,
          loadPercent = 18,
          isProOnly = false,
          isCustom = true,
          rawUri = raw,
          supportsStealth = true,
        )
      )
    } catch (e: Exception) {
      ParseResult.Error("Invalid VMess Base64 JSON: ${e.message ?: "parse error"}")
    }
  }

  private fun parseShadowsocks(raw: String): ParseResult {
    return try {
      val withoutScheme = raw.substringAfter("ss://")
      val remarkPart = withoutScheme.substringAfter("#", "")
      val mainPart = withoutScheme.substringBefore("#")
      val remark = decodeUrl(remarkPart).takeIf { it.isNotBlank() }

      val (host, port) = if (mainPart.contains("@")) {
        val hostPort = mainPart.substringAfter("@").substringBefore("?").substringBefore("/")
        val parts = hostPort.split(":")
        if (parts.size < 2) return ParseResult.Error("Missing port in Shadowsocks URI")
        parts[0] to (parts[1].toIntOrNull() ?: 8388)
      } else {
        val decoded = String(decodeBase64Flexible(mainPart.substringBefore("?")), Charsets.UTF_8)
        val hostPort = decoded.substringAfter("@")
        val parts = hostPort.split(":")
        if (parts.size < 2) return ParseResult.Error("Invalid Shadowsocks Base64 payload")
        parts[0] to (parts[1].toIntOrNull() ?: 8388)
      }

      if (host.isBlank()) return ParseResult.Error("Missing host in Shadowsocks URI")

      ParseResult.Success(
        VpnServer(
          id = "custom-${System.currentTimeMillis()}",
          name = remark ?: "$host:$port",
          countryCode = "CUSTOM",
          flagEmoji = "🛰️",
          city = host,
          host = host,
          port = port,
          protocol = VpnProtocol.SHADOWSOCKS_OBFS,
          sni = "www.bing.com",
          loadPercent = 20,
          isProOnly = false,
          isCustom = true,
          rawUri = raw,
          supportsStealth = true,
        )
      )
    } catch (e: Exception) {
      ParseResult.Error("Invalid Shadowsocks URI: ${e.message ?: "parse error"}")
    }
  }

  private fun decodeBase64Flexible(input: String): ByteArray {
    val normalized = input.replace('-', '+').replace('_', '/')
    val padded = when (normalized.length % 4) {
      2 -> "$normalized=="
      3 -> "$normalized="
      else -> normalized
    }
    return Base64.decode(padded, Base64.DEFAULT)
  }

  private fun decodeUrl(value: String): String {
    return try {
      URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
      value
    }
  }
}
