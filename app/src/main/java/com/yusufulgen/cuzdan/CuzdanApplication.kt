package com.yusufulgen.cuzdan

import android.app.Application
import android.content.Intent
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yusufulgen.cuzdan.util.NotificationHelper
import com.yusufulgen.cuzdan.worker.PriceAlertWorker
import com.yusufulgen.cuzdan.worker.PriceSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CuzdanApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        setupPeriodicWork()
        startPriceSyncService()
    }

    private fun setupPeriodicWork() {
        // Sadece internet bağlantısı yeterli — batarya kısıtı kaldırıldı
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // PriceSyncWorker: 15 dakikada bir, hata olursa 5 dk sonra tekrar dene
        val syncWork = PeriodicWorkRequestBuilder<PriceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PriceSyncWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWork
        )

        // PriceAlertWorker: 15 dakikada bir (WorkManager → güvenlik ağı)
        val alertWork = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PriceAlertWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            alertWork
        )
    }

    /**
     * Foreground Service'i başlatır.
     * Android 8+ için startForegroundService() gerekli.
     * Servis, uygulama kapansa bile çalışmaya devam eder.
     */
    private fun startPriceSyncService() {
        val serviceIntent = Intent(this, PriceSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun calculateInitialDelayToNineAM(): Long {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis - now
    }
}

