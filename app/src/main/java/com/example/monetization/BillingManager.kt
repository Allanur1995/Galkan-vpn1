package com.example.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BillingStatus(
  val isPlayServiceReady: Boolean = false,
  val productPrices: Map<String, String> = emptyMap(),
  val hasActivePurchase: Boolean = false,
  val activeProductId: String? = null,
  val lastMessage: String? = null,
)

class BillingManager(
  context: Context,
  private val onEntitlementChanged: suspend (isPro: Boolean, planId: String?) -> Unit,
) : PurchasesUpdatedListener {

  companion object {
    val SUBSCRIPTION_IDS = listOf("galkan_pro_monthly", "galkan_pro_yearly")
    val INAPP_IDS = listOf("galkan_pro_lifetime")
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _billingStatus = MutableStateFlow(BillingStatus())
  val billingStatus: StateFlow<BillingStatus> = _billingStatus.asStateFlow()

  private val productDetailsMap = mutableMapOf<String, ProductDetails>()

  private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
    .setListener(this)
    .enablePendingPurchases(
      PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .enablePrepaidPlans()
        .build()
    )
    .build()

  fun connect() {
    if (billingClient.isReady) {
      queryProductsAndPurchases()
      return
    }
    billingClient.startConnection(
      object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
          if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            _billingStatus.value = _billingStatus.value.copy(
              isPlayServiceReady = true,
              lastMessage = null,
            )
            queryProductsAndPurchases()
          } else {
            _billingStatus.value = _billingStatus.value.copy(
              isPlayServiceReady = false,
              lastMessage = "Google Play Billing unavailable (${result.debugMessage})",
            )
          }
        }

        override fun onBillingServiceDisconnected() {
          _billingStatus.value = _billingStatus.value.copy(isPlayServiceReady = false)
        }
      }
    )
  }

  fun queryProductsAndPurchases() {
    if (!billingClient.isReady) return

    // Query Subscriptions
    val subsList = SUBSCRIPTION_IDS.map { id ->
      QueryProductDetailsParams.Product.newBuilder()
        .setProductId(id)
        .setProductType(BillingClient.ProductType.SUBS)
        .build()
    }
    billingClient.queryProductDetailsAsync(
      QueryProductDetailsParams.newBuilder().setProductList(subsList).build()
    ) { result, detailsList ->
      if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        val updatedPrices = _billingStatus.value.productPrices.toMutableMap()
        detailsList.forEach { details ->
          productDetailsMap[details.productId] = details
          val price = details.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
            ?.formattedPrice
          if (!price.isNullOrBlank()) {
            updatedPrices[details.productId] = price
          }
        }
        _billingStatus.value = _billingStatus.value.copy(productPrices = updatedPrices)
      }
    }

    // Query Lifetime In-App Product
    val inAppList = INAPP_IDS.map { id ->
      QueryProductDetailsParams.Product.newBuilder()
        .setProductId(id)
        .setProductType(BillingClient.ProductType.INAPP)
        .build()
    }
    billingClient.queryProductDetailsAsync(
      QueryProductDetailsParams.newBuilder().setProductList(inAppList).build()
    ) { result, detailsList ->
      if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        val updatedPrices = _billingStatus.value.productPrices.toMutableMap()
        detailsList.forEach { details ->
          productDetailsMap[details.productId] = details
          val price = details.oneTimePurchaseOfferDetails?.formattedPrice
          if (!price.isNullOrBlank()) {
            updatedPrices[details.productId] = price
          }
        }
        _billingStatus.value = _billingStatus.value.copy(productPrices = updatedPrices)
      }
    }

    restorePurchases()
  }

  fun restorePurchases() {
    if (!billingClient.isReady) {
      connect()
      return
    }

    billingClient.queryPurchasesAsync(
      QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
    ) { subsResult, subsPurchases ->
      val activeSub = if (subsResult.responseCode == BillingClient.BillingResponseCode.OK) {
        subsPurchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
      } else {
        null
      }

      if (activeSub != null) {
        handlePurchase(activeSub)
        return@queryPurchasesAsync
      }

      billingClient.queryPurchasesAsync(
        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
      ) { inAppResult, inAppPurchases ->
        val activeLifetime = if (inAppResult.responseCode == BillingClient.BillingResponseCode.OK) {
          inAppPurchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        } else {
          null
        }

        if (activeLifetime != null) {
          handlePurchase(activeLifetime)
        } else {
          scope.launch {
            onEntitlementChanged(false, null)
            _billingStatus.value = _billingStatus.value.copy(
              hasActivePurchase = false,
              activeProductId = null,
            )
          }
        }
      }
    }
  }

  fun launchPurchase(activity: Activity, productId: String): Boolean {
    if (!billingClient.isReady) {
      _billingStatus.value = _billingStatus.value.copy(
        lastMessage = "Google Play Store is not connected on this device."
      )
      connect()
      return false
    }

    val details = productDetailsMap[productId]
    if (details == null) {
      _billingStatus.value = _billingStatus.value.copy(
        lastMessage = "Product '$productId' is not yet configured in Google Play Console."
      )
      queryProductsAndPurchases()
      return false
    }

    val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
      .setProductDetails(details)

    val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
    if (offerToken != null) {
      productParamsBuilder.setOfferToken(offerToken)
    }

    val flowParams = BillingFlowParams.newBuilder()
      .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
      .build()

    val launchResult = billingClient.launchBillingFlow(activity, flowParams)
    if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
      _billingStatus.value = _billingStatus.value.copy(
        lastMessage = launchResult.debugMessage.ifBlank { "Purchase launch failed" }
      )
      return false
    }
    return true
  }

  override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
    when (result.responseCode) {
      BillingClient.BillingResponseCode.OK -> {
        purchases?.forEach { handlePurchase(it) }
      }
      BillingClient.BillingResponseCode.USER_CANCELED -> {
        _billingStatus.value = _billingStatus.value.copy(lastMessage = "Purchase canceled")
      }
      else -> {
        _billingStatus.value = _billingStatus.value.copy(
          lastMessage = result.debugMessage.ifBlank { "Billing error ${result.responseCode}" }
        )
      }
    }
  }

  private fun handlePurchase(purchase: Purchase) {
    if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
    val productId = purchase.products.firstOrNull()

    if (!purchase.isAcknowledged) {
      val ackParams = AcknowledgePurchaseParams.newBuilder()
        .setPurchaseToken(purchase.purchaseToken)
        .build()
      billingClient.acknowledgePurchase(ackParams) { ackResult ->
        if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
          scope.launch {
            onEntitlementChanged(true, productId)
            _billingStatus.value = _billingStatus.value.copy(
              hasActivePurchase = true,
              activeProductId = productId,
              lastMessage = "Pro activated",
            )
          }
        }
      }
    } else {
      scope.launch {
        onEntitlementChanged(true, productId)
        _billingStatus.value = _billingStatus.value.copy(
          hasActivePurchase = true,
          activeProductId = productId,
          lastMessage = null,
        )
      }
    }
  }

  fun endConnection() {
    if (billingClient.isReady) {
      billingClient.endConnection()
    }
  }
}
