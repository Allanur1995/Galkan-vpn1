package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.VpnViewModel
import com.example.model.VpnConnectionState
import com.example.ui.components.AdBannerFooter
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProPaywallScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StealthScreen
import com.example.ui.theme.AmberPro
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.GalkanVpnTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object RouteHome
@Serializable data object RouteServers
@Serializable data object RouteStealth
@Serializable data object RoutePro
@Serializable data object RouteSettings

private data class NavTabItem(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@Composable
fun GalkanApp(viewModel: VpnViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val strings = remember(uiState.prefs.language) { stringsFor(uiState.prefs.language) }
  val navController = rememberNavController()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val activity = context as? Activity
  val lifecycleOwner = LocalLifecycleOwner.current

  var showBatteryOptDialog by remember { mutableStateOf(false) }
  var hasPromptedBatteryOptThisSession by remember { mutableStateOf(false) }
  var connectAfterBatteryReturn by remember { mutableStateOf(false) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryOptimizationStatus()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(Unit) {
    viewModel.uiMessages.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  val vpnConsentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      viewModel.startVpnConnection()
    } else {
      scope.launch { snackbarHostState.showSnackbar(strings.vpnPermissionNeeded) }
    }
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { _ ->
    val consentIntent = viewModel.prepareVpnConsentIntent()
    if (consentIntent != null) {
      vpnConsentLauncher.launch(consentIntent)
    } else {
      viewModel.startVpnConnection()
    }
  }

  val proceedWithVpnConnect: () -> Unit = {
    viewModel.checkReachabilityAndPrepareConnect(strings) {
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        val consentIntent = viewModel.prepareVpnConsentIntent()
        if (consentIntent != null) {
          vpnConsentLauncher.launch(consentIntent)
        } else {
          viewModel.startVpnConnection()
        }
      }
    }
  }

  val batteryOptimizationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) {
    viewModel.refreshBatteryOptimizationStatus()
    if (connectAfterBatteryReturn) {
      connectAfterBatteryReturn = false
      proceedWithVpnConnect()
    }
  }

  val launchBatteryOptimizationPrompt: () -> Unit = {
    runCatching {
      batteryOptimizationLauncher.launch(viewModel.buildIgnoreBatteryOptimizationIntent())
    }.onFailure {
      runCatching { context.startActivity(viewModel.buildBatterySettingsIntent()) }
    }
  }

  val openBatterySettings: () -> Unit = {
    runCatching {
      batteryOptimizationLauncher.launch(viewModel.buildBatterySettingsIntent())
    }
  }

  val onToggleVpn: () -> Unit = {
    val isActive = uiState.connectionState is VpnConnectionState.Connected ||
      uiState.connectionState is VpnConnectionState.Connecting
    if (isActive) {
      viewModel.disconnectVpn(activity)
    } else {
      if (uiState.selectedServer.isProOnly && !uiState.hasProAccess) {
        scope.launch { snackbarHostState.showSnackbar(strings.proLockedMessage) }
        navController.navigate(RoutePro) {
          popUpTo(navController.graph.findStartDestination().id) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      } else if (!uiState.isBatteryOptimizationIgnored && !hasPromptedBatteryOptThisSession) {
        hasPromptedBatteryOptThisSession = true
        showBatteryOptDialog = true
      } else {
        proceedWithVpnConnect()
      }
    }
  }

  val navigateToTab: (Any) -> Unit = { destination ->
    navController.navigate(destination) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  val tabs = remember(strings) {
    listOf(
      NavTabItem(RouteHome, strings.navHome, Icons.Filled.Shield, Icons.Outlined.Shield, "tab_home"),
      NavTabItem(RouteServers, strings.navServers, Icons.Filled.Public, Icons.Outlined.Public, "tab_servers"),
      NavTabItem(RouteStealth, strings.navStealth, Icons.Filled.Security, Icons.Outlined.Security, "tab_stealth"),
      NavTabItem(RoutePro, strings.navPro, Icons.Filled.WorkspacePremium, Icons.Outlined.WorkspacePremium, "tab_pro"),
      NavTabItem(RouteSettings, strings.navSettings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings"),
    )
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination

  GalkanVpnTheme(darkTheme = uiState.prefs.isDarkTheme) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useNavRail = maxWidth >= 600.dp

      Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
          Column {
            AdBannerFooter(
              adManager = viewModel.adManager,
              isProActive = uiState.hasProAccess,
            )
            if (!useNavRail) {
              NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab ->
                  val selected = currentDestination?.hierarchy?.any {
                    it.hasRoute(tab.route::class)
                  } == true
                  NavigationBarItem(
                    selected = selected,
                    onClick = { navigateToTab(tab.route) },
                    icon = {
                      Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                      )
                    },
                    label = {
                      Text(
                        text = tab.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                    modifier = Modifier.testTag(tab.testTag),
                  )
                }
              }
            }
          }
        },
      ) { innerPadding ->
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        ) {
          if (useNavRail) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
              tabs.forEach { tab ->
                val selected = currentDestination?.hierarchy?.any {
                  it.hasRoute(tab.route::class)
                } == true
                NavigationRailItem(
                  selected = selected,
                  onClick = { navigateToTab(tab.route) },
                  icon = {
                    Icon(
                      imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                      contentDescription = tab.label,
                    )
                  },
                  label = {
                    Text(
                      text = tab.label,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  },
                  modifier = Modifier.testTag(tab.testTag),
                )
              }
            }
          }

          NavHost(
            navController = navController,
            startDestination = RouteHome,
            modifier = Modifier.weight(1f),
          ) {
            composable<RouteHome> {
              HomeScreen(
                uiState = uiState,
                strings = strings,
                onToggleVpn = onToggleVpn,
                onNavigateServers = { navigateToTab(RouteServers) },
                onNavigateStealth = { navigateToTab(RouteStealth) },
                onNavigatePro = { navigateToTab(RoutePro) },
                onWatchAdFreeTime = { act -> viewModel.watchRewardedAdForTime(act, grantProTrial = false, strings = strings) },
                onWatchAdProTrial = { act -> viewModel.watchRewardedAdForTime(act, grantProTrial = true, strings = strings) },
                onRunDiagnostics = { viewModel.runNetworkDiagnostics() },
                onSelectLanguage = { viewModel.setLanguage(it) },
                onRequestBatteryOptimization = launchBatteryOptimizationPrompt,
              )
            }
            composable<RouteServers> {
              ServersScreen(
                uiState = uiState,
                strings = strings,
                onSelectServer = { server ->
                  val ok = viewModel.selectServer(server)
                  if (ok) {
                    navigateToTab(RouteHome)
                  } else {
                    scope.launch { snackbarHostState.showSnackbar(strings.proLockedMessage) }
                    navigateToTab(RoutePro)
                  }
                },
                onAutoSelectFastest = {
                  viewModel.selectFastestAvailableServer()
                  navigateToTab(RouteHome)
                },
                onPingAll = { viewModel.pingAllServers() },
                onImportUri = { uri, onOk, onErr ->
                  viewModel.importServerUri(uri, onOk, onErr)
                },
                onDeleteCustomServer = { id -> viewModel.deleteCustomServer(id) },
              )
            }
            composable<RouteStealth> {
              StealthScreen(
                uiState = uiState,
                strings = strings,
                onSelectProtocol = { viewModel.selectProtocol(it) },
                onUpdateDpiConfig = { viewModel.updateDpiConfig(it) },
                onSelectDns = { viewModel.selectDnsProvider(it) },
                onToggleExcludedApp = { viewModel.toggleExcludedApp(it) },
              )
            }
            composable<RoutePro> {
              ProPaywallScreen(
                uiState = uiState,
                strings = strings,
                onPurchasePlan = { act, productId -> viewModel.purchaseProPlan(act, productId) },
                onRestorePurchases = { viewModel.restorePurchases() },
                onWatchAdProTrial = { act -> viewModel.watchRewardedAdForTime(act, grantProTrial = true, strings = strings) },
              )
            }
            composable<RouteSettings> {
              SettingsScreen(
                uiState = uiState,
                strings = strings,
                isUsingTestAdIds = viewModel.adManager.isUsingTestIds,
                onSetLanguage = { viewModel.setLanguage(it) },
                onSetDarkTheme = { viewModel.setDarkTheme(it) },
                onUpdateDpiConfig = { viewModel.updateDpiConfig(it) },
                onRequestBatteryOptimization = launchBatteryOptimizationPrompt,
                onOpenBatterySettings = openBatterySettings,
                onClearLogs = { viewModel.clearLogs() },
              )
            }
          }
        }
      }

      if (showBatteryOptDialog) {
        AlertDialog(
          onDismissRequest = {
            showBatteryOptDialog = false
            proceedWithVpnConnect()
          },
          icon = {
            Icon(
              imageVector = Icons.Filled.BatteryAlert,
              contentDescription = strings.batteryOptDialogTitle,
              tint = AmberPro,
            )
          },
          title = { Text(strings.batteryOptDialogTitle) },
          text = { Text(strings.batteryOptDialogMessage) },
          confirmButton = {
            Button(
              onClick = {
                showBatteryOptDialog = false
                connectAfterBatteryReturn = true
                launchBatteryOptimizationPrompt()
              },
              colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
              modifier = Modifier.testTag("battery_opt_dialog_allow_button"),
            ) {
              Text(strings.batteryOptAllowButton)
            }
          },
          dismissButton = {
            TextButton(
              onClick = {
                showBatteryOptDialog = false
                proceedWithVpnConnect()
              },
              modifier = Modifier.testTag("battery_opt_dialog_later_button"),
            ) {
              Text(strings.batteryOptLaterButton)
            }
          },
        )
      }
    }
  }
}
