package com.yusufulgen.cuzdan.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        createSharedPrefs(context, masterKey)
    } catch (e: Exception) {
        android.util.Log.e("PreferenceManager", "Failed to create EncryptedSharedPreferences, resetting...", e)
        // Bozuk dosyayı temizleyelim
        context.getSharedPreferences("cuzdan_secure_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        // Tekrar deneyelim
        try {
            createSharedPrefs(context, masterKey)
        } catch (e2: Exception) {
            // Hala hata varsa standart SharedPreferences'a düşelim (çökmesini engellemek için)
            context.getSharedPreferences("cuzdan_secure_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun createSharedPrefs(context: Context, masterKey: MasterKey): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "cuzdan_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun setHomeCurrency(currency: String) {
        prefs.edit().putString("home_currency", currency).apply()
    }

    fun getHomeCurrency(): String {
        return prefs.getString("home_currency", "TL") ?: "TL"
    }

    fun setReportsCurrency(currency: String) {
        prefs.edit().putString("reports_currency", currency).apply()
    }

    fun getReportsCurrency(): String {
        return prefs.getString("reports_currency", "TL") ?: "TL"
    }

    fun setCryptoCurrency(currency: String) {
        prefs.edit().putString("crypto_currency", currency).apply()
    }

    fun getCryptoCurrency(): String {
        return prefs.getString("crypto_currency", "TL") ?: "TL"
    }

    fun setEmtiaCurrency(currency: String) {
        prefs.edit().putString("emtia_currency", currency).apply()
    }

    fun getEmtiaCurrency(): String {
        return prefs.getString("emtia_currency", "TL") ?: "TL"
    }

    fun setLanguage(language: String) {
        prefs.edit().putString("language", language).apply()
    }

    fun getLanguage(): String {
        return prefs.getString("language", "tr") ?: "tr"
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometrics_enabled", enabled).apply()
    }

    fun isBiometricsEnabled(): Boolean {
        return prefs.getBoolean("biometrics_enabled", false)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun isNotificationsEnabled(): Boolean {
        return prefs.getBoolean("notifications_enabled", true)
    }

    fun setAgreementAccepted(accepted: Boolean) {
        prefs.edit().putBoolean("agreement_accepted", accepted).apply()
    }

    fun isAgreementAccepted(): Boolean {
        return prefs.getBoolean("agreement_accepted", false)
    }

    fun setPrivacyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("privacy_mode_enabled", enabled).apply()
    }

    fun isPrivacyModeEnabled(): Boolean {
        return prefs.getBoolean("privacy_mode_enabled", false)
    }

    fun setSelectedPortfolioId(id: Long) {
        prefs.edit().putLong("selected_portfolio_id", id).apply()
    }

    fun getSelectedPortfolioId(): Long {
        // -1L means "All portfolios" / no explicit selection.
        // Defaulting to -1 prevents silently attaching assets to a non-existent "first" portfolio on fresh install.
        return prefs.getLong("selected_portfolio_id", -1L)
    }

    fun setLastAuthTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_auth_timestamp", timestamp).apply()
    }

    fun getLastAuthTimestamp(): Long {
        return prefs.getLong("last_auth_timestamp", 0L)
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun getThemeMode(): String {
        return prefs.getString("theme_mode", "dark") ?: "dark"
    }

    fun resetPreferences() {
        prefs.edit().clear().apply()
    }

    fun setLastShownReleaseVersion(version: String) {
        prefs.edit().putString("last_shown_release_version", version).apply()
    }

    fun getLastShownReleaseVersion(): String {
        return prefs.getString("last_shown_release_version", "") ?: ""
    }
}
