package com.yusufulgen.cuzdan

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.PriceAlertCondition
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.util.MarketStatusUtils
import com.yusufulgen.cuzdan.util.NotificationHelper
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.yusufulgen.cuzdan.util.formatCurrency
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject

/**
 * Foreground Service — Arka planda fiyat senkronizasyonu ve alarm kontrolü yapar.
 *
 * Polling aralıkları:
 *  - BIST:   Hafta içi 09:30–18:30 → her 5 saniye
 *  - Kripto: 7/24 → her 30 saniye
 *  - Döviz/Emtia: Hafta içi → her 1 dakika
 *  - Fon: Günde bir kez saat 21:00
 *  - Price Alert kontrolü: her 1 dakika
 *
 * Android, Foreground Service'i öldürmek zorunda değildir (kullanıcı durdurmazsa).
 * Kalıcı bildirim zorunludur — LOW importance kanal kullanılır (sessiz).
 */
@AndroidEntryPoint
class PriceSyncService : Service() {

    @Inject
    lateinit var repository: AssetRepository

    @Inject
    lateinit var prefManager: PreferenceManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bistJob: Job? = null
    private var cryptoJob: Job? = null
    private var fundJob: Job? = null
    private var forexJob: Job? = null
    private var alertJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PriceSyncService created")
        // Foreground Service başlatılır — kalıcı bildirim zorunlu
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SYNC_NOTIFICATION_ID,
            NotificationHelper.buildSyncNotification(this),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PriceSyncService started — launching polling coroutines")
        startAllPolling()
        // START_STICKY: sistem servisi öldürürse otomatik yeniden başlatır
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "PriceSyncService destroyed — cancelling coroutines")
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Polling Başlatıcılar ─────────────────────────────────────────────────

    private fun startAllPolling() {
        startBistPolling()
        startCryptoPolling()
        startFundPolling()
        startForexCommodityPolling()
        startAlertChecking()
    }

    /** BIST: Hafta içi 09:30–18:30, her 5 saniye */
    private fun startBistPolling() {
        if (bistJob?.isActive == true) return
        bistJob = serviceScope.launch {
            while (isActive) {
                if (isWeekday() && isBetweenTimes(9, 30, 18, 30)) {
                    runCatching { repository.refreshYahooPrices().collect { } }
                        .onFailure { Log.e(TAG, "BIST polling error: ${it.message}") }
                    delay(5_000L)
                } else {
                    delay(60_000L) // Piyasa kapalı — 60sn sonra tekrar kontrol
                }
            }
        }
    }

    /** Kripto: 7/24, her 30 saniye */
    private fun startCryptoPolling() {
        if (cryptoJob?.isActive == true) return
        cryptoJob = serviceScope.launch {
            while (isActive) {
                runCatching { repository.refreshCryptoPrices().collect { } }
                    .onFailure { Log.e(TAG, "Crypto polling error: ${it.message}") }
                delay(30_000L)
            }
        }
    }

    /** Fon: Günde bir kez 21:00'da */
    private fun startFundPolling() {
        if (fundJob?.isActive == true) return
        fundJob = serviceScope.launch {
            while (isActive) {
                delay(msUntilNextRefresh(21, 0))
                runCatching {
                    repository.refreshOwnedFundPrices().collect { }
                    repository.refreshMarketAssets(AssetType.FON).collect { }
                }.onFailure { Log.e(TAG, "Fund polling error: ${it.message}") }
            }
        }
    }

    /** Döviz + Emtia: Hafta içi, her 1 dakika */
    private fun startForexCommodityPolling() {
        if (forexJob?.isActive == true) return
        forexJob = serviceScope.launch {
            while (isActive) {
                if (isWeekday()) {
                    runCatching {
                        repository.refreshMarketAssets(AssetType.DOVIZ).collect { }
                        repository.refreshMarketAssets(AssetType.EMTIA).collect { }
                    }.onFailure { Log.e(TAG, "Forex/Commodity polling error: ${it.message}") }
                    delay(60_000L)
                } else {
                    delay(3_600_000L) // Hafta sonu — 1 saat sonra tekrar kontrol
                }
            }
        }
    }

    /** Fiyat alarm kontrolü: her 1 dakika */
    private fun startAlertChecking() {
        if (alertJob?.isActive == true) return
        alertJob = serviceScope.launch {
            while (isActive) {
                runCatching { checkPriceAlerts() }
                    .onFailure { Log.e(TAG, "Alert check error: ${it.message}") }
                delay(60_000L)
            }
        }
    }

    private suspend fun checkPriceAlerts() {
        val activeAlerts = repository.getActivePriceAlerts()
        if (activeAlerts.isEmpty()) return
        if (!prefManager.isNotificationsEnabled()) return

        activeAlerts.forEach { alert ->
            if (!MarketStatusUtils.isMarketOpenNow(alert.assetType)) return@forEach

            val currentPrice = repository.getYahooPriceOnce(alert.symbol)
                ?: repository.getLatestPrice(alert.symbol).first()

            if (currentPrice != null) {
                val isTriggered = when (alert.condition) {
                    PriceAlertCondition.ABOVE  -> currentPrice >= alert.targetPrice
                    PriceAlertCondition.EQUALS -> currentPrice.compareTo(alert.targetPrice) == 0
                    PriceAlertCondition.BELOW  -> currentPrice <= alert.targetPrice
                }

                if (isTriggered) {
                    val displayName = if (alert.name.contains(alert.symbol, ignoreCase = true))
                        alert.name else "${alert.name} (${alert.symbol})"

                    val title = getString(com.yusufulgen.cuzdan.R.string.alert_notification_title)
                    val message = "$displayName ${alert.targetPrice.formatCurrency()} seviyesine ulaştı.\n" +
                            "${getString(com.yusufulgen.cuzdan.R.string.alert_current_price)}: ${currentPrice.formatCurrency()}"

                    NotificationHelper.showPriceAlertNotification(this, title, message, alert.id.toInt())
                    repository.markAlertAsTriggered(alert.id)
                    Log.d(TAG, "Alert triggered for ${alert.symbol} at $currentPrice")
                }
            }
        }
    }

    // ─── Yardımcı Fonksiyonlar ───────────────────────────────────────────────

    private fun isWeekday(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY
    }

    private fun isBetweenTimes(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return nowMin in (startH * 60 + startM)..(endH * 60 + endM)
    }

    private fun msUntilNextRefresh(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "PriceSyncService"
    }
}
