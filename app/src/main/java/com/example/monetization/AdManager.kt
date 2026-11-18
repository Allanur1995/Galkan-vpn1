package com.example.monetization

import android.app.Activity
import android.content.Context
import com.example.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AdFailureKind {
  NETWORK_ERROR,
  NO_FILL,
  CONFIG_OR_INTERNAL,
}

sealed interface RewardedAdState {
  data object Idle : RewardedAdState
  data object Loading : RewardedAdState
  data object Ready : RewardedAdState
  data class Unavailable(
    val reason: String,
    val errorCode: Int = -1,
    val kind: AdFailureKind = AdFailureKind.CONFIG_OR_INTERNAL,
  ) : RewardedAdState
}

private data class PendingRewardedShow(
  val activityRef: WeakReference<Activity>,
  val onRewardEarned: () -> Unit,
  val onNotReady: (AdFailureKind?, String) -> Unit,
)

class AdManager(private val appContext: Context) {

  companion object {
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val MAX_AUTO_RETRIES = 3
    private const val PENDING_WAIT_TIMEOUT_MS = 8500L

    fun classifyLoadError(code: Int, message: String): AdFailureKind {
      val lower = message.lowercase()
      return when {
        code == AdRequest.ERROR_CODE_NETWORK_ERROR ||
          lower.contains("network") ||
          lower.contains("timeout") ||
          lower.contains("unable to resolve host") -> AdFailureKind.NETWORK_ERROR
        code == AdRequest.ERROR_CODE_NO_FILL ||
          code == 9 ||
          lower.contains("no fill") ||
          lower.contains("no ad config") -> AdFailureKind.NO_FILL
        else -> AdFailureKind.CONFIG_OR_INTERNAL
      }
    }

    fun computeRetryDelayMs(attempt: Int): Long {
      return when (attempt.coerceAtLeast(1)) {
        1 -> 5_000L
        2 -> 12_000L
        else -> 25_000L
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val bannerUnitId: String = BuildConfig.ADMOB_BANNER_ID.ifBlank { TEST_BANNER }
  val rewardedUnitId: String = BuildConfig.ADMOB_REWARDED_ID.ifBlank { TEST_REWARDED }
  val interstitialUnitId: String = BuildConfig.ADMOB_INTERSTITIAL_ID.ifBlank { TEST_INTERSTITIAL }
  val isUsingTestIds: Boolean = rewardedUnitId == TEST_REWARDED || bannerUnitId == TEST_BANNER

  private val _rewardedState = MutableStateFlow<RewardedAdState>(RewardedAdState.Idle)
  val rewardedState: StateFlow<RewardedAdState> = _rewardedState.asStateFlow()

  private val _bannerReloadTick = MutableStateFlow(0)
  val bannerReloadTick: StateFlow<Int> = _bannerReloadTick.asStateFlow()

  private val sdkInitialized = AtomicBoolean(false)
  private val sdkInitializing = AtomicBoolean(false)

  private var rewardedAd: RewardedAd? = null
  private var interstitialAd: InterstitialAd? = null
  private var isInterstitialLoading = false
  private var lastInterstitialShownMs = 0L
  private var rewardedRetryCount = 0
  private var rewardedRetryJob: Job? = null
  private var interstitialRetryJob: Job? = null
  private var pendingShow: PendingRewardedShow? = null
  private var pendingTimeoutJob: Job? = null

  fun initialize() {
    if (sdkInitialized.get() || !sdkInitializing.compareAndSet(false, true)) return
    scope.launch {
      runCatching {
        MobileAds.initialize(appContext) {
          sdkInitializing.set(false)
          sdkInitialized.set(true)
          preloadRewardedAd()
          preloadInterstitialAd()
        }
      }.onFailure { err ->
        sdkInitializing.set(false)
        _rewardedState.value = RewardedAdState.Unavailable(
          reason = err.message ?: "AdMob init error",
          kind = AdFailureKind.CONFIG_OR_INTERNAL,
        )
      }
    }
  }

  fun onNetworkAvailable() {
    rewardedRetryCount = 0
    _bannerReloadTick.value = _bannerReloadTick.value + 1
    if (!sdkInitialized.get()) {
      initialize()
      return
    }
    if (rewardedAd == null && _rewardedState.value !is RewardedAdState.Loading) {
      preloadRewardedAd()
    }
    if (interstitialAd == null && !isInterstitialLoading) {
      preloadInterstitialAd()
    }
  }

  fun preloadRewardedAd(forceRetry: Boolean = false) {
    if (forceRetry) {
      rewardedRetryCount = 0
      rewardedRetryJob?.cancel()
    }
    if (!sdkInitialized.get()) {
      initialize()
    }
    if (_rewardedState.value == RewardedAdState.Loading || rewardedAd != null) return
    _rewardedState.value = RewardedAdState.Loading

    scope.launch {
      runCatching {
        val request = AdRequest.Builder().build()
        RewardedAd.load(
          appContext,
          rewardedUnitId,
          request,
          object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
              rewardedAd = ad
              rewardedRetryCount = 0
              rewardedRetryJob?.cancel()
              _rewardedState.value = RewardedAdState.Ready

              val pending = pendingShow
              if (pending != null) {
                pendingShow = null
                pendingTimeoutJob?.cancel()
                val act = pending.activityRef.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                  presentRewardedAd(act, ad, pending.onRewardEarned, pending.onNotReady)
                }
              }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
              rewardedAd = null
              val kind = classifyLoadError(error.code, error.message)
              _rewardedState.value = RewardedAdState.Unavailable(
                reason = error.message,
                errorCode = error.code,
                kind = kind,
              )

              val pending = pendingShow
              if (pending != null) {
                pendingShow = null
                pendingTimeoutJob?.cancel()
                pending.onNotReady(kind, error.message)
              }

              scheduleRewardedRetryIfNeeded()
            }
          },
        )
      }.onFailure { err ->
        rewardedAd = null
        val msg = err.message ?: "Rewarded load exception"
        _rewardedState.value = RewardedAdState.Unavailable(msg, kind = AdFailureKind.CONFIG_OR_INTERNAL)
        val pending = pendingShow
        if (pending != null) {
          pendingShow = null
          pendingTimeoutJob?.cancel()
          pending.onNotReady(AdFailureKind.CONFIG_OR_INTERNAL, msg)
        }
      }
    }
  }

  private fun scheduleRewardedRetryIfNeeded() {
    if (rewardedRetryCount >= MAX_AUTO_RETRIES) return
    rewardedRetryCount++
    val delayMs = computeRetryDelayMs(rewardedRetryCount)
    rewardedRetryJob?.cancel()
    rewardedRetryJob = scope.launch {
      delay(delayMs)
      if (rewardedAd == null && _rewardedState.value !is RewardedAdState.Loading) {
        preloadRewardedAd()
      }
    }
  }

  fun showRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onLoadingStarted: () -> Unit = {},
    onNotReady: (AdFailureKind?, String) -> Unit,
  ) {
    val readyAd = rewardedAd
    if (readyAd != null) {
      pendingShow = null
      pendingTimeoutJob?.cancel()
      presentRewardedAd(activity, readyAd, onRewardEarned, onNotReady)
      return
    }

    // Queue immediate auto-present when load finishes instead of failing instantly
    pendingShow = PendingRewardedShow(
      activityRef = WeakReference(activity),
      onRewardEarned = onRewardEarned,
      onNotReady = onNotReady,
    )
    pendingTimeoutJob?.cancel()
    pendingTimeoutJob = scope.launch {
      delay(PENDING_WAIT_TIMEOUT_MS)
      val stillPending = pendingShow
      if (stillPending != null) {
        pendingShow = null
        val cur = _rewardedState.value
        if (cur is RewardedAdState.Unavailable) {
          stillPending.onNotReady(cur.kind, cur.reason)
        } else {
          stillPending.onNotReady(AdFailureKind.NETWORK_ERROR, "Timed out waiting for ad load")
        }
      }
    }

    onLoadingStarted()
    preloadRewardedAd(forceRetry = _rewardedState.value is RewardedAdState.Unavailable)
  }

  private fun presentRewardedAd(
    activity: Activity,
    ad: RewardedAd,
    onRewardEarned: () -> Unit,
    onNotReady: (AdFailureKind?, String) -> Unit,
  ) {
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
        _rewardedState.value = RewardedAdState.Unavailable(
          reason = error.message,
          errorCode = error.code,
          kind = AdFailureKind.CONFIG_OR_INTERNAL,
        )
        preloadRewardedAd()
        onNotReady(AdFailureKind.CONFIG_OR_INTERNAL, error.message)
      }
    }
    ad.show(activity) {
      earned = true
    }
  }

  fun preloadInterstitialAd() {
    if (interstitialAd != null || isInterstitialLoading) return
    isInterstitialLoading = true
    scope.launch {
      runCatching {
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
          appContext,
          interstitialUnitId,
          request,
          object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
              isInterstitialLoading = false
              interstitialAd = ad
              interstitialRetryJob?.cancel()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
              isInterstitialLoading = false
              interstitialAd = null
              interstitialRetryJob?.cancel()
              interstitialRetryJob = scope.launch {
                delay(15_000L)
                preloadInterstitialAd()
              }
            }
          },
        )
      }.onFailure {
        isInterstitialLoading = false
      }
    }
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
        preloadInterstitialAd()
      }
    }
    ad.show(activity)
  }

  fun createBannerAdView(
    context: Context,
    onLoadChanged: (Boolean) -> Unit = {},
  ): AdView {
    return AdView(context).apply {
      setAdSize(AdSize.BANNER)
      adUnitId = bannerUnitId
      adListener = object : AdListener() {
        override fun onAdLoaded() {
          onLoadChanged(true)
        }

        override fun onAdFailedToLoad(error: LoadAdError) {
          onLoadChanged(false)
        }
      }
      loadAd(AdRequest.Builder().build())
    }
  }
}
