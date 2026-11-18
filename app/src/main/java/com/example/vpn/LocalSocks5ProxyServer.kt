package com.example.vpn

import com.example.model.DpiBypassConfig
import com.example.model.VpnServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class Socks5UdpDatagram(
  val atyp: Int,
  val addressBytes: ByteArray,
  val hostString: String,
  val port: Int,
  val payload: ByteArray,
)

/**
 * Local multi-threaded RFC 1928 SOCKS5 proxy server bound to 127.0.0.1.
 * Receives reassembled TCP streams and UDP associations from hev-socks5-tunnel,
 * intercepts DNS (port 53) via DohDnsResolver, drops QUIC UDP 443 when TLS
 * fragmentation bypass is active, and relays TCP/UDP over UpstreamProtocolDialer.
 */
internal class LocalSocks5ProxyServer(
  private val server: VpnServer,
  private val dpiConfig: DpiBypassConfig,
  private val dnsResolver: DohDnsResolver,
  private val dialer: UpstreamProtocolDialer,
  private val protectDatagram: (DatagramSocket) -> Boolean,
  private val onLog: (String) -> Unit = {},
) : Closeable {

  private val running = AtomicBoolean(true)
  private val serverSocket = ServerSocket(0, 128, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
  private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
    Thread(r, "GalkanSocks5Worker").apply { isDaemon = true }
  }
  private val activeSockets = ConcurrentHashMap.newKeySet<Closeable>()

  val port: Int = serverSocket.localPort
  val txBytes = AtomicLong(0L)
  val rxBytes = AtomicLong(0L)
  val packetCount = AtomicLong(0L)

  fun isRunning(): Boolean = running.get() && !serverSocket.isClosed

  fun start() {
    executor.execute {
      while (running.get() && !serverSocket.isClosed) {
        try {
          val client = serverSocket.accept()
          client.tcpNoDelay = true
          activeSockets.add(client)
          executor.execute {
            try {
              handleClient(client)
            } finally {
              activeSockets.remove(client)
              runCatching { client.close() }
            }
          }
        } catch (_: Exception) {
          if (!running.get()) break
        }
      }
    }
  }

  private fun handleClient(client: Socket) {
    val inp = DataInputStream(client.getInputStream())
    val out = client.getOutputStream()

    // 1. Method negotiation
    val ver = inp.readUnsignedByte()
    if (ver != 0x05) return
    val nMethods = inp.readUnsignedByte()
    val methods = ByteArray(nMethods)
    inp.readFully(methods)
    out.write(byteArrayOf(0x05, 0x00)) // NO AUTH
    out.flush()

    // 2. Request header
    val reqVer = inp.readUnsignedByte()
    if (reqVer != 0x05) return
    val cmd = inp.readUnsignedByte()
    inp.readUnsignedByte() // RSV
    val atyp = inp.readUnsignedByte()
    val dstHost = readSocksAddress(inp, atyp) ?: return
    val dstPort = inp.readUnsignedShort()

    when (cmd) {
      CMD_CONNECT -> handleTcpConnect(client, inp, out, dstHost, dstPort)
      CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, inp, out)
      else -> {
        out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
      }
    }
  }

  private fun handleTcpConnect(
    client: Socket,
    inp: InputStream,
    out: OutputStream,
    dstHost: String,
    dstPort: Int,
  ) {
    // Ack CONNECT so lwIP immediately writes the opening payload (e.g. TLS ClientHello)
    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
    out.flush()

    if (dstPort == 53) {
      handleTcpDnsFallback(inp, out)
      return
    }

    // Peek/read opening client packet briefly so TLS ClientHello fragmentation and proxy headers coalesce
    val firstBuf = ByteArray(16384)
    val firstRead = try {
      client.soTimeout = 120
      inp.read(firstBuf)
    } catch (_: SocketTimeoutException) {
      0
    } finally {
      runCatching { client.soTimeout = 0 }
    }
    if (firstRead < 0) return

    val upstream = try {
      dialer.openStream(
        targetHost = dstHost,
        targetPort = dstPort,
        server = server,
        dpiConfig = dpiConfig,
        initialData = if (firstRead > 0) firstBuf else null,
        initialLength = firstRead.coerceAtLeast(0),
      )
    } catch (e: Exception) {
      if (packetCount.get() % 50L == 0L) {
        onLog("Stream dial error ($dstHost:$dstPort): ${e.message}")
      }
      return
    }

    if (firstRead > 0) {
      txBytes.addAndGet(firstRead.toLong())
      packetCount.incrementAndGet()
    }

    activeSockets.add(upstream)
    val downstreamDone = AtomicBoolean(false)

    val downstreamFuture = executor.submit {
      try {
        val buf = ByteArray(RELAY_BUFFER_SIZE)
        while (running.get() && !client.isClosed) {
          val n = upstream.readServerPayload(buf)
          if (n <= 0) break
          out.write(buf, 0, n)
          out.flush()
          rxBytes.addAndGet(n.toLong())
          packetCount.incrementAndGet()
        }
      } catch (_: Exception) {
      } finally {
        downstreamDone.set(true)
        runCatching { client.shutdownOutput() }
      }
    }

    try {
      val upBuf = ByteArray(RELAY_BUFFER_SIZE)
      while (running.get() && !client.isClosed && !downstreamDone.get()) {
        val n = inp.read(upBuf)
        if (n <= 0) break
        upstream.writeClientPayload(upBuf, 0, n)
        txBytes.addAndGet(n.toLong())
        packetCount.incrementAndGet()
      }
    } catch (_: Exception) {
    } finally {
      activeSockets.remove(upstream)
      runCatching { upstream.close() }
      runCatching { downstreamFuture.cancel(true) }
    }
  }

  private fun handleTcpDnsFallback(inp: InputStream, out: OutputStream) {
    val dataIn = DataInputStream(inp)
    try {
      while (running.get()) {
        val msgLen = dataIn.readUnsignedShort()
        if (msgLen <= 0 || msgLen > 8192) break
        val query = ByteArray(msgLen)
        dataIn.readFully(query)
        txBytes.addAndGet(msgLen.toLong())
        packetCount.incrementAndGet()
        val resp = dnsResolver.resolveDnsWirePacket(query, dpiConfig.dnsProvider) ?: break
        out.write((resp.size shr 8) and 0xFF)
        out.write(resp.size and 0xFF)
        out.write(resp)
        out.flush()
        rxBytes.addAndGet(resp.size.toLong())
        packetCount.incrementAndGet()
      }
    } catch (_: Exception) {
    }
  }

  private fun handleUdpAssociate(client: Socket, inp: InputStream, out: OutputStream) {
    val localUdp = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val remoteUdp = DatagramSocket()
    protectDatagram(remoteUdp)
    activeSockets.add(localUdp)
    activeSockets.add(remoteUdp)

    val creds = TunnelProtocolCodec.parseCredentials(server, dpiConfig)
    val ssCreds = creds as? TunnelCredentials.Shadowsocks
    val ssServerAddr: InetAddress? = ssCreds?.let {
      dnsResolver.resolveHost(it.host, dpiConfig.dnsProvider)
    }

    val bindPort = localUdp.localPort
    out.write(
      byteArrayOf(
        0x05, 0x00, 0x00, 0x01,
        127, 0, 0, 1,
        ((bindPort shr 8) and 0xFF).toByte(),
        (bindPort and 0xFF).toByte(),
      ),
    )
    out.flush()

    val sendLock = Any()
    val lastClientAddr = AtomicReference<SocketAddress?>(null)

    val clientReaderFuture = executor.submit {
      val buf = ByteArray(65535)
      while (running.get() && !localUdp.isClosed) {
        try {
          val pkt = DatagramPacket(buf, buf.size)
          localUdp.receive(pkt)
          val senderAddr = pkt.socketAddress
          lastClientAddr.set(senderAddr)
          val parsed = parseSocks5UdpPacket(pkt.data, pkt.length) ?: continue
          txBytes.addAndGet(parsed.payload.size.toLong())
          packetCount.incrementAndGet()

          if (parsed.port == 53) {
            executor.execute {
              val dnsResp = dnsResolver.resolveDnsWirePacket(parsed.payload, dpiConfig.dnsProvider)
              if (dnsResp != null && !localUdp.isClosed) {
                val framed = encodeSocks5UdpDatagram(parsed.atyp, parsed.addressBytes, parsed.port, dnsResp)
                synchronized(sendLock) {
                  runCatching {
                    localUdp.send(DatagramPacket(framed, framed.size, senderAddr))
                  }
                }
                rxBytes.addAndGet(dnsResp.size.toLong())
                packetCount.incrementAndGet()
              }
            }
          } else if (parsed.port == 443 && dpiConfig.tlsFragmentEnabled) {
            // Drop HTTP/3 QUIC UDP 443 so Chrome/YouTube/TikTok cleanly fall back to fragmented TCP 443
            continue
          } else if (ssCreds != null && ssServerAddr != null) {
            val encryptedUdp = TunnelProtocolCodec.encodeShadowsocksAeadUdpPacket(
              method = ssCreds.method,
              password = ssCreds.password,
              targetHost = parsed.hostString,
              targetPort = parsed.port,
              payload = parsed.payload,
            )
            runCatching {
              remoteUdp.send(DatagramPacket(encryptedUdp, encryptedUdp.size, ssServerAddr, ssCreds.port))
            }
          } else {
            val resolved = dnsResolver.resolveHost(parsed.hostString, dpiConfig.dnsProvider) ?: continue
            runCatching {
              remoteUdp.send(DatagramPacket(parsed.payload, parsed.payload.size, resolved, parsed.port))
            }
          }
        } catch (_: Exception) {
          if (!running.get() || localUdp.isClosed) break
        }
      }
    }

    val remoteReaderFuture = executor.submit {
      val buf = ByteArray(65535)
      while (running.get() && !remoteUdp.isClosed && !localUdp.isClosed) {
        try {
          val pkt = DatagramPacket(buf, buf.size)
          remoteUdp.receive(pkt)
          val targetClient = lastClientAddr.get() ?: continue

          if (ssCreds != null) {
            val decoded = TunnelProtocolCodec.decodeShadowsocksAeadUdpPacket(
              method = ssCreds.method,
              password = ssCreds.password,
              packet = pkt.data,
              length = pkt.length,
            ) ?: continue
            val framed = encodeSocks5UdpDatagram(
              decoded.atyp,
              decoded.addressBytes,
              decoded.port,
              decoded.payload,
            )
            synchronized(sendLock) {
              runCatching {
                localUdp.send(DatagramPacket(framed, framed.size, targetClient))
              }
            }
            rxBytes.addAndGet(decoded.payload.size.toLong())
            packetCount.incrementAndGet()
          } else {
            val addrBytes = pkt.address.address
            val atyp = if (addrBytes.size == 16) 0x04 else 0x01
            val payload = pkt.data.copyOfRange(0, pkt.length)
            val framed = encodeSocks5UdpDatagram(atyp, addrBytes, pkt.port, payload)
            synchronized(sendLock) {
              runCatching {
                localUdp.send(DatagramPacket(framed, framed.size, targetClient))
              }
            }
            rxBytes.addAndGet(payload.size.toLong())
            packetCount.incrementAndGet()
          }
        } catch (_: Exception) {
          if (!running.get() || remoteUdp.isClosed) break
        }
      }
    }

    try {
      // Keep association alive until SOCKS5 TCP control stream closes
      val drain = ByteArray(64)
      while (running.get() && !client.isClosed) {
        if (inp.read(drain) < 0) break
      }
    } catch (_: Exception) {
    } finally {
      activeSockets.remove(localUdp)
      activeSockets.remove(remoteUdp)
      runCatching { localUdp.close() }
      runCatching { remoteUdp.close() }
      runCatching { clientReaderFuture.cancel(true) }
      runCatching { remoteReaderFuture.cancel(true) }
    }
  }

  private fun readSocksAddress(inp: DataInputStream, atyp: Int): String? {
    return when (atyp) {
      0x01 -> {
        val addr = ByteArray(4)
        inp.readFully(addr)
        InetAddress.getByAddress(addr).hostAddress
      }
      0x03 -> {
        val len = inp.readUnsignedByte()
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        String(bytes, Charsets.UTF_8)
      }
      0x04 -> {
        val addr = ByteArray(16)
        inp.readFully(addr)
        InetAddress.getByAddress(addr).hostAddress
      }
      else -> null
    }
  }

  override fun close() {
    if (!running.compareAndSet(true, false)) return
    runCatching { serverSocket.close() }
    activeSockets.forEach { runCatching { it.close() } }
    activeSockets.clear()
    runCatching { executor.shutdownNow() }
  }

  companion object {
    private const val CMD_CONNECT = 0x01
    private const val CMD_UDP_ASSOCIATE = 0x03
    private const val RELAY_BUFFER_SIZE = 65536

    fun parseSocks5UdpPacket(data: ByteArray, length: Int): Socks5UdpDatagram? {
      if (length < 10) return null
      if (data[2] != 0.toByte()) return null // Fragmented SOCKS UDP unsupported
      val atyp = data[3].toInt() and 0xFF
      var idx = 4
      val addrBytes: ByteArray
      val host: String
      when (atyp) {
        0x01 -> {
          if (idx + 4 + 2 > length) return null
          addrBytes = data.copyOfRange(idx, idx + 4)
          host = "${addrBytes[0].toInt() and 0xFF}.${addrBytes[1].toInt() and 0xFF}." +
            "${addrBytes[2].toInt() and 0xFF}.${addrBytes[3].toInt() and 0xFF}"
          idx += 4
        }
        0x03 -> {
          if (idx + 1 > length) return null
          val domLen = data[idx].toInt() and 0xFF
          idx++
          if (idx + domLen + 2 > length) return null
          addrBytes = data.copyOfRange(idx, idx + domLen)
          host = String(addrBytes, Charsets.UTF_8)
          idx += domLen
        }
        0x04 -> {
          if (idx + 16 + 2 > length) return null
          addrBytes = data.copyOfRange(idx, idx + 16)
          host = InetAddress.getByAddress(addrBytes).hostAddress.orEmpty()
          idx += 16
        }
        else -> return null
      }
      val port = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
      idx += 2
      val payload = data.copyOfRange(idx, length)
      return Socks5UdpDatagram(
        atyp = atyp,
        addressBytes = addrBytes,
        hostString = host,
        port = port,
        payload = payload,
      )
    }

    fun encodeSocks5UdpDatagram(
      atyp: Int,
      addressBytes: ByteArray,
      port: Int,
      payload: ByteArray,
    ): ByteArray {
      val out = ByteArrayOutputStream(10 + addressBytes.size + payload.size)
      out.write(0x00); out.write(0x00) // RSV
      out.write(0x00) // FRAG
      out.write(atyp and 0xFF)
      if (atyp == 0x03) {
        out.write(addressBytes.size and 0xFF)
      }
      out.write(addressBytes)
      out.write((port shr 8) and 0xFF)
      out.write(port and 0xFF)
      out.write(payload)
      return out.toByteArray()
    }
  }
}
