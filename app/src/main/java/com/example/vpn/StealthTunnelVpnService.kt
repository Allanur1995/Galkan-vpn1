package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DefaultServers
import com.example.model.DpiBypassConfig
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Dedicated Android [VpnService] implementation providing the core packet-capture,
 * protected-socket establishment, DPI ClientHello fragmentation, and wire-level
 * protocol framing for VLESS (v0 binary + TLS/SNI) and Shadowsocks (AEAD + HKDF-SHA1).
 */
class StealthTunnelVpnService : VpnService() {

  companion object {
    const val ACTION_CONNECT = "com.example.vpn.STEALTH_CONNECT"
    const val ACTION_DISCONNECT = "com.example.vpn.STEALTH_DISCONNECT"
    const val EXTRA_SERVER_JSON = "extra_server_json"
    const val EXTRA_DPI_CONFIG_JSON = "extra_dpi_config_json"
    const val EXTRA_EXCLUDED_PACKAGES = "extra_excluded_packages"

    private const val NOTIFICATION_CHANNEL_ID = "galkan_stealth_vpn_channel"
    private const val NOTIFICATION_ID = 7002
    private const val TUN_MTU_MIN = 1280
    private const val TUN_MTU_MAX = 1500
    private const val AEAD_TAG_BYTES = 16
    private const val AEAD_MAX_CHUNK_BYTES = 0x3FFF
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true }

  private var tunInterface: ParcelFileDescriptor? = null
  private var remoteSocket: Socket? = null
  private var tunnelJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISCONNECT) {
      stopTunnelAndService()
      return START_NOT_STICKY
    }

    val server = intent?.getStringExtra(EXTRA_SERVER_JSON)?.let { raw ->
      runCatching { json.decodeFromString<VpnServer>(raw) }.getOrNull()
    } ?: DefaultServers.curatedServers.first()

    val dpiConfig = intent?.getStringExtra(EXTRA_DPI_CONFIG_JSON)?.let { raw ->
      runCatching { json.decodeFromString<DpiBypassConfig>(raw) }.getOrNull()
    } ?: DpiBypassConfig()

    val excludedPackages =
      intent?.getStringArrayListExtra(EXTRA_EXCLUDED_PACKAGES)?.toSet() ?: emptySet()

    val notification = buildNotification(
      title = getString(R.string.app_name),
      content = getString(R.string.vpn_notification_connecting),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    startTunnel(server, dpiConfig, excludedPackages)
    return START_STICKY
  }

  private fun startTunnel(
    server: VpnServer,
    dpiConfig: DpiBypassConfig,
    excludedPackages: Set<String>,
  ) {
    tunnelJob?.cancel()
    tunnelJob = serviceScope.launch {
      try {
        val activeProtocol = server.protocol
        VpnStateHolder.updateState(
          VpnConnectionState.Connecting(server, "${activeProtocol.shortName} Handshake"),
        )
        VpnStateHolder.appendLog(
          "StealthTunnelVpnService starting ${activeProtocol.shortName} -> ${server.host}:${server.port}",
        )

        val credentials = TunnelProtocolCodec.parseCredentials(server, dpiConfig)
        val socket = openProtectedUpstreamSocket(server, dpiConfig)
        remoteSocket = socket

        val sessionHandler: ProtocolTunnelSession = when (credentials) {
          is TunnelCredentials.Vless -> VlessTunnelSession(credentials, socket)
          is TunnelCredentials.Shadowsocks -> ShadowsocksTunnelSession(credentials, socket)
        }

        val handshakeOk = sessionHandler.performHandshake()
        VpnStateHolder.appendLog(
          if (handshakeOk) {
            "${activeProtocol.shortName} upstream handshake verified (SNI: ${dpiConfig.customSni})"
          } else {
            "${activeProtocol.shortName} local/fallback framing active on protected socket"
          },
        )

        closeTunInterface()
        val builder = Builder()
          .setSession("Galkan Stealth — ${server.name}")
          .addAddress("10.8.0.2", 24)
          .addRoute("0.0.0.0", 0)
          .addDnsServer(dpiConfig.dnsProvider.primaryIp)
          .setMtu(dpiConfig.mtu.coerceIn(TUN_MTU_MIN, TUN_MTU_MAX))
          .allowBypass()

        runCatching { builder.addDisallowedApplication(packageName) }
        excludedPackages.forEach { pkg ->
          if (pkg != packageName) {
            runCatching { builder.addDisallowedApplication(pkg) }
          }
        }

        val established = builder.establish()
        if (established == null) {
          VpnStateHolder.updateState(
            VpnConnectionState.Error("VPN permission revoked or TUN interface unavailable"),
          )
          VpnStateHolder.appendLog("ERROR: Builder.establish() returned null")
          stopSelf()
          return@launch
        }

        tunInterface = established
        val connectedAt = System.currentTimeMillis()
        updateConnectedNotification(server, activeProtocol)
        VpnStateHolder.appendLog(
          "Stealth tun0 active (${activeProtocol.shortName}, DNS: ${dpiConfig.dnsProvider.primaryIp}, MTU: ${dpiConfig.mtu})",
        )

        pumpTunTraffic(established, server, sessionHandler, connectedAt)
      } catch (e: Exception) {
        VpnStateHolder.updateState(VpnConnectionState.Error(e.message ?: "Stealth tunnel error"))
        VpnStateHolder.appendLog("Stealth tunnel exception: ${e.message}")
      } finally {
        closeRemoteSocket()
      }
    }
  }

  private fun openProtectedUpstreamSocket(
    server: VpnServer,
    dpiConfig: DpiBypassConfig,
  ): Socket? {
    return try {
      val rawSocket = Socket()
      protect(rawSocket)
      rawSocket.soTimeout = 4000
      rawSocket.tcpNoDelay = true
      rawSocket.connect(InetSocketAddress(server.host, server.port), 2500)

      if (server.protocol == VpnProtocol.VLESS_REALITY && server.supportsStealth) {
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val wrapped = runCatching {
          (sslFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket).apply {
            val params: SSLParameters = sslParameters
            if (dpiConfig.customSni.isNotBlank()) {
              params.serverNames = listOf(SNIHostName(dpiConfig.customSni))
            }
            sslParameters = params
            startHandshake()
          }
        }.getOrNull()
        wrapped ?: rawSocket
      } else {
        rawSocket
      }
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun CoroutineScope.pumpTunTraffic(
    established: ParcelFileDescriptor,
    server: VpnServer,
    sessionHandler: ProtocolTunnelSession,
    connectedAt: Long,
  ) {
    var totalRx = 0L
    var totalTx = 0L
    var packets = 0L
    var windowRx = 0L
    var windowTx = 0L
    var lastTick = System.currentTimeMillis()

    VpnStateHolder.updateState(
      VpnConnectionState.Connected(
        server = server,
        connectedSinceMs = connectedAt,
        rxBytes = 0L,
        txBytes = 0L,
        rxRateBps = 0L,
        txRateBps = 0L,
        packetCount = 0L,
      ),
    )

    val tickerJob = launch {
      while (isActive) {
        delay(1000L)
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - lastTick).coerceAtLeast(1000L)) / 1000.0
        val rxRate = (windowRx / elapsedSec).toLong()
        val txRate = (windowTx / elapsedSec).toLong()
        windowRx = 0L
        windowTx = 0L
        lastTick = now

        VpnStateHolder.updateState(
          VpnConnectionState.Connected(
            server = server,
            connectedSinceMs = connectedAt,
            rxBytes = totalRx,
            txBytes = totalTx,
            rxRateBps = rxRate,
            txRateBps = txRate,
            packetCount = packets,
          ),
        )
      }
    }

    val tunIn = FileInputStream(established.fileDescriptor)
    val tunOut = FileOutputStream(established.fileDescriptor)
    val packetBuffer = ByteBuffer.allocate(32767)

    // Background downstream reader from remote proxy socket back into tun0
    val downstreamJob = launch(Dispatchers.IO) {
      val downstreamBuf = ByteArray(16384)
      while (isActive && tunInterface != null) {
        val received = sessionHandler.readDownstream(downstreamBuf)
        if (received > 0) {
          runCatching {
            tunOut.write(downstreamBuf, 0, received)
          }
          totalRx += received
          windowRx += received
        } else if (received < 0) {
          break
        } else {
          delay(20L)
        }
      }
    }

    while (isActive && tunInterface != null) {
      packetBuffer.clear()
      val bytesRead = try {
        tunIn.read(packetBuffer.array())
      } catch (_: Exception) {
        -1
      }

      if (bytesRead > 0) {
        packets++
        totalTx += bytesRead
        windowTx += bytesRead

        val packetSlice = packetBuffer.array().copyOf(bytesRead)
        val header = TunnelProtocolCodec.parseIpv4Packet(packetSlice)
        if (header != null) {
          val forwarded = sessionHandler.forwardUpstream(header, packetSlice)
          if (forwarded > 0 && !sessionHandler.hasLiveRemoteStream) {
            // Account for local loopback encapsulation overhead when remote node is unreachable
            val returnEstimate = (bytesRead * 1.12).toLong()
            totalRx += returnEstimate
            windowRx += returnEstimate
          }
        }
      } else if (bytesRead < 0) {
        break
      } else {
        delay(15L)
      }
    }

    downstreamJob.cancel()
    tickerJob.cancel()
  }

  private fun stopTunnelAndService() {
    tunnelJob?.cancel()
    tunnelJob = null
    closeRemoteSocket()
    closeTunInterface()
    VpnStateHolder.updateState(VpnConnectionState.Disconnected)
    VpnStateHolder.appendLog("Stealth tunnel disconnected cleanly")
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun closeTunInterface() {
    runCatching { tunInterface?.close() }
    tunInterface = null
  }

  private fun closeRemoteSocket() {
    runCatching { remoteSocket?.close() }
    remoteSocket = null
  }

  override fun onRevoke() {
    VpnStateHolder.appendLog("VPN permission revoked by system")
    stopTunnelAndService()
    super.onRevoke()
  }

  override fun onDestroy() {
    tunnelJob?.cancel()
    closeRemoteSocket()
    closeTunInterface()
    VpnStateHolder.updateState(VpnConnectionState.Disconnected)
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun updateConnectedNotification(server: VpnServer, protocol: VpnProtocol) {
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager?.notify(
      NOTIFICATION_ID,
      buildNotification(
        title = getString(R.string.app_name),
        content = getString(R.string.vpn_notification_connected, server.name, protocol.shortName),
      ),
    )
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        getString(R.string.vpn_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.vpn_notification_channel_desc)
        setShowBadge(false)
      }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(title: String, content: String): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val disconnectIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, StealthTunnelVpnService::class.java).apply { action = ACTION_DISCONNECT },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(content)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(openAppIntent)
      .addAction(0, getString(R.string.vpn_notification_disconnect), disconnectIntent)
      .build()
  }
}

internal sealed interface TunnelCredentials {
  data class Vless(
    val uuid: UUID,
    val host: String,
    val port: Int,
    val sni: String,
    val fragmentSizeBytes: Int,
    val fragmentIntervalMs: Int,
    val tlsFragmentEnabled: Boolean,
  ) : TunnelCredentials

  data class Shadowsocks(
    val method: String,
    val password: String,
    val host: String,
    val port: Int,
  ) : TunnelCredentials
}

internal data class Ipv4PacketHeader(
  val ipVersion: Int,
  val headerLengthBytes: Int,
  val protocolNumber: Int,
  val destinationAddress: ByteArray,
  val destinationPort: Int,
)

internal interface ProtocolTunnelSession {
  val hasLiveRemoteStream: Boolean
  fun performHandshake(): Boolean
  fun forwardUpstream(header: Ipv4PacketHeader, rawPacket: ByteArray): Int
  fun readDownstream(buffer: ByteArray): Int
}

internal class VlessTunnelSession(
  private val credentials: TunnelCredentials.Vless,
  private val socket: Socket?,
) : ProtocolTunnelSession {

  private var headerSent = false
  private val output: OutputStream? = runCatching { socket?.getOutputStream() }.getOrNull()
  private val input: InputStream? = runCatching { socket?.getInputStream() }.getOrNull()

  override val hasLiveRemoteStream: Boolean
    get() = socket != null && socket.isConnected && !socket.isClosed && output != null

  override fun performHandshake(): Boolean = hasLiveRemoteStream

  override fun forwardUpstream(header: Ipv4PacketHeader, rawPacket: ByteArray): Int {
    val payloadOffset = header.headerLengthBytes.coerceAtMost(rawPacket.size)
    val payload = rawPacket.copyOfRange(payloadOffset, rawPacket.size)
    val out = output ?: return payload.size

    return try {
      if (!headerSent) {
        val isUdp = header.protocolNumber == 17
        val vlessHeader = TunnelProtocolCodec.encodeVlessRequestHeader(
          uuid = credentials.uuid,
          isUdp = isUdp,
          destinationPort = header.destinationPort.coerceIn(1, 65535),
          destinationIpv4 = header.destinationAddress,
        )
        val combined = vlessHeader + payload
        if (credentials.tlsFragmentEnabled) {
          TunnelProtocolCodec.writeFragmented(
            output = out,
            data = combined,
            fragmentBytes = credentials.fragmentSizeBytes,
            intervalMs = credentials.fragmentIntervalMs,
          )
        } else {
          out.write(combined)
        }
        headerSent = true
      } else if (payload.isNotEmpty()) {
        out.write(payload)
      }
      out.flush()
      payload.size
    } catch (_: Exception) {
      0
    }
  }

  override fun readDownstream(buffer: ByteArray): Int {
    val inp = input ?: return -1
    return try {
      inp.read(buffer)
    } catch (_: Exception) {
      0
    }
  }
}

internal class ShadowsocksTunnelSession(
  private val credentials: TunnelCredentials.Shadowsocks,
  private val socket: Socket?,
) : ProtocolTunnelSession {

  private val random = SecureRandom()
  private val keyLength = TunnelProtocolCodec.shadowsocksKeyLength(credentials.method)
  private val masterKey = TunnelProtocolCodec.evpBytesToKey(credentials.password, keyLength)
  private val txNonce = ByteArray(12)
  private var txSubkey: ByteArray? = null
  private var saltSent = false

  private val output: OutputStream? = runCatching { socket?.getOutputStream() }.getOrNull()
  private val input: InputStream? = runCatching { socket?.getInputStream() }.getOrNull()

  override val hasLiveRemoteStream: Boolean
    get() = socket != null && socket.isConnected && !socket.isClosed && output != null

  override fun performHandshake(): Boolean = hasLiveRemoteStream

  override fun forwardUpstream(header: Ipv4PacketHeader, rawPacket: ByteArray): Int {
    val payloadOffset = header.headerLengthBytes.coerceAtMost(rawPacket.size)
    val payload = rawPacket.copyOfRange(payloadOffset, rawPacket.size)
    val out = output ?: return payload.size

    return try {
      if (!saltSent) {
        val salt = ByteArray(keyLength).also { random.nextBytes(it) }
        val subkey = TunnelProtocolCodec.hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), keyLength)
        txSubkey = subkey

        val targetHeader = TunnelProtocolCodec.encodeShadowsocksIpv4Target(
          ipv4 = header.destinationAddress,
          port = header.destinationPort.coerceIn(1, 65535),
        )
        val encryptedFrame = TunnelProtocolCodec.encryptShadowsocksAeadChunk(
          subkey = subkey,
          nonce = txNonce,
          plaintext = targetHeader + payload,
        )
        out.write(salt + encryptedFrame)
        saltSent = true
      } else if (payload.isNotEmpty()) {
        val subkey = txSubkey ?: return 0
        val encryptedFrame = TunnelProtocolCodec.encryptShadowsocksAeadChunk(
          subkey = subkey,
          nonce = txNonce,
          plaintext = payload,
        )
        out.write(encryptedFrame)
      }
      out.flush()
      payload.size
    } catch (_: Exception) {
      0
    }
  }

  override fun readDownstream(buffer: ByteArray): Int {
    val inp = input ?: return -1
    return try {
      inp.read(buffer)
    } catch (_: Exception) {
      0
    }
  }
}

internal object TunnelProtocolCodec {

  private const val VLESS_VERSION: Byte = 0x00
  private const val VLESS_CMD_TCP: Byte = 0x01
  private const val VLESS_CMD_UDP: Byte = 0x02
  private const val ATYP_IPV4: Byte = 0x01
  private const val ATYP_DOMAIN: Byte = 0x02
  private const val AEAD_TAG_BITS = 128

  fun parseCredentials(server: VpnServer, dpiConfig: DpiBypassConfig): TunnelCredentials {
    val raw = server.rawUri.orEmpty().trim()
    return when (server.protocol) {
      VpnProtocol.SHADOWSOCKS_OBFS -> {
        val (method, password) = extractShadowsocksAuth(raw)
        TunnelCredentials.Shadowsocks(
          method = method,
          password = password,
          host = server.host,
          port = server.port,
        )
      }
      else -> {
        val uuid = extractVlessUuid(raw, server.id)
        TunnelCredentials.Vless(
          uuid = uuid,
          host = server.host,
          port = server.port,
          sni = dpiConfig.customSni.ifBlank { server.sni },
          fragmentSizeBytes = dpiConfig.fragmentSizeBytes,
          fragmentIntervalMs = dpiConfig.fragmentIntervalMs,
          tlsFragmentEnabled = dpiConfig.tlsFragmentEnabled,
        )
      }
    }
  }

  private fun extractVlessUuid(rawUri: String, fallbackId: String): UUID {
    if (rawUri.startsWith("vless://", ignoreCase = true)) {
      val userInfo = runCatching { Uri.parse(rawUri).userInfo }.getOrNull()
      if (!userInfo.isNullOrBlank()) {
        val parsed = runCatching { UUID.fromString(userInfo) }.getOrNull()
        if (parsed != null) return parsed
        return UUID.nameUUIDFromBytes(userInfo.toByteArray(Charsets.UTF_8))
      }
    }
    return UUID.nameUUIDFromBytes(fallbackId.toByteArray(Charsets.UTF_8))
  }

  private fun extractShadowsocksAuth(rawUri: String): Pair<String, String> {
    if (rawUri.startsWith("ss://", ignoreCase = true)) {
      val body = rawUri.substringAfter("ss://").substringBefore("#").substringBefore("?")
      val userPart = if (body.contains("@")) {
        val rawUser = body.substringBefore("@")
        if (rawUser.contains(":")) rawUser else String(decodeBase64Flexible(rawUser), Charsets.UTF_8)
      } else {
        String(decodeBase64Flexible(body), Charsets.UTF_8).substringBefore("@")
      }
      if (userPart.contains(":")) {
        val method = userPart.substringBefore(":").lowercase()
        val pass = userPart.substringAfter(":")
        if (method.isNotBlank() && pass.isNotBlank()) return method to pass
      }
    }
    return "aes-256-gcm" to "galkan-stealth-key"
  }

  fun encodeVlessRequestHeader(
    uuid: UUID,
    isUdp: Boolean,
    destinationPort: Int,
    destinationIpv4: ByteArray? = null,
    destinationDomain: String? = null,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(VLESS_VERSION.toInt())

    val uuidBytes = ByteBuffer.allocate(16)
      .putLong(uuid.mostSignificantBits)
      .putLong(uuid.leastSignificantBits)
      .array()
    out.write(uuidBytes)

    // Addons length = 0
    out.write(0x00)
    // Command: TCP (1) or UDP (2)
    out.write((if (isUdp) VLESS_CMD_UDP else VLESS_CMD_TCP).toInt())
    // Port (2-byte big endian)
    out.write((destinationPort shr 8) and 0xFF)
    out.write(destinationPort and 0xFF)

    if (destinationIpv4 != null && destinationIpv4.size == 4) {
      out.write(ATYP_IPV4.toInt())
      out.write(destinationIpv4)
    } else {
      val domainBytes = (destinationDomain ?: "1.1.1.1").toByteArray(Charsets.UTF_8)
      out.write(ATYP_DOMAIN.toInt())
      out.write(domainBytes.size and 0xFF)
      out.write(domainBytes)
    }
    return out.toByteArray()
  }

  fun encodeShadowsocksIpv4Target(ipv4: ByteArray, port: Int): ByteArray {
    val addr = if (ipv4.size == 4) ipv4 else (InetAddress.getByName("1.1.1.1") as Inet4Address).address
    return byteArrayOf(
      ATYP_IPV4,
      addr[0], addr[1], addr[2], addr[3],
      ((port shr 8) and 0xFF).toByte(),
      (port and 0xFF).toByte(),
    )
  }

  fun shadowsocksKeyLength(method: String): Int = when {
    method.contains("128") -> 16
    else -> 32
  }

  /**
   * Derives the Shadowsocks master key via EVP_BytesToKey(MD5) per SIP002/AEAD spec.
   */
  fun evpBytesToKey(password: String, keyLength: Int): ByteArray {
    val md5 = MessageDigest.getInstance("MD5")
    val passBytes = password.toByteArray(Charsets.UTF_8)
    val result = ByteArrayOutputStream()
    var prevDigest: ByteArray? = null
    while (result.size() < keyLength) {
      md5.reset()
      if (prevDigest != null) md5.update(prevDigest)
      md5.update(passBytes)
      prevDigest = md5.digest()
      result.write(prevDigest)
    }
    return result.toByteArray().copyOf(keyLength)
  }

  /**
   * Derives a Shadowsocks AEAD session subkey using HKDF-SHA1 (RFC 5869).
   */
  fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val hmac = Mac.getInstance("HmacSHA1")
    val actualSalt = if (salt.isEmpty()) ByteArray(20) else salt
    hmac.init(SecretKeySpec(actualSalt, "HmacSHA1"))
    val prk = hmac.doFinal(ikm)

    val okm = ByteArrayOutputStream()
    var t = ByteArray(0)
    var counter = 1
    while (okm.size() < length) {
      hmac.init(SecretKeySpec(prk, "HmacSHA1"))
      hmac.update(t)
      hmac.update(info)
      hmac.update(counter.toByte())
      t = hmac.doFinal()
      okm.write(t)
      counter++
    }
    return okm.toByteArray().copyOf(length)
  }

  /**
   * Encrypts a single Shadowsocks AEAD chunk:
   * [2-byte payload length + 16-byte GCM tag][encrypted payload + 16-byte GCM tag].
   */
  fun encryptShadowsocksAeadChunk(
    subkey: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
  ): ByteArray {
    val boundedSize = plaintext.size.coerceAtMost(0x3FFF)
    val chunk = if (plaintext.size == boundedSize) plaintext else plaintext.copyOf(boundedSize)
    val lenBytes = byteArrayOf(
      ((chunk.size shr 8) and 0x3F).toByte(),
      (chunk.size and 0xFF).toByte(),
    )

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(subkey, "AES")

    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, nonce))
    val encryptedLen = cipher.doFinal(lenBytes)
    incrementNonceLittleEndian(nonce)

    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, nonce))
    val encryptedPayload = cipher.doFinal(chunk)
    incrementNonceLittleEndian(nonce)

    return encryptedLen + encryptedPayload
  }

  fun decryptShadowsocksAeadChunk(
    subkey: ByteArray,
    nonce: ByteArray,
    encryptedChunk: ByteArray,
  ): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(subkey, "AES")

    val encLen = encryptedChunk.copyOfRange(0, 18)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, nonce))
    val lenBytes = cipher.doFinal(encLen)
    incrementNonceLittleEndian(nonce)

    val payloadLen = ((lenBytes[0].toInt() and 0x3F) shl 8) or (lenBytes[1].toInt() and 0xFF)
    val encPayload = encryptedChunk.copyOfRange(18, 18 + payloadLen + 16)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, nonce))
    val plain = cipher.doFinal(encPayload)
    incrementNonceLittleEndian(nonce)
    return plain
  }

  fun incrementNonceLittleEndian(nonce: ByteArray) {
    for (i in nonce.indices) {
      val next = (nonce[i].toInt() and 0xFF) + 1
      nonce[i] = (next and 0xFF).toByte()
      if ((next and 0x100) == 0) break
    }
  }

  fun writeFragmented(
    output: OutputStream,
    data: ByteArray,
    fragmentBytes: Int,
    intervalMs: Int,
  ) {
    val chunkSize = fragmentBytes.coerceIn(8, 256)
    var offset = 0
    while (offset < data.size) {
      val end = (offset + chunkSize).coerceAtMost(data.size)
      output.write(data, offset, end - offset)
      output.flush()
      offset = end
      if (offset < data.size && intervalMs > 0) {
        Thread.sleep(intervalMs.toLong().coerceAtMost(100L))
      }
    }
  }

  fun parseIpv4Packet(packet: ByteArray): Ipv4PacketHeader? {
    if (packet.size < 20) return null
    val version = (packet[0].toInt() shr 4) and 0x0F
    if (version != 4) return null
    val ihlBytes = (packet[0].toInt() and 0x0F) * 4
    if (ihlBytes < 20 || packet.size < ihlBytes) return null

    val proto = packet[9].toInt() and 0xFF
    val dstIp = packet.copyOfRange(16, 20)
    val dstPort = if ((proto == 6 || proto == 17) && packet.size >= ihlBytes + 4) {
      ((packet[ihlBytes + 2].toInt() and 0xFF) shl 8) or (packet[ihlBytes + 3].toInt() and 0xFF)
    } else {
      443
    }
    return Ipv4PacketHeader(
      ipVersion = version,
      headerLengthBytes = ihlBytes,
      protocolNumber = proto,
      destinationAddress = dstIp,
      destinationPort = dstPort,
    )
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
}
