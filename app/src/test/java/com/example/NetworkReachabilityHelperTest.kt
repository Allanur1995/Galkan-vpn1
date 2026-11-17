package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.vpn.NetworkReachabilityHelper
import com.example.vpn.NetworkTransport
import com.example.vpn.ReachabilityResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkReachabilityHelperTest {

  @Test
  fun `returns Offline when active network has no internet capability`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)
    shadowCm.setActiveNetworkInfo(null)

    val helper = NetworkReachabilityHelper(context)
    val status = helper.readCurrentNetworkStatus()
    assertFalse(status.isNetworkAvailable)
    assertEquals(NetworkTransport.NONE, status.transport)

    val result = helper.verifyInternetBeforeConnect("1.1.1.1", 443)
    assertEquals(ReachabilityResult.Offline, result)
  }

  @Test
  fun `detects WiFi transport when network capabilities indicate WiFi and Internet`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)
    val network = cm.activeNetwork
    val caps = ShadowNetworkCapabilities.newInstance()
    val shadowCaps = Shadows.shadowOf(caps)
    shadowCaps.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    shadowCm.setNetworkCapabilities(network, caps)

    val helper = NetworkReachabilityHelper(context)
    val status = helper.readCurrentNetworkStatus()
    assertTrue(status.isNetworkAvailable)
    assertTrue(status.isInternetValidated)
    assertEquals(NetworkTransport.WIFI, status.transport)
  }
}
