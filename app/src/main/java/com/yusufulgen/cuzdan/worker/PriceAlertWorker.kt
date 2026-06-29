package com.yusufulgen.cuzdan.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yusufulgen.cuzdan.data.local.entity.PriceAlertCondition
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.util.NotificationHelper
import com.yusufulgen.cuzdan.util.formatCurrency
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.math.BigDecimal

@HiltWorker
class PriceAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AssetRepository,
    private val prefManager: com.yusufulgen.cuzdan.util.PreferenceManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val activeAlerts = repository.getActivePriceAlerts()
            if (activeAlerts.isEmpty()) return Result.success()

            val notificationsEnabled = prefManager.isNotificationsEnabled()

            activeAlerts.forEach { alert ->
                // Skip if the market is closed right now
                if (!com.yusufulgen.cuzdan.util.MarketStatusUtils.isMarketOpenNow(alert.assetType)) {
                    return@forEach
                }

                // Refresh price for the asset
                // Note: Simplified logic, we assume repository has a way to get latest price once
                val currentPrice = repository.getYahooPriceOnce(alert.symbol) 
                    ?: repository.getLatestPrice(alert.symbol).first()

                if (currentPrice != null) {
                    val isTriggered = when (alert.condition) {
                        PriceAlertCondition.ABOVE -> currentPrice >= alert.targetPrice
                        PriceAlertCondition.EQUALS -> currentPrice.compareTo(alert.targetPrice) == 0
                        PriceAlertCondition.BELOW -> currentPrice <= alert.targetPrice
                    }

                    if (isTriggered) {
                        if (notificationsEnabled) {
                            val displayName = if (alert.symbol.uppercase() == "GRAM_ALTIN") {
                                alert.name
                            } else if (alert.name.contains(alert.symbol, ignoreCase = true) || alert.name.equals(alert.symbol, ignoreCase = true)) {
                                alert.name
                            } else {
                                "${alert.name} (${alert.symbol})"
                            }

                            val title = applicationContext.getString(com.yusufulgen.cuzdan.R.string.alert_notification_title)
                            val message = "$displayName ${alert.targetPrice.formatCurrency()} seviyesine ulaştı.\n${applicationContext.getString(com.yusufulgen.cuzdan.R.string.alert_current_price)}: ${currentPrice.formatCurrency()}"

                            NotificationHelper.showPriceAlertNotification(
                                applicationContext,
                                title,
                                message,
                                alert.id.toInt()
                            )
                        }
                        repository.markAlertAsTriggered(alert.id)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
