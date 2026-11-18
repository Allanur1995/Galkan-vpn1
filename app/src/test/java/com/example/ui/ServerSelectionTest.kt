package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.VpnUiState
import com.example.data.DefaultServers
import com.example.model.AppLanguage
import com.example.model.ServerNodeStatus
import com.example.model.VpnServer
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.theme.GalkanVpnTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerSelectionTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `VpnServer resolveStatus maps connected, checking, online, high load, offline, and standby`() {
    assertEquals(
      ServerNodeStatus.CONNECTED,
      VpnServer.resolveStatus(pingMs = 35, loadPercent = 20, wasProbed = true, isPinging = false, isConnected = true),
    )
    assertEquals(
      ServerNodeStatus.CHECKING,
      VpnServer.resolveStatus(pingMs = null, loadPercent = 20, wasProbed = false, isPinging = true, isConnected = false),
    )
    assertEquals(
      ServerNodeStatus.ONLINE,
      VpnServer.resolveStatus(pingMs = 45, loadPercent = 30, wasProbed = true, isPinging = false, isConnected = false),
    )
    assertEquals(
      ServerNodeStatus.HIGH_LOAD,
      VpnServer.resolveStatus(pingMs = 60, loadPercent = 85, wasProbed = true, isPinging = false, isConnected = false),
    )
    assertEquals(
      ServerNodeStatus.OFFLINE,
      VpnServer.resolveStatus(pingMs = null, loadPercent = 20, wasProbed = true, isPinging = false, isConnected = false),
    )
    assertEquals(
      ServerNodeStatus.STANDBY,
      VpnServer.resolveStatus(pingMs = null, loadPercent = 20, wasProbed = false, isPinging = false, isConnected = false),
    )
  }

  @Test
  fun `HomeScreen opens server selection drawer showing latency and status and allows node selection`() {
    val first = DefaultServers.curatedServers[0].copy(
      pingMs = 38,
      nodeStatus = ServerNodeStatus.ONLINE,
    )
    val second = DefaultServers.curatedServers[1].copy(
      pingMs = 74,
      nodeStatus = ServerNodeStatus.ONLINE,
    )
    val third = DefaultServers.curatedServers[2].copy(
      pingMs = null,
      nodeStatus = ServerNodeStatus.OFFLINE,
    )
    val state = VpnUiState(
      servers = listOf(first, second, third),
      selectedServer = first,
    )
    val strings = stringsFor(AppLanguage.ENGLISH)
    val chosen = AtomicReference<VpnServer?>(null)

    composeTestRule.setContent {
      GalkanVpnTheme(darkTheme = true) {
        HomeScreen(
          uiState = state,
          strings = strings,
          onToggleVpn = {},
          onNavigateServers = {},
          onNavigateStealth = {},
          onNavigatePro = {},
          onWatchAdFreeTime = {},
          onWatchAdProTrial = {},
          onRunDiagnostics = {},
          onSelectLanguage = {},
          onRequestBatteryOptimization = {},
          onSelectServer = { chosen.set(it) },
        )
      }
    }

    composeTestRule.onNodeWithTag("home_server_card").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("server_selection_drawer").assertIsDisplayed()
    composeTestRule.onNodeWithText("38ms", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("74ms", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_server_item_${second.id}").performClick()
    composeTestRule.waitForIdle()

    assertEquals(second.id, chosen.get()?.id)
  }

  @Test
  fun `ServersScreen displays pre-defined nodes with latency and status pills`() {
    val nodes = listOf(
      DefaultServers.curatedServers[0].copy(pingMs = 41, nodeStatus = ServerNodeStatus.CONNECTED),
      DefaultServers.curatedServers[1].copy(pingMs = null, nodeStatus = ServerNodeStatus.OFFLINE),
    )
    val state = VpnUiState(
      servers = nodes,
      selectedServer = nodes.first(),
    )
    val strings = stringsFor(AppLanguage.ENGLISH)

    composeTestRule.setContent {
      GalkanVpnTheme(darkTheme = true) {
        ServersScreen(
          uiState = state,
          strings = strings,
          onSelectServer = {},
          onAutoSelectFastest = {},
          onPingAll = {},
          onImportUri = { _, _, _ -> },
          onDeleteCustomServer = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("server_item_${nodes[0].id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("server_status_${nodes[0].id}").assertIsDisplayed()
    composeTestRule.onNodeWithText("41ms", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("server_status_${nodes[1].id}").assertIsDisplayed()
  }
}
