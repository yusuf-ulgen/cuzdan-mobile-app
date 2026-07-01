package com.yusufulgen.cuzdan.util

import android.util.Log
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Varlık türüne göre farklı zaman aralıklarında API yenileme yapar:
 *
 * - BIST (Borsa):  Hafta içi 09:30-18:30 arası her 5 saniyede bir
 * - Kripto:         7/24 her 30 saniyede bir
 * - Fon:            Günde bir kez saat 21:00'da
 * - Döviz:          Hafta içi 00:00-23:59 her 1 dakikada bir
 * - Emtia:          Hafta içi 00:00-23:59 her 1 dakikada bir
 */
@Singleton
class PriceSyncManager @Inject constructor(
    private val repository: AssetRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bistJob: Job? = null
    private var cryptoJob: Job? = null
    private var fundJob: Job? = null
    private var forexCommodityJob: Job? = null

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    data class SyncStatus(
        val lastUpdate: Long = 0,
        val isOffline: Boolean = false
    )

    fun startPolling() {
        // Uygulama açılır açılmaz anlık sync yap (Paramla yaklaşımı: Open = Fresh)
        scope.launch {
            initialSync()
        }
        startBistPolling()
        startCryptoPolling()
        startFundPolling()
        startForexCommodityPolling()
    }

    /**
     * Uygulama açılınca hemen çalışır — polling döngüleri başlamadan önce
     * kripto ve hisse fiyatlarını paralel olarak çeker.
     * Room Flow'ları otomatik güncellendiğinden UI anında yenilenir.
     */
    private suspend fun initialSync() {
        try {
            kotlinx.coroutines.coroutineScope {
                launch { repository.refreshCryptoPrices().collect { } }
                launch { repository.refreshYahooPrices().collect { } }
            }
            _syncStatus.value = SyncStatus(lastUpdate = System.currentTimeMillis(), isOffline = false)
            Log.d("PriceSyncManager", "Initial sync completed")
        } catch (e: Exception) {
            _syncStatus.value = _syncStatus.value.copy(isOffline = true)
            Log.e("PriceSyncManager", "Initial sync error: ${e.message}")
        }
    }

    fun stopPolling() {
        Log.d("PriceSyncManager", "Stopping all polling jobs")
        bistJob?.cancel()
        cryptoJob?.cancel()
        fundJob?.cancel()
        forexCommodityJob?.cancel()
        bistJob = null
        cryptoJob = null
        fundJob = null
        forexCommodityJob = null
    }

    // ─── BIST: Hafta içi 09:30-18:30, her 5 saniye ────────────────────────────
    private fun startBistPolling() {
        if (bistJob?.isActive == true) return
        bistJob = scope.launch {
            while (isActive) {
                if (isWeekday() && isBetweenTimes(9, 30, 18, 30)) {
                    try {
                        repository.refreshYahooPrices().collect { }
                        Log.d("PriceSyncManager", "BIST refresh completed")
                        _syncStatus.value = SyncStatus(lastUpdate = System.currentTimeMillis(), isOffline = false)
                    } catch (e: Exception) {
                        _syncStatus.value = _syncStatus.value.copy(isOffline = true)
                        Log.e("PriceSyncManager", "BIST polling error: ${e.message}")
                    }
                    delay(5_000L) // 5 saniye
                } else {
                    // Piyasa kapalı – 60 sn sonra tekrar kontrol et
                    delay(60_000L)
                }
            }
        }
    }

    // ─── Kripto: 7/24, her 30 saniye ─────────────────────────────────────────
    private fun startCryptoPolling() {
        if (cryptoJob?.isActive == true) return
        cryptoJob = scope.launch {
            while (isActive) {
                try {
                    repository.refreshCryptoPrices().collect { }
                    Log.d("PriceSyncManager", "Crypto refresh completed")
                    _syncStatus.value = SyncStatus(lastUpdate = System.currentTimeMillis(), isOffline = false)
                } catch (e: Exception) {
                    _syncStatus.value = _syncStatus.value.copy(isOffline = true)
                    Log.e("PriceSyncManager", "Crypto polling error: ${e.message}")
                }
                delay(30_000L) // 30 saniye
            }
        }
    }

    // ─── Fon: Günde bir kez saat 21:00'da ────────────────────────────────────
    private fun startFundPolling() {
        if (fundJob?.isActive == true) return
        fundJob = scope.launch {
            while (isActive) {
                val waitMs = msUntilNextRefresh(21, 0)
                Log.d("PriceSyncManager", "Fund refresh in ${waitMs / 60000} min")
                delay(waitMs)
                try {
                    repository.refreshOwnedFundPrices().collect { }
                    repository.refreshMarketAssets(AssetType.FON).collect { }
                    Log.d("PriceSyncManager", "Fund refresh completed")
                    _syncStatus.value = SyncStatus(lastUpdate = System.currentTimeMillis(), isOffline = false)
                } catch (e: Exception) {
                    _syncStatus.value = _syncStatus.value.copy(isOffline = true)
                    Log.e("PriceSyncManager", "Fund polling error: ${e.message}")
                }
            }
        }
    }

    // ─── Döviz + Emtia + Portföy Geneli: Hafta içi, her 1 dakika ──────────────────────────────
    private fun startForexCommodityPolling() {
        if (forexCommodityJob?.isActive == true) return
        forexCommodityJob = scope.launch {
            while (isActive) {
                if (isWeekday()) {
                    try {
                        Log.d("PriceSyncManager", "Starting Forex/Commodity/Portfolio refresh...")
                        repository.refreshMarketAssets(AssetType.DOVIZ).collect { }
                        repository.refreshMarketAssets(AssetType.EMTIA).collect { }
                        repository.refreshYahooPrices().collect { } // Portföydeki varlıkları günceller
                        
                        Log.d("PriceSyncManager", "Forex/Commodity/Portfolio refresh completed")
                        _syncStatus.value = SyncStatus(lastUpdate = System.currentTimeMillis(), isOffline = false)
                    } catch (e: Exception) {
                        _syncStatus.value = _syncStatus.value.copy(isOffline = true)
                        Log.e("PriceSyncManager", "Forex/Commodity polling error: ${e.message}")
                    }
                    delay(60_000L) // 1 dakika
                } else {
                    // Hafta sonu – 1 saat sonra tekrar kontrol et
                    delay(3_600_000L)
                }
            }
        }
    }

    // ─── Yardımcı Fonksiyonlar ────────────────────────────────────────────────

    /** Bugün pazartesi-cuma ise true döner */
    private fun isWeekday(): Boolean {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY
    }

    /** Şu anki saat [startH:startM, endH:endM] aralığında mı? */
    private fun isBetweenTimes(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startH * 60 + startM
        val endMinutes = endH * 60 + endM
        return nowMinutes in startMinutes..endMinutes
    }

    /**
     * Verilen saate (hour:minute) kaç ms kaldığını döner.
     * Eğer saat geçmişse yarına ertelenmiş olarak hesaplanır.
     */
    private fun msUntilNextRefresh(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Eğer hedef saat geçmişse, yarın aynı saate planla
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }
}
