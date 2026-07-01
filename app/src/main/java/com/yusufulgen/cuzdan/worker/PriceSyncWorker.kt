package com.yusufulgen.cuzdan.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.data.repository.PortfolioRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect

/**
 * WorkManager güvenlik ağı — Foreground Service durduğunda devreye girer.
 * Her 15 dakikada bir çalışır, tüm fiyat güncellemelerini paralel yapar.
 */
@HiltWorker
class PriceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val assetRepository: AssetRepository,
    private val portfolioRepository: PortfolioRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Tüm refresh işlemlerini paralel çalıştır
            coroutineScope {
                val cryptoJob = async { assetRepository.refreshCryptoPrices().collect { } }
                val yahooJob  = async { assetRepository.refreshYahooPrices().collect { } }
                val fundJob   = async { assetRepository.refreshOwnedFundPrices().collect { } }
                val marketJob = async { assetRepository.refreshMarketAssets(null).collect { } }
                cryptoJob.await()
                yahooJob.await()
                fundJob.await()
                marketJob.await()
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Ağ hatası olursa backoff policy'ye göre yeniden dene
            Result.retry()
        }
    }
}
