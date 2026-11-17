package com.example

import com.example.data.ConfigUriParser
import com.example.model.VpnProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigUriParserTest {

  @Test
  fun `parses valid vless reality uri`() {
    val uri = "vless://11111111-2222-3333-4444-555555555555@fra.example.com:443?security=reality&sni=www.microsoft.com#Frankfurt%20Stealth"
    val result = ConfigUriParser.parse(uri)
    assertTrue(result is ConfigUriParser.ParseResult.Success)
    val server = (result as ConfigUriParser.ParseResult.Success).server
    assertEquals("Frankfurt Stealth", server.name)
    assertEquals("fra.example.com", server.host)
    assertEquals(443, server.port)
    assertEquals("www.microsoft.com", server.sni)
    assertEquals(VpnProtocol.VLESS_REALITY, server.protocol)
  }

  @Test
  fun `parses valid trojan uri`() {
    val uri = "trojan://secretpass@ist.example.com:443?sni=cdn.cloudflare.net#Istanbul"
    val result = ConfigUriParser.parse(uri)
    assertTrue(result is ConfigUriParser.ParseResult.Success)
    val server = (result as ConfigUriParser.ParseResult.Success).server
    assertEquals(VpnProtocol.TROJAN_TLS, server.protocol)
    assertEquals("cdn.cloudflare.net", server.sni)
  }

  @Test
  fun `rejects unsupported scheme`() {
    val result = ConfigUriParser.parse("http://not-a-vpn.com")
    assertTrue(result is ConfigUriParser.ParseResult.Error)
  }
}
