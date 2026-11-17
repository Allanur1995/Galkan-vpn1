package com.example

import com.example.model.DpiBypassConfig
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.vpn.TunnelCredentials
import com.example.vpn.TunnelProtocolCodec
import java.util.UUID
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
class StealthTunnelVpnServiceTest {

  @Test
  fun `encodes VLESS v0 binary header with UUID, TCP command, port, and IPv4 address`() {
    val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val ipv4 = byteArrayOf(1, 1, 1, 1)
    val header = TunnelProtocolCodec.encodeVlessRequestHeader(
      uuid = uuid,
      isUdp = false,
      destinationPort = 443,
      destinationIpv4 = ipv4,
    )

    // Version(1) + UUID(16) + AddonsLen(1) + Command(1) + Port(2) + Atyp(1) + IPv4(4) = 26 bytes
    assertEquals(26, header.size)
    assertEquals(0x00.toByte(), header[0]) // Version 0
    assertEquals(0x00.toByte(), header[17]) // Addons length 0
    assertEquals(0x01.toByte(), header[18]) // Command TCP = 1
    assertEquals(0x01.toByte(), header[19]) // Port 443 high byte (0x01BB)
    assertEquals(0xBB.toByte(), header[20]) // Port 443 low byte
    assertEquals(0x01.toByte(), header[21]) // ATYP IPv4 = 1
    assertArrayEquals(ipv4, header.copyOfRange(22, 26))
  }

  @Test
  fun `encrypts and decrypts Shadowsocks AEAD chunk using EVP_BytesToKey and HKDF-SHA1`() {
    val masterKey = TunnelProtocolCodec.evpBytesToKey("secret-ss-pass", 32)
    assertEquals(32, masterKey.size)

    val salt = ByteArray(32) { (it + 1).toByte() }
    val subkey = TunnelProtocolCodec.hkdfSha1(
      ikm = masterKey,
      salt = salt,
      info = "ss-subkey".toByteArray(),
      length = 32,
    )
    assertEquals(32, subkey.size)

    val txNonce = ByteArray(12)
    val rxNonce = ByteArray(12)
    val payload = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()

    val targetPrefix = TunnelProtocolCodec.encodeShadowsocksIpv4Target(
      ipv4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()),
      port = 80,
    )
    val plaintext = targetPrefix + payload

    val encrypted = TunnelProtocolCodec.encryptShadowsocksAeadChunk(subkey, txNonce, plaintext)
    val decrypted = TunnelProtocolCodec.decryptShadowsocksAeadChunk(subkey, rxNonce, encrypted)

    assertArrayEquals(plaintext, decrypted)
    assertEquals(2.toByte(), txNonce[0]) // 2 GCM operations incremented nonce twice
  }

  @Test
  fun `parses VLESS and Shadowsocks credentials and IPv4 packet headers`() {
    val vlessServer = VpnServer(
      id = "vless-test",
      name = "VLESS Test",
      countryCode = "DE",
      flagEmoji = "🇩🇪",
      city = "Frankfurt",
      host = "fra.example.com",
      port = 443,
      protocol = VpnProtocol.VLESS_REALITY,
      rawUri = "vless://11111111-2222-3333-4444-555555555555@fra.example.com:443?security=reality#Test",
    )
    val vlessCreds = TunnelProtocolCodec.parseCredentials(vlessServer, DpiBypassConfig())
    assertTrue(vlessCreds is TunnelCredentials.Vless)
    assertEquals(
      UUID.fromString("11111111-2222-3333-4444-555555555555"),
      (vlessCreds as TunnelCredentials.Vless).uuid,
    )

    val ssServer = VpnServer(
      id = "ss-test",
      name = "SS Test",
      countryCode = "SG",
      flagEmoji = "🇸🇬",
      city = "Singapore",
      host = "sg.example.com",
      port = 8388,
      protocol = VpnProtocol.SHADOWSOCKS_OBFS,
      rawUri = "ss://aes-256-gcm:myStrongPass@sg.example.com:8388#SG",
    )
    val ssCreds = TunnelProtocolCodec.parseCredentials(ssServer, DpiBypassConfig())
    assertTrue(ssCreds is TunnelCredentials.Shadowsocks)
    assertEquals("aes-256-gcm", (ssCreds as TunnelCredentials.Shadowsocks).method)
    assertEquals("myStrongPass", ssCreds.password)

    // Minimal 24-byte IPv4 + TCP port header (dst IP 8.8.8.8, dst port 443)
    val ipv4Packet = ByteArray(24)
    ipv4Packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
    ipv4Packet[9] = 0x06.toByte() // TCP
    ipv4Packet[16] = 8; ipv4Packet[17] = 8; ipv4Packet[18] = 8; ipv4Packet[19] = 8
    ipv4Packet[22] = 0x01.toByte(); ipv4Packet[23] = 0xBB.toByte()

    val parsed = TunnelProtocolCodec.parseIpv4Packet(ipv4Packet)
    assertNotNull(parsed)
    assertEquals(4, parsed!!.ipVersion)
    assertEquals(6, parsed.protocolNumber)
    assertEquals(443, parsed.destinationPort)
    assertArrayEquals(byteArrayOf(8, 8, 8, 8), parsed.destinationAddress)
  }
}
