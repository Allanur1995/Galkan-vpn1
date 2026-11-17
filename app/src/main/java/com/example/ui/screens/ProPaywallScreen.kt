package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.VpnUiState
import com.example.data.DefaultServers
import com.example.model.ProPlan
import com.example.ui.AppStrings
import com.example.ui.theme.AmberOnPro
import com.example.ui.theme.AmberPro
import com.example.ui.theme.EmeraldPrimary

@Composable
fun ProPaywallScreen(
  uiState: VpnUiState,
  strings: AppStrings,
  onPurchasePlan: (Activity, String) -> Unit,
  onRestorePurchases: () -> Unit,
  onWatchAdProTrial: (Activity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val activity = LocalContext.current as? Activity
  var selectedPlanId by remember { mutableStateOf(DefaultServers.proPlans[1].productId) }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 620.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
      // Hero Banner
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(176.dp)
          .clip(RoundedCornerShape(22.dp))
          .border(1.5.dp, AmberPro.copy(alpha = 0.55f), RoundedCornerShape(22.dp)),
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_shield_hero_1794925557648),
          contentDescription = strings.proTitle,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.25f), Color(0xFF070B14).copy(alpha = 0.94f))
              )
            )
            .padding(18.dp),
          contentAlignment = Alignment.BottomStart,
        ) {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = "Pro Crown",
                tint = AmberPro,
                modifier = Modifier.size(26.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = strings.proTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = strings.proSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFE2E8F0),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      if (uiState.prefs.isProActive || uiState.billingStatus.hasActivePurchase) {
        Surface(
          color = EmeraldPrimary.copy(alpha = 0.14f),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Verified, contentDescription = null, tint = EmeraldPrimary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = strings.proAlreadyActive,
              style = MaterialTheme.typography.titleSmall,
              color = EmeraldPrimary,
            )
          }
        }
        Spacer(modifier = Modifier.height(16.dp))
      }

      // Features Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          ProFeatureRow(strings.proFeature1)
          ProFeatureRow(strings.proFeature2)
          ProFeatureRow(strings.proFeature3)
          ProFeatureRow(strings.proFeature4)
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Plans
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DefaultServers.proPlans.forEach { plan ->
          val title = when (plan.productId) {
            "galkan_pro_monthly" -> strings.planMonthly
            "galkan_pro_yearly" -> strings.planYearly
            else -> strings.planLifetime
          }
          val livePrice = uiState.billingStatus.productPrices[plan.productId] ?: plan.fallbackPrice
          PlanCard(
            plan = plan,
            title = title,
            price = livePrice,
            isSelected = selectedPlanId == plan.productId,
            onClick = { selectedPlanId = plan.productId },
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Button(
        onClick = { activity?.let { onPurchasePlan(it, selectedPlanId) } },
        enabled = activity != null,
        colors = ButtonDefaults.buttonColors(
          containerColor = AmberPro,
          contentColor = AmberOnPro,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(54.dp)
          .testTag("purchase_pro_button"),
      ) {
        Icon(Icons.Filled.WorkspacePremium, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = strings.subscribeNow,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedButton(
        onClick = { activity?.let { onWatchAdProTrial(it) } },
        enabled = activity != null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("paywall_watch_ad_trial_button"),
      ) {
        Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = EmeraldPrimary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.watchAdProTrialButton)
      }

      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
      ) {
        TextButton(
          onClick = onRestorePurchases,
          modifier = Modifier.testTag("restore_purchases_button"),
        ) {
          Text(strings.restorePurchases)
        }
      }

      Text(
        text = uiState.billingStatus.lastMessage ?: strings.playBillingNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}

@Composable
private fun ProFeatureRow(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      imageVector = Icons.Filled.CheckCircle,
      contentDescription = null,
      tint = EmeraldPrimary,
      modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun PlanCard(
  plan: ProPlan,
  title: String,
  price: String,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val borderColor = if (isSelected) AmberPro else MaterialTheme.colorScheme.outline
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        AmberPro.copy(alpha = 0.12f)
      } else {
        MaterialTheme.colorScheme.surface
      }
    ),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .testTag("pro_plan_${plan.productId}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (plan.badge != null) {
          Spacer(modifier = Modifier.width(8.dp))
          Surface(
            color = if (plan.isBestValue) AmberPro else EmeraldPrimary.copy(alpha = 0.2f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = plan.badge,
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
              color = if (plan.isBestValue) AmberOnPro else EmeraldPrimary,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }
      }

      Text(
        text = price,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = if (isSelected) AmberPro else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
