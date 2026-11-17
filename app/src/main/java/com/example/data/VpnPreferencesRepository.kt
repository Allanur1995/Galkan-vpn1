package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.DpiBypassConfig
import com.example.model.VpnServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "galkan_vpn_prefs")

data class UserPreferencesSnapshot(
  val selectedServerId: String = DefaultServers.curatedServers.first().id,
  val customServers: List<VpnServer> = emptyList(),
  val dpiConfig: DpiBypassConfig = DpiBypassConfig(),
  val excludedPackages: Set<String> = emptySet(),
  val language: AppLanguage = AppLanguage.TURKMEN,
  val isDarkTheme: Boolean = true,
  val adUnlockExpiresAtMs: Long = 0L,
  val proTrialExpiresAtMs: Long = 0L,
  val isProActive: Boolean = false,
  val activePlanId: String? = null,
)

class VpnPreferencesRepository(private val context: Context) {

  private val json = Json { ignoreUnknownKeys = true }

  private object Keys {
    val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
    val CUSTOM_SERVERS_JSON = stringPreferencesKey("custom_servers_json")
    val DPI_CONFIG_JSON = stringPreferencesKey("dpi_config_json")
    val EXCLUDED_PACKAGES = stringSetPreferencesKey("excluded_packages")
    val LANGUAGE_CODE = stringPreferencesKey("language_code")
    val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
    val AD_UNLOCK_EXPIRES_AT = longPreferencesKey("ad_unlock_expires_at")
    val PRO_TRIAL_EXPIRES_AT = longPreferencesKey("pro_trial_expires_at")
    val IS_PRO_ACTIVE = booleanPreferencesKey("is_pro_active")
    val ACTIVE_PLAN_ID = stringPreferencesKey("active_plan_id")
  }

  val preferencesFlow: Flow<UserPreferencesSnapshot> = context.dataStore.data.map { prefs ->
    val customServers = prefs[Keys.CUSTOM_SERVERS_JSON]?.let { raw ->
      runCatching { json.decodeFromString<List<VpnServer>>(raw) }.getOrDefault(emptyList())
    } ?: emptyList()

    val dpiConfig = prefs[Keys.DPI_CONFIG_JSON]?.let { raw ->
      runCatching { json.decodeFromString<DpiBypassConfig>(raw) }.getOrDefault(DpiBypassConfig())
    } ?: DpiBypassConfig()

    val langCode = prefs[Keys.LANGUAGE_CODE] ?: AppLanguage.TURKMEN.code
    val language = AppLanguage.entries.find { it.code == langCode } ?: AppLanguage.TURKMEN

    UserPreferencesSnapshot(
      selectedServerId = prefs[Keys.SELECTED_SERVER_ID] ?: DefaultServers.curatedServers.first().id,
      customServers = customServers,
      dpiConfig = dpiConfig,
      excludedPackages = prefs[Keys.EXCLUDED_PACKAGES] ?: emptySet(),
      language = language,
      isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true,
      adUnlockExpiresAtMs = prefs[Keys.AD_UNLOCK_EXPIRES_AT] ?: 0L,
      proTrialExpiresAtMs = prefs[Keys.PRO_TRIAL_EXPIRES_AT] ?: 0L,
      isProActive = prefs[Keys.IS_PRO_ACTIVE] ?: false,
      activePlanId = prefs[Keys.ACTIVE_PLAN_ID],
    )
  }

  suspend fun selectServer(serverId: String) {
    context.dataStore.edit { it[Keys.SELECTED_SERVER_ID] = serverId }
  }

  suspend fun addCustomServer(server: VpnServer) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.CUSTOM_SERVERS_JSON]?.let { raw ->
        runCatching { json.decodeFromString<List<VpnServer>>(raw) }.getOrDefault(emptyList())
      } ?: emptyList()
      val updated = listOf(server) + current.filterNot { it.id == server.id }
      prefs[Keys.CUSTOM_SERVERS_JSON] = json.encodeToString(updated)
      prefs[Keys.SELECTED_SERVER_ID] = server.id
    }
  }

  suspend fun removeCustomServer(serverId: String) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.CUSTOM_SERVERS_JSON]?.let { raw ->
        runCatching { json.decodeFromString<List<VpnServer>>(raw) }.getOrDefault(emptyList())
      } ?: emptyList()
      val updated = current.filterNot { it.id == serverId }
      prefs[Keys.CUSTOM_SERVERS_JSON] = json.encodeToString(updated)
      if (prefs[Keys.SELECTED_SERVER_ID] == serverId) {
        prefs[Keys.SELECTED_SERVER_ID] = DefaultServers.curatedServers.first().id
      }
    }
  }

  suspend fun updateDpiConfig(config: DpiBypassConfig) {
    context.dataStore.edit { prefs ->
      prefs[Keys.DPI_CONFIG_JSON] = json.encodeToString(config)
    }
  }

  suspend fun toggleExcludedPackage(packageName: String) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.EXCLUDED_PACKAGES] ?: emptySet()
      prefs[Keys.EXCLUDED_PACKAGES] = if (packageName in current) {
        current - packageName
      } else {
        current + packageName
      }
    }
  }

  suspend fun setLanguage(language: AppLanguage) {
    context.dataStore.edit { it[Keys.LANGUAGE_CODE] = language.code }
  }

  suspend fun setDarkTheme(isDark: Boolean) {
    context.dataStore.edit { it[Keys.IS_DARK_THEME] = isDark }
  }

  suspend fun grantAdUnlock(durationMillis: Long, includesProTrial: Boolean) {
    val now = System.currentTimeMillis()
    context.dataStore.edit { prefs ->
      val currentFree = prefs[Keys.AD_UNLOCK_EXPIRES_AT] ?: 0L
      val baseFree = maxOf(now, currentFree)
      prefs[Keys.AD_UNLOCK_EXPIRES_AT] = baseFree + durationMillis
      if (includesProTrial) {
        val currentPro = prefs[Keys.PRO_TRIAL_EXPIRES_AT] ?: 0L
        val basePro = maxOf(now, currentPro)
        prefs[Keys.PRO_TRIAL_EXPIRES_AT] = basePro + (30 * 60 * 1000L)
      }
    }
  }

  suspend fun updateProEntitlement(isPro: Boolean, planId: String?) {
    context.dataStore.edit { prefs ->
      prefs[Keys.IS_PRO_ACTIVE] = isPro
      if (planId != null) {
        prefs[Keys.ACTIVE_PLAN_ID] = planId
      } else {
        prefs.remove(Keys.ACTIVE_PLAN_ID)
      }
    }
  }
}
