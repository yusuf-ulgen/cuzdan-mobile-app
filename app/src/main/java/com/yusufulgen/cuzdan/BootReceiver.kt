package com.yusufulgen.cuzdan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yusufulgen.cuzdan.worker.PriceAlertWorker
import com.yusufulgen.cuzdan.worker.PriceSyncWorker
import java.util.concurrent.TimeUnit

/**
 * Telefon yeniden başladığında WorkManager periodic job'larını yeniden planlar.
 * AndroidManifest'te RECEIVE_BOOT_COMPLETED izni ve bu receiver'ın tanımlı olması gerekir.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d("BootReceiver", "Boot completed — rescheduling WorkManager jobs")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<PriceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        val alertWork = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            "PriceSyncWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWork
        )
        workManager.enqueueUniquePeriodicWork(
            "PriceAlertWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            alertWork
        )

        Log.d("BootReceiver", "WorkManager jobs rescheduled successfully")
    }
}
