package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DefaultServers
import com.example.model.DpiBypassConfig
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.monetization.AdFailureKind
import com.example.monetization.AdManager
import com.example.vpn.DohDnsResolver
import com.example.vpn.HevVpnEngine
import com.example.vpn.LocalSocks5ProxyServer
import com.example.vpn.TunnelCredentials
import com.example.vpn.TunnelProtocolCodec
import com.example.vpn.UpstreamProtocolDialer
import com.google.android.gms.ads.AdRequest
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TunnelAndAdEngineTest {

  @Test
  fun `built-in servers map to DirectAntiDpi while imported URIs parse protocol credentials`() {
    val builtinCreds = TunnelProtocolCodec.parseCredentials(
      DefaultServers.curatedServers.first(),
      DpiBypassConfig(),
    )
    assertTrue(builtinCreds is TunnelCredentials.DirectAntiDpi)

    val trojanServer = VpnServer(
      id = "trojan-custom",
      name = "My Trojan",
      countryCode = "CUSTOM",
      flagEmoji = "🛰️",
      city = "example.com",
      host = "example.com",
      port = 443,
      protocol = VpnProtocol.TROJAN_TLS,
      rawUri = "trojan://super-secret-pass@example.com:443?sni=cdn.example.com#MyTrojan",
    )
    val trojanCreds = TunnelProtocolCodec.parseCredentials(trojanServer, DpiBypassConfig())
    assertTrue(trojanCreds is TunnelCredentials.Trojan)
    assertEquals("super-secret-pass", (trojanCreds as TunnelCredentials.Trojan).password)
  }

  @Test
  fun `encodes Trojan SHA224 request header with CRLF and target host`() {
    val header = TunnelProtocolCodec.encodeTrojanRequestHeader(
      password = "test-password",
      targetHost = "1.1.1.1",
      targetPort = 443,
    )
    // SHA-224 hex is 56 bytes + CRLF(2) + CMD(1) + ATYP(1) + IPv4(4) + PORT(2) + CRLF(2) = 68 bytes
    assertEquals(68, header.size)
    assertEquals('\r'.code.toByte(), header[56])
    assertEquals('\n'.code.toByte(), header[57])
    assertEquals(0x01.toByte(), header[58]) // TCP
    assertEquals(0x01.toByte(), header[59]) // ATYP_IPV4
    assertEquals('\r'.code.toByte(), header[66])
    assertEquals('\n'.code.toByte(), header[67])
  }

  @Test
  fun `builds DNS query parses question and extracts A record cleanly`() {
    val query = DohDnsResolver.buildDnsQueryPacket("www.youtube.com", qtype = 1, txId = 0x4242)
    val parsed = DohDnsResolver.parseDnsQuestion(query)
    assertNotNull(parsed)
    assertEquals(0x4242, parsed!!.txId)
    assertEquals("www.youtube.com", parsed.qname)
    assertEquals(1, parsed.qtype)

    // Append minimal synthetic A-record answer (142.250.185.78)
    val out = ByteArrayOutputStream()
    out.write(query)
    val response = out.toByteArray()
    response[2] = 0x81.toByte()
    response[3] = 0x80.toByte()
    response[7] = 0x01.toByte() // ANCOUNT = 1

    val fullResp = ByteArrayOutputStream().apply {
      write(response)
      write(byteArrayOf(0xC0.toByte(), 0x0C)) // Name pointer to offset 12
      write(byteArrayOf(0x00, 0x01)) // TYPE A
      write(byteArrayOf(0x00, 0x01)) // CLASS IN
      write(byteArrayOf(0x00, 0x00, 0x00, 0x3C)) // TTL 60s
      write(byteArrayOf(0x00, 0x04)) // RDLENGTH = 4
      write(byteArrayOf(142.toByte(), 250.toByte(), 185.toByte(), 78))
    }.toByteArray()

    val extracted = DohDnsResolver.extractFirstARecord(fullResp)
    assertArrayEquals(byteArrayOf(142.toByte(), 250.toByte(), 185.toByte(), 78), extracted)
  }

  @Test
  fun `LocalSocks5ProxyServer relays TLS ClientHello fragmented stream to local TCP server`() {
    val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val echoLatch = CountDownLatch(1)
    val receivedOnUpstream = ByteArrayOutputStream()

    val upstreamWorker = thread {
      val s = echoServer.accept()
      val inp = s.getInputStream()
      val out = s.getOutputStream()
      val buf = ByteArray(1024)
      var total = 0
      while (total < 32) {
        val n = inp.read(buf)
        if (n <= 0) break
        receivedOnUpstream.write(buf, 0, n)
        total += n
      }
      out.write("SERVER_HELLO_OK".toByteArray())
      out.flush()
      echoLatch.countDown()
      Thread.sleep(100)
      s.close()
    }

    val dnsResolver = DohDnsResolver(protectSocket = { true }, protectDatagram = { true })
    val dialer = UpstreamProtocolDialer(dnsResolver = dnsResolver, protectSocket = { true })
    val proxy = LocalSocks5ProxyServer(
      server = DefaultServers.curatedServers.first(),
      dpiConfig = DpiBypassConfig(tlsFragmentEnabled = true, fragmentSizeBytes = 8, fragmentIntervalMs = 2),
      dnsResolver = dnsResolver,
      dialer = dialer,
      protectDatagram = { true },
    )
    proxy.start()

    try {
      Socket("127.0.0.1", proxy.port).use { client ->
        client.soTimeout = 4000
        val out = client.getOutputStream()
        val inp = DataInputStream(client.getInputStream())

        // SOCKS5 Greeting: NO AUTH
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greetResp = ByteArray(2)
        inp.readFully(greetResp)
        assertArrayEquals(byteArrayOf(0x05, 0x00), greetResp)

        // SOCKS5 CONNECT -> 127.0.0.1:<echoPort>
        val echoPort = echoServer.localPort
        out.write(
          byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            127, 0, 0, 1,
            ((echoPort shr 8) and 0xFF).toByte(),
            (echoPort and 0xFF).toByte(),
          ),
        )
        out.flush()

        val connResp = ByteArray(10)
        inp.readFully(connResp)
        assertEquals(0x00.toByte(), connResp[1]) // SUCCEEDED

        // Write synthetic TLS ClientHello header (0x16 0x03 ...) -> triggers fragmentation
        val syntheticClientHello = ByteArray(32) { i -> (i + 1).toByte() }
        syntheticClientHello[0] = 0x16.toByte()
        syntheticClientHello[1] = 0x03.toByte()
        syntheticClientHello[2] = 0x01.toByte()
        out.write(syntheticClientHello)
        out.flush()

        assertTrue(echoLatch.await(3, TimeUnit.SECONDS))
        val replyBuf = ByteArray(15)
        inp.readFully(replyBuf)
        assertEquals("SERVER_HELLO_OK", String(replyBuf))
        assertArrayEquals(syntheticClientHello, receivedOnUpstream.toByteArray())
        assertTrue(proxy.txBytes.get() >= 32L)
        assertTrue(proxy.rxBytes.get() >= 15L)
      }
    } finally {
      proxy.close()
      echoServer.close()
      upstreamWorker.join(500)
    }
  }

  @Test
  fun `encodes and parses RFC 1928 SOCKS5 UDP datagrams`() {
    val payload = byteArrayOf(1, 2, 3, 4, 5)
    val ip = byteArrayOf(1, 1, 1, 1)
    val framed = LocalSocks5ProxyServer.encodeSocks5UdpDatagram(0x01, ip, 53, payload)
    val parsed = LocalSocks5ProxyServer.parseSocks5UdpPacket(framed, framed.size)
    assertNotNull(parsed)
    assertEquals("1.1.1.1", parsed!!.hostString)
    assertEquals(53, parsed.port)
    assertArrayEquals(payload, parsed.payload)
  }

  @Test
  fun `generates dual-stack hev-socks5-tunnel YAML config and classifies AdMob errors`() {
    val yaml = HevVpnEngine.buildHevConfigYaml(mtu = 1400, socksPort = 10808)
    assertTrue(yaml.contains("mtu: 1400"))
    assertTrue(yaml.contains("ipv4: 10.8.0.2"))
    assertTrue(yaml.contains("ipv6: 'fc00::1'"))
    assertTrue(yaml.contains("port: 10808"))
    assertTrue(yaml.contains("udp: 'udp'"))
    assertTrue(yaml.contains("tcp-buffer-size: 65536"))

    assertEquals(
      AdFailureKind.NETWORK_ERROR,
      AdManager.classifyLoadError(AdRequest.ERROR_CODE_NETWORK_ERROR, "Network error"),
    )
    assertEquals(
      AdFailureKind.NO_FILL,
      AdManager.classifyLoadError(AdRequest.ERROR_CODE_NO_FILL, "No fill"),
    )
    assertEquals(5_000L, AdManager.computeRetryDelayMs(1))
    assertEquals(12_000L, AdManager.computeRetryDelayMs(2))
    assertEquals(25_000L, AdManager.computeRetryDelayMs(3))

    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val manager = AdManager(ctx)
    assertTrue(manager.isUsingTestIds)
  }

  @Test
  fun `encodes and decodes Shadowsocks AEAD UDP packets cleanly`() {
    val payload = "UDP_HELLO_PAYLOAD".toByteArray()
    val encoded = TunnelProtocolCodec.encodeShadowsocksAeadUdpPacket(
      method = "aes-256-gcm",
      password = "udp-secret-password",
      targetHost = "9.9.9.9",
      targetPort = 3478,
      payload = payload,
    )
    val decoded = TunnelProtocolCodec.decodeShadowsocksAeadUdpPacket(
      method = "aes-256-gcm",
      password = "udp-secret-password",
      packet = encoded,
      length = encoded.size,
    )
    assertNotNull(decoded)
    assertEquals("9.9.9.9", decoded!!.hostString)
    assertEquals(3478, decoded.port)
    assertArrayEquals(payload, decoded.payload)
  }

  @Test
  fun `LocalSocks5ProxyServer relays UDP Associate datagrams to local UDP echo endpoint`() {
    val udpEchoServer = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val echoThread = thread {
      val buf = ByteArray(1024)
      val pkt = java.net.DatagramPacket(buf, buf.size)
      runCatching {
        udpEchoServer.receive(pkt)
        val reply = ("ECHO:" + String(pkt.data, 0, pkt.length)).toByteArray()
        udpEchoServer.send(java.net.DatagramPacket(reply, reply.size, pkt.address, pkt.port))
      }
    }

    val dnsResolver = DohDnsResolver(protectSocket = { true }, protectDatagram = { true })
    val dialer = UpstreamProtocolDialer(dnsResolver = dnsResolver, protectSocket = { true })
    val proxy = LocalSocks5ProxyServer(
      server = DefaultServers.curatedServers.first(),
      dpiConfig = DpiBypassConfig(tlsFragmentEnabled = true),
      dnsResolver = dnsResolver,
      dialer = dialer,
      protectDatagram = { true },
    )
    proxy.start()

    try {
      Socket("127.0.0.1", proxy.port).use { tcpControl ->
        tcpControl.soTimeout = 4000
        val out = tcpControl.getOutputStream()
        val inp = DataInputStream(tcpControl.getInputStream())

        // SOCKS5 Greeting
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greetResp = ByteArray(2)
        inp.readFully(greetResp)

        // SOCKS5 CMD_UDP_ASSOCIATE (0x03)
        out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
        val assocResp = ByteArray(10)
        inp.readFully(assocResp)
        assertEquals(0x00.toByte(), assocResp[1])
        val bndPort = ((assocResp[8].toInt() and 0xFF) shl 8) or (assocResp[9].toInt() and 0xFF)

        java.net.DatagramSocket().use { clientUdp ->
          clientUdp.soTimeout = 4000
          val framed = LocalSocks5ProxyServer.encodeSocks5UdpDatagram(
            atyp = 0x01,
            addressBytes = byteArrayOf(127, 0, 0, 1),
            port = udpEchoServer.localPort,
            payload = "PING_UDP".toByteArray(),
          )
          clientUdp.send(
            java.net.DatagramPacket(
              framed,
              framed.size,
              InetAddress.getByName("127.0.0.1"),
              bndPort,
            ),
          )

          val respBuf = ByteArray(1024)
          val respPkt = java.net.DatagramPacket(respBuf, respBuf.size)
          clientUdp.receive(respPkt)
          val parsed = LocalSocks5ProxyServer.parseSocks5UdpPacket(respPkt.data, respPkt.length)
          assertNotNull(parsed)
          assertEquals("ECHO:PING_UDP", String(parsed!!.payload))
        }
      }
    } finally {
      proxy.close()
      udpEchoServer.close()
      echoThread.join(500)
    }
  }
}
