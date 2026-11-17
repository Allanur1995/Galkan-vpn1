package com.example.monetization

import android.app.Activity
import android.content.Context
import com.example.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RewardedAdState {
  data object Idle : RewardedAdState
  data object Loading : RewardedAdState
  data object Ready : RewardedAdState
  data class Unavailable(val reason: String) : RewardedAdState
}

class AdManager(private val appContext: Context) {

  companion object {
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val bannerUnitId: String = BuildConfig.ADMOB_BANNER_ID.ifBlank { TEST_BANNER }
  val rewardedUnitId: String = BuildConfig.ADMOB_REWARDED_ID.ifBlank { TEST_REWARDED }
  val interstitialUnitId: String = BuildConfig.ADMOB_INTERSTITIAL_ID.ifBlank { TEST_INTERSTITIAL }
  val isUsingTestIds: Boolean = rewardedUnitId == TEST_REWARDED || bannerUnitId == TEST_BANNER

  private val _rewardedState = MutableStateFlow<RewardedAdState>(RewardedAdState.Idle)
  val rewardedState: StateFlow<RewardedAdState> = _rewardedState.asStateFlow()

  private var rewardedAd: RewardedAd? = null
  private var interstitialAd: InterstitialAd? = null
  private var initialized = false
  private var lastInterstitialShownMs = 0L

  fun initialize() {
    if (initialized) return
    initialized = true
    scope.launch {
      runCatching {
        MobileAds.initialize(appContext) {
          scope.launch(Dispatchers.Main) {
            preloadRewardedAd()
            preloadInterstitialAd()
          }
        }
      }.onFailure {
        _rewardedState.value = RewardedAdState.Unavailable(it.message ?: "AdMob init error")
      }
    }
  }

  fun preloadRewardedAd() {
    if (_rewardedState.value == RewardedAdState.Loading || rewardedAd != null) return
    _rewardedState.value = RewardedAdState.Loading
    val request = AdRequest.Builder().build()
    RewardedAd.load(
      appContext,
      rewardedUnitId,
      request,
      object : RewardedAdLoadCallback() {
        override fun onAdLoaded(ad: RewardedAd) {
          rewardedAd = ad
          _rewardedState.value = RewardedAdState.Ready
        }

        override fun onAdFailedToLoad(error: LoadAdError) {
          rewardedAd = null
          _rewardedState.value = RewardedAdState.Unavailable(error.message)
        }
      },
    )
  }

  fun showRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onNotReady: (String) -> Unit,
  ) {
    val ad = rewardedAd
    if (ad == null) {
      onNotReady("Rewarded ad is loading, please try again in a moment")
      preloadRewardedAd()
      return
    }

    var earned = false
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdDismissedFullScreenContent() {
        rewardedAd = null
        _rewardedState.value = RewardedAdState.Idle
        preloadRewardedAd()
        if (earned) {
          onRewardEarned()
        }
      }

      override fun onAdFailedToShowFullScreenContent(error: AdError) {
        rewardedAd = null
        _rewardedState.value = RewardedAdState.Unavailable(error.message)
        preloadRewardedAd()
        onNotReady(error.message)
      }
    }

    ad.show(activity) {
      earned = true
    }
  }

  fun preloadInterstitialAd() {
    if (interstitialAd != null) return
    val request = AdRequest.Builder().build()
    InterstitialAd.load(
      appContext,
      interstitialUnitId,
      request,
      object : InterstitialAdLoadCallback() {
        override fun onAdLoaded(ad: InterstitialAd) {
          interstitialAd = ad
        }

        override fun onAdFailedToLoad(error: LoadAdError) {
          interstitialAd = null
        }
      },
    )
  }

  fun maybeShowInterstitial(activity: Activity, isProUser: Boolean) {
    if (isProUser) return
    val now = System.currentTimeMillis()
    if (now - lastInterstitialShownMs < 180_000L) return
    val ad = interstitialAd ?: run {
      preloadInterstitialAd()
      return
    }
    lastInterstitialShownMs = now
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdDismissedFullScreenContent() {
        interstitialAd = null
        preloadInterstitialAd()
      }

      override fun onAdFailedToShowFullScreenContent(error: AdError) {
        interstitialAd = null
      }
    }
    ad.show(activity)
  }

  fun createBannerAdView(context: Context): AdView {
    return AdView(context).apply {
      setAdSize(AdSize.BANNER)
      adUnitId = bannerUnitId
      loadAd(AdRequest.Builder().build())
    }
  }
}
