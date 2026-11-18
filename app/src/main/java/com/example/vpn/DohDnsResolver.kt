package com.example.vpn

import com.example.model.DnsProvider
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class DnsQuestion(
  val txId: Int,
  val qname: String,
  val qtype: Int,
)

/**
 * Resolves DNS wire packets and hostnames over Encrypted DNS-over-HTTPS (DoH)
 * using VpnService-protected sockets and hardcoded bootstrap DoH IPs so DNS
 * never loops back through tun0 and remains immune to ISP DNS poisoning.
 */
class DohDnsResolver(
  private val protectSocket: (Socket) -> Boolean,
  private val protectDatagram: (DatagramSocket) -> Boolean,
  private val fragmentDohTls: Boolean = true,
  private val onLog: (String) -> Unit = {},
) {

  private data class CachedResponse(
    val wireBytes: ByteArray,
    val expiresAtMs: Long,
  )

  private data class CachedHost(
    val address: InetAddress,
    val expiresAtMs: Long,
  )

  private val wireCache = ConcurrentHashMap<String, CachedResponse>()
  private val hostCache = ConcurrentHashMap<String, CachedHost>()

  private val bootstrapDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      val key = hostname.lowercase()
      val presetIps = BOOTSTRAP_MAP[key]
      if (presetIps != null) {
        return presetIps.map { InetAddress.getByAddress(hostname, parseIpv4Bytes(it)) }
      }
      return Dns.SYSTEM.lookup(hostname)
    }
  }

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .socketFactory(
      ProtectedSocketFactory(
        protectFn = protectSocket,
        tlsFragmentBytes = if (fragmentDohTls) 12 else 0,
      ),
    )
    .dns(bootstrapDns)
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .writeTimeout(4, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

  fun resolveDnsWirePacket(query: ByteArray, primaryProvider: DnsProvider): ByteArray? {
    if (query.size < 12) return null
    val question = parseDnsQuestion(query)
    val now = System.currentTimeMillis()
    val cacheKey = question?.let { "${it.qname.lowercase()}:${it.qtype}" }

    if (cacheKey != null) {
      val cached = wireCache[cacheKey]
      if (cached != null && cached.expiresAtMs > now && cached.wireBytes.size >= 12) {
        val patched = cached.wireBytes.copyOf()
        patched[0] = query[0]
        patched[1] = query[1]
        return patched
      }
    }

    // Try selected DoH provider first, then fast resilient DoH backups, then protected UDP
    val providers = listOf(primaryProvider) +
      listOf(DnsProvider.CLOUDFLARE, DnsProvider.GOOGLE, DnsProvider.QUAD9)
        .filter { it != primaryProvider }

    for (provider in providers.take(2)) {
      val dohResult = queryDoh(query, provider.dohEndpoint)
      if (dohResult != null && dohResult.size >= 12) {
        cacheWireResponse(cacheKey, dohResult, now)
        return dohResult
      }
    }

    val udpResult = queryProtectedUdp(query, primaryProvider.primaryIp)
      ?: if (primaryProvider.primaryIp != "1.1.1.1") queryProtectedUdp(query, "1.1.1.1") else null

    if (udpResult != null && udpResult.size >= 12) {
      cacheWireResponse(cacheKey, udpResult, now)
      return udpResult
    }

    onLog("DNS resolution failed for ${question?.qname ?: "unknown"}")
    return null
  }

  fun resolveHost(host: String, provider: DnsProvider): InetAddress? {
    val clean = host.trim()
    if (clean.isEmpty()) return null

    parseNumericIpv4OrNull(clean)?.let { ipBytes ->
      return InetAddress.getByAddress(clean, ipBytes)
    }

    if (clean.contains(':')) {
      return runCatching { InetAddress.getByName(clean) }.getOrNull()
    }

    val now = System.currentTimeMillis()
    val cached = hostCache[clean.lowercase()]
    if (cached != null && cached.expiresAtMs > now) {
      return cached.address
    }

    val queryPacket = buildDnsQueryPacket(clean, qtype = 1, txId = (System.nanoTime() and 0xFFFF).toInt())
    val response = resolveDnsWirePacket(queryPacket, provider)
    if (response != null) {
      val ipBytes = extractFirstARecord(response)
      if (ipBytes != null) {
        val addr = InetAddress.getByAddress(clean, ipBytes)
        hostCache[clean.lowercase()] = CachedHost(addr, now + DEFAULT_TTL_MS)
        return addr
      }
    }

    return runCatching { InetAddress.getByName(clean) }.getOrNull()
  }

  private fun queryDoh(dnsQuery: ByteArray, endpointUrl: String): ByteArray? {
    return try {
      val body = dnsQuery.toRequestBody(DOH_MEDIA_TYPE)
      val request = Request.Builder()
        .url(endpointUrl)
        .post(body)
        .header("Accept", "application/dns-message")
        .header("Content-Type", "application/dns-message")
        .build()

      httpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        resp.body?.bytes()?.takeIf { it.size >= 12 }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun queryProtectedUdp(dnsQuery: ByteArray, dnsIp: String): ByteArray? {
    var socket: DatagramSocket? = null
    return try {
      socket = DatagramSocket()
      protectDatagram(socket)
      socket.soTimeout = 1800
      val addr = InetAddress.getByAddress(parseIpv4Bytes(dnsIp))
      socket.send(DatagramPacket(dnsQuery, dnsQuery.size, addr, 53))
      val recvBuf = ByteArray(2048)
      val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
      socket.receive(recvPacket)
      recvBuf.copyOf(recvPacket.length).takeIf { it.size >= 12 }
    } catch (_: Exception) {
      null
    } finally {
      runCatching { socket?.close() }
    }
  }

  private fun cacheWireResponse(cacheKey: String?, response: ByteArray, nowMs: Long) {
    if (cacheKey == null || response.size < 12) return
    val rcode = response[3].toInt() and 0x0F
    if (rcode == 0) {
      if (wireCache.size > MAX_CACHE_ENTRIES) {
        wireCache.clear()
      }
      wireCache[cacheKey] = CachedResponse(response.copyOf(), nowMs + DEFAULT_TTL_MS)
    }
  }

  companion object {
    private val DOH_MEDIA_TYPE = "application/dns-message".toMediaType()
    private const val DEFAULT_TTL_MS = 90_000L
    private const val MAX_CACHE_ENTRIES = 512

    private val BOOTSTRAP_MAP: Map<String, List<String>> = mapOf(
      "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
      "1dot1dot1dot1.cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
      "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
      "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
      "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
    )

    fun parseDnsQuestion(packet: ByteArray): DnsQuestion? {
      if (packet.size < 17) return null
      val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
      val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
      if (qdCount < 1) return null

      var idx = 12
      val domain = StringBuilder()
      while (idx < packet.size) {
        val labelLen = packet[idx].toInt() and 0xFF
        idx++
        if (labelLen == 0) break
        if ((labelLen and 0xC0) != 0 || idx + labelLen > packet.size) return null
        if (domain.isNotEmpty()) domain.append('.')
        domain.append(String(packet, idx, labelLen, Charsets.US_ASCII))
        idx += labelLen
      }
      if (idx + 4 > packet.size) return null
      val qtype = ((packet[idx].toInt() and 0xFF) shl 8) or (packet[idx + 1].toInt() and 0xFF)
      return DnsQuestion(txId = txId, qname = domain.toString(), qtype = qtype)
    }

    fun buildDnsQueryPacket(domain: String, qtype: Int = 1, txId: Int = 0x1234): ByteArray {
      val out = ByteArrayOutputStream()
      out.write((txId shr 8) and 0xFF)
      out.write(txId and 0xFF)
      out.write(0x01) // Standard query, recursion desired
      out.write(0x00)
      out.write(0x00); out.write(0x01) // QDCOUNT = 1
      out.write(0x00); out.write(0x00) // ANCOUNT = 0
      out.write(0x00); out.write(0x00) // NSCOUNT = 0
      out.write(0x00); out.write(0x00) // ARCOUNT = 0

      domain.trim().trimEnd('.').split('.').forEach { label ->
        val bytes = label.toByteArray(Charsets.US_ASCII)
        if (bytes.isNotEmpty() && bytes.size <= 63) {
          out.write(bytes.size)
          out.write(bytes)
        }
      }
      out.write(0x00)
      out.write((qtype shr 8) and 0xFF)
      out.write(qtype and 0xFF)
      out.write(0x00); out.write(0x01) // QCLASS = IN
      return out.toByteArray()
    }

    fun extractFirstARecord(response: ByteArray): ByteArray? {
      if (response.size < 12) return null
      val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
      val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
      if (anCount == 0) return null

      var idx = 12
      repeat(qdCount) {
        idx = skipDnsName(response, idx) ?: return null
        idx += 4 // QTYPE + QCLASS
        if (idx > response.size) return null
      }

      repeat(anCount) {
        idx = skipDnsName(response, idx) ?: return null
        if (idx + 10 > response.size) return null
        val type = ((response[idx].toInt() and 0xFF) shl 8) or (response[idx + 1].toInt() and 0xFF)
        val rdLength = ((response[idx + 8].toInt() and 0xFF) shl 8) or (response[idx + 9].toInt() and 0xFF)
        idx += 10
        if (idx + rdLength > response.size) return null
        if (type == 1 && rdLength == 4) {
          return response.copyOfRange(idx, idx + 4)
        }
        idx += rdLength
      }
      return null
    }

    private fun skipDnsName(packet: ByteArray, startIdx: Int): Int? {
      var idx = startIdx
      while (idx < packet.size) {
        val len = packet[idx].toInt() and 0xFF
        if (len == 0) return idx + 1
        if ((len and 0xC0) == 0xC0) {
          return if (idx + 2 <= packet.size) idx + 2 else null
        }
        idx += 1 + len
      }
      return null
    }

    fun parseNumericIpv4OrNull(host: String): ByteArray? {
      val parts = host.split('.')
      if (parts.size != 4) return null
      val out = ByteArray(4)
      for (i in 0 until 4) {
        val v = parts[i].toIntOrNull() ?: return null
        if (v !in 0..255) return null
        out[i] = v.toByte()
      }
      return out
    }

    private fun parseIpv4Bytes(ip: String): ByteArray =
      parseNumericIpv4OrNull(ip) ?: (InetAddress.getByName(ip) as Inet4Address).address
  }
}

/**
 * SocketFactory whose created sockets are immediately passed to VpnService.protect()
 * and optionally fragment the opening TLS ClientHello TCP write to evade DPI SNI blocks.
 */
class ProtectedSocketFactory(
  private val protectFn: (Socket) -> Boolean,
  private val tlsFragmentBytes: Int = 0,
) : SocketFactory() {

  override fun createSocket(): Socket = createProtectedSocket()

  override fun createSocket(host: String, port: Int): Socket =
    createProtectedSocket().apply { connect(InetSocketAddress(host, port), 4000) }

  override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
    createProtectedSocket().apply {
      bind(InetSocketAddress(localHost, localPort))
      connect(InetSocketAddress(host, port), 4000)
    }

  override fun createSocket(host: InetAddress, port: Int): Socket =
    createProtectedSocket().apply { connect(InetSocketAddress(host, port), 4000) }

  override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
    createProtectedSocket().apply {
      bind(InetSocketAddress(localAddress, localPort))
      connect(InetSocketAddress(address, port), 4000)
    }

  private fun createProtectedSocket(): Socket {
    val base = object : Socket() {
      override fun connect(endpoint: SocketAddress?) {
        protectFn(this)
        super.connect(endpoint)
      }

      override fun connect(endpoint: SocketAddress?, timeout: Int) {
        protectFn(this)
        super.connect(endpoint, timeout)
      }

      override fun getOutputStream(): OutputStream {
        val raw = super.getOutputStream()
        return if (tlsFragmentBytes > 0) {
          TlsClientHelloFragmentingOutputStream(raw, tlsFragmentBytes)
        } else {
          raw
        }
      }
    }
    runCatching {
      base.tcpNoDelay = true
      protectFn(base)
    }
    return base
  }
}

/**
 * Splits only the first outbound TLS ClientHello record across tiny TCP segments
 * to prevent DPI appliances from reassembling the plaintext SNI hostname, then forwards
 * all subsequent writes at full wire speed without artificial overhead.
 */
internal class TlsClientHelloFragmentingOutputStream(
  private val delegate: OutputStream,
  private val fragmentBytes: Int,
) : FilterOutputStream(delegate) {

  private var clientHelloHandled = false

  override fun write(b: Int) {
    delegate.write(b)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    if (!clientHelloHandled && len > 6 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte()) {
      clientHelloHandled = true
      val firstCut = 2.coerceAtMost(len)
      delegate.write(b, off, firstCut)
      delegate.flush()
      if (len > firstCut) {
        var pos = off + firstCut
        val end = off + len
        val step = fragmentBytes.coerceIn(8, 64)
        var splits = 0
        while (pos < end) {
          val chunk = (end - pos).coerceAtMost(step)
          if (splits in 1..6) {
            Thread.sleep(2L)
          }
          delegate.write(b, pos, chunk)
          delegate.flush()
          pos += chunk
          splits++
        }
      }
      return
    }
    clientHelloHandled = true
    delegate.write(b, off, len)
  }
}
