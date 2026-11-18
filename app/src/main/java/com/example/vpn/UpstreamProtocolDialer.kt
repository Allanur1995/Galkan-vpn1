package com.example.vpn

import android.net.Uri
import android.os.Build
import android.util.Base64
import com.example.model.DpiBypassConfig
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
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
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal sealed interface TunnelCredentials {
  data object DirectAntiDpi : TunnelCredentials

  data class Vless(
    val uuid: UUID,
    val host: String,
    val port: Int,
    val sni: String,
    val security: String,
    val transportType: String,
    val wsPath: String,
    val wsHost: String,
    val fragmentSizeBytes: Int,
    val fragmentIntervalMs: Int,
    val tlsFragmentEnabled: Boolean,
  ) : TunnelCredentials

  data class Trojan(
    val password: String,
    val host: String,
    val port: Int,
    val sni: String,
    val transportType: String,
    val wsPath: String,
    val wsHost: String,
    val fragmentSizeBytes: Int,
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

internal sealed interface UpstreamCheckResult {
  data class Reachable(val latencyMs: Int, val modeDescription: String) : UpstreamCheckResult
  data class Unreachable(val errorMessage: String) : UpstreamCheckResult
}

internal interface DialedStream : Closeable {
  fun writeClientPayload(data: ByteArray, offset: Int, length: Int)
  fun readServerPayload(buffer: ByteArray): Int
}

internal class UpstreamProtocolDialer(
  private val dnsResolver: DohDnsResolver,
  private val protectSocket: (Socket) -> Boolean,
  private val onLog: (String) -> Unit = {},
) {

  fun verifyUpstreamReachable(
    server: VpnServer,
    dpiConfig: DpiBypassConfig,
  ): UpstreamCheckResult {
    if (server.rawUri.isNullOrBlank()) {
      // Built-in presets operate in direct Anti-DPI + DoH mode (no third-party proxy backend)
      val probes = listOf(
        dpiConfig.dnsProvider.primaryIp to 443,
        "1.1.1.1" to 443,
        "8.8.8.8" to 443,
      ).distinct()
      for ((host, port) in probes) {
        val rtt = probeProtectedTcp(host, port, 2500)
        if (rtt != null) {
          return UpstreamCheckResult.Reachable(
            latencyMs = rtt,
            modeDescription = "Anti-DPI ClientHello + DoH (${dpiConfig.dnsProvider.label})",
          )
        }
      }
      return UpstreamCheckResult.Reachable(
        latencyMs = 0,
        modeDescription = "Anti-DPI Direct + DoH (${dpiConfig.dnsProvider.label})",
      )
    }

    val addr = dnsResolver.resolveHost(server.host, dpiConfig.dnsProvider)
      ?: return UpstreamCheckResult.Unreachable("Cannot resolve server host: ${server.host}")

    val rtt = probeProtectedTcp(addr.hostAddress ?: server.host, server.port, 3500)
      ?: return UpstreamCheckResult.Unreachable(
        "Server ${server.host}:${server.port} is unreachable. Check key or internet connection.",
      )

    if (server.rawUri.contains("security=reality", ignoreCase = true)) {
      onLog("Note: VLESS+Reality URIs require X25519 uTLS; attempting TLS/SNI compatibility mode")
    }

    return UpstreamCheckResult.Reachable(
      latencyMs = rtt,
      modeDescription = "${server.protocol.shortName} (${server.host}:${server.port})",
    )
  }

  fun openStream(
    targetHost: String,
    targetPort: Int,
    server: VpnServer,
    dpiConfig: DpiBypassConfig,
    initialData: ByteArray?,
    initialLength: Int,
  ): DialedStream {
    return when (val creds = TunnelProtocolCodec.parseCredentials(server, dpiConfig)) {
      is TunnelCredentials.DirectAntiDpi -> openDirectAntiDpiStream(
        targetHost = targetHost,
        targetPort = targetPort,
        dpiConfig = dpiConfig,
        initialData = initialData,
        initialLength = initialLength,
      )
      is TunnelCredentials.Vless -> openVlessStream(
        creds = creds,
        targetHost = targetHost,
        targetPort = targetPort,
        dpiConfig = dpiConfig,
        initialData = initialData,
        initialLength = initialLength,
      )
      is TunnelCredentials.Trojan -> openTrojanStream(
        creds = creds,
        targetHost = targetHost,
        targetPort = targetPort,
        dpiConfig = dpiConfig,
        initialData = initialData,
        initialLength = initialLength,
      )
      is TunnelCredentials.Shadowsocks -> openShadowsocksStream(
        creds = creds,
        targetHost = targetHost,
        targetPort = targetPort,
        dpiConfig = dpiConfig,
        initialData = initialData,
        initialLength = initialLength,
      )
    }
  }

  private fun openDirectAntiDpiStream(
    targetHost: String,
    targetPort: Int,
    dpiConfig: DpiBypassConfig,
    initialData: ByteArray?,
    initialLength: Int,
  ): DialedStream {
    val resolved = dnsResolver.resolveHost(targetHost, dpiConfig.dnsProvider)
      ?: throw IOException("DNS lookup failed for $targetHost")

    val socket = Socket()
    if (!protectSocket(socket)) {
      onLog("Warning: protectSocket failed for direct TCP stream to $targetHost:$targetPort")
    }
    socket.tcpNoDelay = true
    socket.connect(InetSocketAddress(resolved, targetPort), 6000)
    socket.soTimeout = 0

    val out = socket.getOutputStream()
    val inp = socket.getInputStream()

    if (initialData != null && initialLength > 0) {
      if (dpiConfig.tlsFragmentEnabled && TunnelProtocolCodec.isTlsClientHello(initialData, 0, initialLength)) {
        TunnelProtocolCodec.writeClientHelloFragmented(
          output = out,
          data = initialData,
          offset = 0,
          length = initialLength,
          fragmentBytes = dpiConfig.fragmentSizeBytes,
          intervalMs = dpiConfig.fragmentIntervalMs,
        )
      } else {
        out.write(initialData, 0, initialLength)
        out.flush()
      }
    }

    return RawSocketStream(socket, inp, out)
  }

  private fun openVlessStream(
    creds: TunnelCredentials.Vless,
    targetHost: String,
    targetPort: Int,
    dpiConfig: DpiBypassConfig,
    initialData: ByteArray?,
    initialLength: Int,
  ): DialedStream {
    val useTls = creds.security == "tls" || creds.security == "reality"
    val (socket, inp, out) = connectUpstreamTransport(
      host = creds.host,
      port = creds.port,
      useTls = useTls,
      sni = creds.sni.ifBlank { creds.host },
      useWebSocket = creds.transportType == "ws",
      wsHost = creds.wsHost.ifBlank { creds.sni.ifBlank { creds.host } },
      wsPath = creds.wsPath,
      dpiConfig = dpiConfig,
    )

    val ipv4 = DohDnsResolver.parseNumericIpv4OrNull(targetHost)
    val vlessHeader = TunnelProtocolCodec.encodeVlessRequestHeader(
      uuid = creds.uuid,
      isUdp = false,
      destinationPort = targetPort,
      destinationIpv4 = ipv4,
      destinationDomain = if (ipv4 == null) targetHost else null,
    )

    if (initialData != null && initialLength > 0) {
      val combined = ByteArray(vlessHeader.size + initialLength)
      System.arraycopy(vlessHeader, 0, combined, 0, vlessHeader.size)
      System.arraycopy(initialData, 0, combined, vlessHeader.size, initialLength)
      out.write(combined)
    } else {
      out.write(vlessHeader)
    }
    out.flush()

    return VlessDialedStream(socket, inp, out)
  }

  private fun openTrojanStream(
    creds: TunnelCredentials.Trojan,
    targetHost: String,
    targetPort: Int,
    dpiConfig: DpiBypassConfig,
    initialData: ByteArray?,
    initialLength: Int,
  ): DialedStream {
    val (socket, inp, out) = connectUpstreamTransport(
      host = creds.host,
      port = creds.port,
      useTls = true,
      sni = creds.sni.ifBlank { creds.host },
      useWebSocket = creds.transportType == "ws",
      wsHost = creds.wsHost.ifBlank { creds.sni.ifBlank { creds.host } },
      wsPath = creds.wsPath,
      dpiConfig = dpiConfig,
    )

    val trojanHeader = TunnelProtocolCodec.encodeTrojanRequestHeader(
      password = creds.password,
      targetHost = targetHost,
      targetPort = targetPort,
    )
    if (initialData != null && initialLength > 0) {
      val combined = ByteArray(trojanHeader.size + initialLength)
      System.arraycopy(trojanHeader, 0, combined, 0, trojanHeader.size)
      System.arraycopy(initialData, 0, combined, trojanHeader.size, initialLength)
      out.write(combined)
    } else {
      out.write(trojanHeader)
    }
    out.flush()

    return RawSocketStream(socket, inp, out)
  }

  private fun openShadowsocksStream(
    creds: TunnelCredentials.Shadowsocks,
    targetHost: String,
    targetPort: Int,
    dpiConfig: DpiBypassConfig,
    initialData: ByteArray?,
    initialLength: Int,
  ): DialedStream {
    val resolved = dnsResolver.resolveHost(creds.host, dpiConfig.dnsProvider)
      ?: throw IOException("Cannot resolve Shadowsocks host ${creds.host}")

    val socket = Socket()
    protectSocket(socket)
    socket.tcpNoDelay = true
    socket.connect(InetSocketAddress(resolved, creds.port), 6000)
    socket.soTimeout = 0

    val inp = socket.getInputStream()
    val out = socket.getOutputStream()

    val stream = ShadowsocksDialedStream(
      socket = socket,
      inp = inp,
      out = out,
      method = creds.method,
      password = creds.password,
      targetHost = targetHost,
      targetPort = targetPort,
    )
    stream.sendOpeningPacket(initialData, initialLength)
    return stream
  }

  private fun connectUpstreamTransport(
    host: String,
    port: Int,
    useTls: Boolean,
    sni: String,
    useWebSocket: Boolean,
    wsHost: String,
    wsPath: String,
    dpiConfig: DpiBypassConfig,
  ): Triple<Socket, InputStream, OutputStream> {
    val resolved = dnsResolver.resolveHost(host, dpiConfig.dnsProvider)
      ?: throw IOException("Cannot resolve proxy host $host")

    val rawSocket = if (useTls && dpiConfig.tlsFragmentEnabled) {
      object : Socket() {
        override fun getOutputStream(): OutputStream {
          return TlsClientHelloFragmentingOutputStream(
            super.getOutputStream(),
            dpiConfig.fragmentSizeBytes,
          )
        }
      }
    } else {
      Socket()
    }
    protectSocket(rawSocket)
    rawSocket.tcpNoDelay = true
    rawSocket.connect(InetSocketAddress(resolved, port), 6000)

    val activeSocket: Socket = if (useTls) {
      val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
      val ssl = (sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
        soTimeout = 7000
        val params: SSLParameters = sslParameters
        if (sni.isNotBlank() && !sni.contains(':')) {
          runCatching { params.serverNames = listOf(SNIHostName(sni)) }
        }
        sslParameters = params
        startHandshake()
        soTimeout = 0
      }
      ssl
    } else {
      rawSocket.soTimeout = 0
      rawSocket
    }

    val rawIn = activeSocket.getInputStream()
    val rawOut = activeSocket.getOutputStream()

    if (!useWebSocket) {
      return Triple(activeSocket, rawIn, rawOut)
    }

    val cleanPath = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
    val wsKeyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val wsKey = Base64.encodeToString(wsKeyBytes, Base64.NO_WRAP)
    val upgradeReq = buildString {
      append("GET ").append(cleanPath).append(" HTTP/1.1\r\n")
      append("Host: ").append(wsHost).append("\r\n")
      append("Upgrade: websocket\r\n")
      append("Connection: Upgrade\r\n")
      append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
      append("Sec-WebSocket-Version: 13\r\n\r\n")
    }
    rawOut.write(upgradeReq.toByteArray(Charsets.US_ASCII))
    rawOut.flush()

    val headerLine = readHttpUpgradeResponseStatus(rawIn)
    if (!headerLine.contains("101")) {
      runCatching { activeSocket.close() }
      throw IOException("WebSocket upgrade failed: $headerLine")
    }

    return Triple(activeSocket, WebSocketBinaryInputStream(rawIn), WebSocketBinaryOutputStream(rawOut))
  }

  private fun readHttpUpgradeResponseStatus(inp: InputStream): String {
    val buf = ByteArrayOutputStream()
    var prev3 = 0; var prev2 = 0; var prev1 = 0
    while (buf.size() < 8192) {
      val b = inp.read()
      if (b == -1) break
      buf.write(b)
      if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
        break
      }
      prev3 = prev2; prev2 = prev1; prev1 = b
    }
    val text = buf.toString("US-ASCII")
    return text.lineSequence().firstOrNull().orEmpty()
  }

  private fun probeProtectedTcp(host: String, port: Int, timeoutMs: Int): Int? {
    return try {
      Socket().use { s ->
        protectSocket(s)
        val start = System.currentTimeMillis()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
      }
    } catch (_: Exception) {
      null
    }
  }
}

private class RawSocketStream(
  private val socket: Socket,
  private val inp: InputStream,
  private val out: OutputStream,
) : DialedStream {
  override fun writeClientPayload(data: ByteArray, offset: Int, length: Int) {
    out.write(data, offset, length)
    out.flush()
  }

  override fun readServerPayload(buffer: ByteArray): Int = inp.read(buffer)

  override fun close() {
    runCatching { socket.close() }
  }
}

private class VlessDialedStream(
  private val socket: Socket,
  private val inp: InputStream,
  private val out: OutputStream,
) : DialedStream {
  private var responseHeaderStripped = false

  override fun writeClientPayload(data: ByteArray, offset: Int, length: Int) {
    out.write(data, offset, length)
    out.flush()
  }

  override fun readServerPayload(buffer: ByteArray): Int {
    if (!responseHeaderStripped) {
      responseHeaderStripped = true
      val version = inp.read()
      if (version < 0) return -1
      val addonsLen = inp.read()
      if (addonsLen < 0) return -1
      if (addonsLen > 0) {
        val skipBuf = ByteArray(addonsLen)
        var read = 0
        while (read < addonsLen) {
          val n = inp.read(skipBuf, read, addonsLen - read)
          if (n < 0) return -1
          read += n
        }
      }
    }
    return inp.read(buffer)
  }

  override fun close() {
    runCatching { socket.close() }
  }
}

private class ShadowsocksDialedStream(
  private val socket: Socket,
  private val inp: InputStream,
  private val out: OutputStream,
  private val method: String,
  password: String,
  private val targetHost: String,
  private val targetPort: Int,
) : DialedStream {

  private val random = SecureRandom()
  private val keyLength = TunnelProtocolCodec.shadowsocksKeyLength(method)
  private val masterKey = TunnelProtocolCodec.evpBytesToKey(password, keyLength)
  private val txNonce = ByteArray(12)
  private val rxNonce = ByteArray(12)
  private var txSubkey: ByteArray? = null
  private var rxSubkey: ByteArray? = null
  private val txLock = Any()
  private var pendingPlainBuf = ByteArray(0)
  private var pendingOffset = 0

  fun sendOpeningPacket(initialData: ByteArray?, initialLength: Int) {
    synchronized(txLock) {
      val salt = ByteArray(keyLength).also { random.nextBytes(it) }
      val subkey = TunnelProtocolCodec.hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), keyLength)
      txSubkey = subkey

      val ipv4 = DohDnsResolver.parseNumericIpv4OrNull(targetHost)
      val targetHeader = if (ipv4 != null) {
        TunnelProtocolCodec.encodeShadowsocksIpv4Target(ipv4, targetPort)
      } else {
        TunnelProtocolCodec.encodeShadowsocksDomainTarget(targetHost, targetPort)
      }

      val firstPlain = if (initialData != null && initialLength > 0) {
        val take = initialLength.coerceAtMost(0x3FFF - targetHeader.size)
        val combined = ByteArray(targetHeader.size + take)
        System.arraycopy(targetHeader, 0, combined, 0, targetHeader.size)
        System.arraycopy(initialData, 0, combined, targetHeader.size, take)
        val encrypted = TunnelProtocolCodec.encryptShadowsocksAeadChunk(subkey, txNonce, combined, method)
        out.write(salt)
        out.write(encrypted)
        if (initialLength > take) {
          writeChunkedLocked(subkey, initialData, take, initialLength - take)
        }
        out.flush()
        return
      } else {
        targetHeader
      }

      val encrypted = TunnelProtocolCodec.encryptShadowsocksAeadChunk(subkey, txNonce, firstPlain, method)
      out.write(salt)
      out.write(encrypted)
      out.flush()
    }
  }

  override fun writeClientPayload(data: ByteArray, offset: Int, length: Int) {
    synchronized(txLock) {
      val subkey = txSubkey ?: return
      writeChunkedLocked(subkey, data, offset, length)
      out.flush()
    }
  }

  private fun writeChunkedLocked(subkey: ByteArray, data: ByteArray, offset: Int, length: Int) {
    var pos = offset
    val end = offset + length
    while (pos < end) {
      val chunkLen = (end - pos).coerceAtMost(0x3FFF)
      val slice = data.copyOfRange(pos, pos + chunkLen)
      val encrypted = TunnelProtocolCodec.encryptShadowsocksAeadChunk(subkey, txNonce, slice, method)
      out.write(encrypted)
      pos += chunkLen
    }
  }

  override fun readServerPayload(buffer: ByteArray): Int {
    if (pendingOffset < pendingPlainBuf.size) {
      val avail = pendingPlainBuf.size - pendingOffset
      val copyLen = avail.coerceAtMost(buffer.size)
      System.arraycopy(pendingPlainBuf, pendingOffset, buffer, 0, copyLen)
      pendingOffset += copyLen
      return copyLen
    }

    val dataIn = DataInputStream(inp)
    val subkey = rxSubkey ?: run {
      val salt = ByteArray(keyLength)
      try {
        dataIn.readFully(salt)
      } catch (_: EOFException) {
        return -1
      }
      TunnelProtocolCodec.hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), keyLength).also {
        rxSubkey = it
      }
    }

    val encLen = ByteArray(2 + 16)
    try {
      dataIn.readFully(encLen)
    } catch (_: EOFException) {
      return -1
    }
    val lenBytes = TunnelProtocolCodec.aeadCrypt(
      encrypt = false,
      method = method,
      subkey = subkey,
      nonce = rxNonce,
      input = encLen,
    )
    TunnelProtocolCodec.incrementNonceLittleEndian(rxNonce)

    val payloadLen = ((lenBytes[0].toInt() and 0x3F) shl 8) or (lenBytes[1].toInt() and 0xFF)
    val encPayload = ByteArray(payloadLen + 16)
    try {
      dataIn.readFully(encPayload)
    } catch (_: EOFException) {
      return -1
    }
    val plain = TunnelProtocolCodec.aeadCrypt(
      encrypt = false,
      method = method,
      subkey = subkey,
      nonce = rxNonce,
      input = encPayload,
    )
    TunnelProtocolCodec.incrementNonceLittleEndian(rxNonce)

    val copyLen = plain.size.coerceAtMost(buffer.size)
    System.arraycopy(plain, 0, buffer, 0, copyLen)
    if (plain.size > copyLen) {
      pendingPlainBuf = plain
      pendingOffset = copyLen
    } else {
      pendingPlainBuf = ByteArray(0)
      pendingOffset = 0
    }
    return copyLen
  }

  override fun close() {
    runCatching { socket.close() }
  }
}

internal class WebSocketBinaryOutputStream(
  private val delegate: OutputStream,
) : FilterOutputStream(delegate) {
  private val random = SecureRandom()

  override fun write(b: Int) {
    write(byteArrayOf(b.toByte()), 0, 1)
  }

  @Synchronized
  override fun write(b: ByteArray, off: Int, len: Int) {
    if (len <= 0) return
    val mask = ByteArray(4).also { random.nextBytes(it) }
    val header = ByteArrayOutputStream()
    header.write(0x82) // FIN + Binary frame
    when {
      len <= 125 -> header.write(0x80 or len)
      len <= 65535 -> {
        header.write(0x80 or 126)
        header.write((len shr 8) and 0xFF)
        header.write(len and 0xFF)
      }
      else -> {
        header.write(0x80 or 127)
        val l = len.toLong()
        for (shift in 56 downTo 0 step 8) {
          header.write(((l ushr shift) and 0xFF).toInt())
        }
      }
    }
    header.write(mask)
    val masked = ByteArray(len)
    for (i in 0 until len) {
      masked[i] = (b[off + i].toInt() xor mask[i and 3].toInt()).toByte()
    }
    delegate.write(header.toByteArray())
    delegate.write(masked)
  }

  @Synchronized
  override fun flush() {
    delegate.flush()
  }
}

internal class WebSocketBinaryInputStream(
  private val inp: InputStream,
) : InputStream() {
  private val dataIn = DataInputStream(inp)
  private var framePayload = ByteArray(0)
  private var frameOffset = 0

  override fun read(): Int {
    val one = ByteArray(1)
    val n = read(one, 0, 1)
    return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    while (frameOffset >= framePayload.size) {
      if (!readNextFrame()) return -1
    }
    val copy = (framePayload.size - frameOffset).coerceAtMost(len)
    System.arraycopy(framePayload, frameOffset, b, off, copy)
    frameOffset += copy
    return copy
  }

  private fun readNextFrame(): Boolean {
    while (true) {
      val b0 = inp.read()
      if (b0 < 0) return false
      val b1 = inp.read()
      if (b1 < 0) return false

      val opcode = b0 and 0x0F
      val masked = (b1 and 0x80) != 0
      var payloadLen = (b1 and 0x7F).toLong()
      if (payloadLen == 126L) {
        payloadLen = ((dataIn.readUnsignedByte() shl 8) or dataIn.readUnsignedByte()).toLong()
      } else if (payloadLen == 127L) {
        payloadLen = dataIn.readLong()
      }
      val maskKey = if (masked) ByteArray(4).also { dataIn.readFully(it) } else null
      val safeLen = payloadLen.toInt().coerceIn(0, 8 * 1024 * 1024)
      val data = ByteArray(safeLen)
      dataIn.readFully(data)
      if (maskKey != null) {
        for (i in data.indices) {
          data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        }
      }
      when (opcode) {
        0x08 -> return false // Close frame
        0x09, 0x0A -> continue // Ping / Pong: read next frame
        else -> {
          framePayload = data
          frameOffset = 0
          return true
        }
      }
    }
  }
}

internal object TunnelProtocolCodec {

  private const val VLESS_VERSION: Byte = 0x00
  private const val VLESS_CMD_TCP: Byte = 0x01
  private const val VLESS_CMD_UDP: Byte = 0x02
  private const val ATYP_IPV4: Byte = 0x01
  private const val ATYP_DOMAIN: Byte = 0x02
  private const val ATYP_SOCKS_DOMAIN: Byte = 0x03
  private const val AEAD_TAG_BITS = 128

  fun parseCredentials(server: VpnServer, dpiConfig: DpiBypassConfig): TunnelCredentials {
    val raw = server.rawUri.orEmpty().trim()
    if (raw.isEmpty()) {
      return TunnelCredentials.DirectAntiDpi
    }
    return when {
      raw.startsWith("ss://", ignoreCase = true) ||
        server.protocol == VpnProtocol.SHADOWSOCKS_OBFS -> {
        val (method, password) = extractShadowsocksAuth(raw)
        TunnelCredentials.Shadowsocks(
          method = method,
          password = password,
          host = server.host,
          port = server.port,
        )
      }
      raw.startsWith("trojan://", ignoreCase = true) ||
        server.protocol == VpnProtocol.TROJAN_TLS -> {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val password = uri?.userInfo.orEmpty().ifBlank { server.id }
        val type = runCatching { uri?.getQueryParameter("type")?.lowercase() }.getOrNull() ?: "tcp"
        val path = runCatching { uri?.getQueryParameter("path") }.getOrNull() ?: "/"
        val wsHost = runCatching { uri?.getQueryParameter("host") }.getOrNull() ?: server.sni
        TunnelCredentials.Trojan(
          password = password,
          host = server.host,
          port = server.port,
          sni = dpiConfig.customSni.ifBlank { server.sni },
          transportType = type,
          wsPath = path,
          wsHost = wsHost,
          fragmentSizeBytes = dpiConfig.fragmentSizeBytes,
          tlsFragmentEnabled = dpiConfig.tlsFragmentEnabled,
        )
      }
      else -> {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val uuid = extractVlessUuid(raw, server.id)
        val security = runCatching { uri?.getQueryParameter("security")?.lowercase() }.getOrNull()
          ?: if (server.port == 443) "tls" else "none"
        val type = runCatching { uri?.getQueryParameter("type")?.lowercase() }.getOrNull() ?: "tcp"
        val path = runCatching { uri?.getQueryParameter("path") }.getOrNull() ?: "/"
        val wsHost = runCatching { uri?.getQueryParameter("host") }.getOrNull() ?: server.sni
        TunnelCredentials.Vless(
          uuid = uuid,
          host = server.host,
          port = server.port,
          sni = dpiConfig.customSni.ifBlank { server.sni },
          security = security,
          transportType = type,
          wsPath = path,
          wsHost = wsHost,
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
    out.write(0x00) // Addons length = 0
    out.write((if (isUdp) VLESS_CMD_UDP else VLESS_CMD_TCP).toInt())
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

  fun encodeTrojanRequestHeader(
    password: String,
    targetHost: String,
    targetPort: Int,
  ): ByteArray {
    val digest = MessageDigest.getInstance("SHA-224").digest(password.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    val out = ByteArrayOutputStream()
    out.write(hex.toByteArray(Charsets.US_ASCII))
    out.write('\r'.code); out.write('\n'.code)
    out.write(0x01) // CMD = CONNECT (TCP)
    val ipv4 = DohDnsResolver.parseNumericIpv4OrNull(targetHost)
    if (ipv4 != null) {
      out.write(ATYP_IPV4.toInt())
      out.write(ipv4)
    } else {
      val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
      out.write(ATYP_SOCKS_DOMAIN.toInt())
      out.write(domainBytes.size and 0xFF)
      out.write(domainBytes)
    }
    out.write((targetPort shr 8) and 0xFF)
    out.write(targetPort and 0xFF)
    out.write('\r'.code); out.write('\n'.code)
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

  fun encodeShadowsocksDomainTarget(domain: String, port: Int): ByteArray {
    val domainBytes = domain.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    out.write(ATYP_SOCKS_DOMAIN.toInt())
    out.write(domainBytes.size and 0xFF)
    out.write(domainBytes)
    out.write((port shr 8) and 0xFF)
    out.write(port and 0xFF)
    return out.toByteArray()
  }

  fun shadowsocksKeyLength(method: String): Int = when {
    method.contains("128") -> 16
    else -> 32
  }

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

  fun encryptShadowsocksAeadChunk(
    subkey: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    method: String = "aes-256-gcm",
  ): ByteArray {
    val boundedSize = plaintext.size.coerceAtMost(0x3FFF)
    val chunk = if (plaintext.size == boundedSize) plaintext else plaintext.copyOf(boundedSize)
    val lenBytes = byteArrayOf(
      ((chunk.size shr 8) and 0x3F).toByte(),
      (chunk.size and 0xFF).toByte(),
    )

    val encryptedLen = aeadCrypt(true, method, subkey, nonce, lenBytes)
    incrementNonceLittleEndian(nonce)

    val encryptedPayload = aeadCrypt(true, method, subkey, nonce, chunk)
    incrementNonceLittleEndian(nonce)

    return encryptedLen + encryptedPayload
  }

  fun decryptShadowsocksAeadChunk(
    subkey: ByteArray,
    nonce: ByteArray,
    encryptedChunk: ByteArray,
    method: String = "aes-256-gcm",
  ): ByteArray {
    val encLen = encryptedChunk.copyOfRange(0, 18)
    val lenBytes = aeadCrypt(false, method, subkey, nonce, encLen)
    incrementNonceLittleEndian(nonce)

    val payloadLen = ((lenBytes[0].toInt() and 0x3F) shl 8) or (lenBytes[1].toInt() and 0xFF)
    val encPayload = encryptedChunk.copyOfRange(18, 18 + payloadLen + 16)
    val plain = aeadCrypt(false, method, subkey, nonce, encPayload)
    incrementNonceLittleEndian(nonce)
    return plain
  }

  internal fun aeadCrypt(
    encrypt: Boolean,
    method: String,
    subkey: ByteArray,
    nonce: ByteArray,
    input: ByteArray,
  ): ByteArray {
    val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
    return if (method.contains("chacha20", ignoreCase = true)) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        throw IOException("ChaCha20-Poly1305 requires Android 9+ (use aes-256-gcm)")
      }
      val cipher = Cipher.getInstance("ChaCha20-Poly1305")
      cipher.init(mode, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(nonce))
      cipher.doFinal(input)
    } else {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(mode, SecretKeySpec(subkey, "AES"), GCMParameterSpec(AEAD_TAG_BITS, nonce))
      cipher.doFinal(input)
    }
  }

  fun incrementNonceLittleEndian(nonce: ByteArray) {
    for (i in nonce.indices) {
      val next = (nonce[i].toInt() and 0xFF) + 1
      nonce[i] = (next and 0xFF).toByte()
      if ((next and 0x100) == 0) break
    }
  }

  fun isTlsClientHello(data: ByteArray, offset: Int, length: Int): Boolean {
    return length > 6 &&
      data[offset] == 0x16.toByte() &&
      data[offset + 1] == 0x03.toByte()
  }

  fun writeClientHelloFragmented(
    output: OutputStream,
    data: ByteArray,
    offset: Int,
    length: Int,
    fragmentBytes: Int,
    intervalMs: Int,
  ) {
    // Split opening TLS record header cleanly first, then small chunks past SNI
    val firstCut = 2.coerceAtMost(length)
    output.write(data, offset, firstCut)
    output.flush()

    var pos = offset + firstCut
    val end = offset + length
    val step = fragmentBytes.coerceIn(8, 64)
    val delayMs = intervalMs.coerceIn(1, 10).toLong()
    var chunks = 0
    while (pos < end) {
      if (chunks < 8 && delayMs > 0L) {
        Thread.sleep(delayMs)
      }
      val chunk = (end - pos).coerceAtMost(step)
      output.write(data, pos, chunk)
      output.flush()
      pos += chunk
      chunks++
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

  /**
   * Encodes a single Shadowsocks AEAD UDP packet:
   * [random salt (keyLen)] + AEAD_Encrypt(subkey, zeroNonce, [ATYP + dstAddr + dstPort + payload]).
   */
  fun encodeShadowsocksAeadUdpPacket(
    method: String,
    password: String,
    targetHost: String,
    targetPort: Int,
    payload: ByteArray,
    random: SecureRandom = SecureRandom(),
  ): ByteArray {
    val keyLength = shadowsocksKeyLength(method)
    val masterKey = evpBytesToKey(password, keyLength)
    val salt = ByteArray(keyLength).also { random.nextBytes(it) }
    val subkey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), keyLength)
    val zeroNonce = ByteArray(12)

    val ipv4 = DohDnsResolver.parseNumericIpv4OrNull(targetHost)
    val targetHeader = if (ipv4 != null) {
      encodeShadowsocksIpv4Target(ipv4, targetPort)
    } else {
      encodeShadowsocksDomainTarget(targetHost, targetPort)
    }
    val plaintext = targetHeader + payload
    val encrypted = aeadCrypt(
      encrypt = true,
      method = method,
      subkey = subkey,
      nonce = zeroNonce,
      input = plaintext,
    )
    return salt + encrypted
  }

  /**
   * Decodes a Shadowsocks AEAD UDP response packet into (atyp, addressBytes, port, payload).
   */
  fun decodeShadowsocksAeadUdpPacket(
    method: String,
    password: String,
    packet: ByteArray,
    length: Int,
  ): Socks5UdpDatagram? {
    val keyLength = shadowsocksKeyLength(method)
    if (length <= keyLength + 16 + 7) return null

    val masterKey = evpBytesToKey(password, keyLength)
    val salt = packet.copyOfRange(0, keyLength)
    val subkey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), keyLength)
    val zeroNonce = ByteArray(12)

    val ciphertext = packet.copyOfRange(keyLength, length)
    val plain = runCatching {
      aeadCrypt(
        encrypt = false,
        method = method,
        subkey = subkey,
        nonce = zeroNonce,
        input = ciphertext,
      )
    }.getOrNull() ?: return null

    if (plain.size < 7) return null
    val atyp = plain[0].toInt() and 0xFF
    var idx = 1
    val addrBytes: ByteArray
    val host: String
    when (atyp) {
      0x01 -> {
        if (idx + 4 + 2 > plain.size) return null
        addrBytes = plain.copyOfRange(idx, idx + 4)
        host = InetAddress.getByAddress(addrBytes).hostAddress.orEmpty()
        idx += 4
      }
      0x03 -> {
        val domLen = plain[idx].toInt() and 0xFF
        idx++
        if (idx + domLen + 2 > plain.size) return null
        addrBytes = plain.copyOfRange(idx, idx + domLen)
        host = String(addrBytes, Charsets.UTF_8)
        idx += domLen
      }
      0x04 -> {
        if (idx + 16 + 2 > plain.size) return null
        addrBytes = plain.copyOfRange(idx, idx + 16)
        host = InetAddress.getByAddress(addrBytes).hostAddress.orEmpty()
        idx += 16
      }
      else -> return null
    }
    val port = ((plain[idx].toInt() and 0xFF) shl 8) or (plain[idx + 1].toInt() and 0xFF)
    idx += 2
    val udpPayload = plain.copyOfRange(idx, plain.size)
    return Socks5UdpDatagram(
      atyp = atyp,
      addressBytes = addrBytes,
      hostString = host,
      port = port,
      payload = udpPayload,
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
