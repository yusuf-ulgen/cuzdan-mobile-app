package com.yusufulgen.cuzdan.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yusufulgen.cuzdan.MainActivity
import com.yusufulgen.cuzdan.R

object NotificationHelper {

    // ─── Fiyat Alarm Kanalı (kullanıcıya gösterilen alert bildirimleri) ────────
    private const val CHANNEL_ID = "price_alerts_channel"
    private const val CHANNEL_NAME = "Fiyat Alarmları"
    private const val CHANNEL_DESC = "Varlık fiyatları hedef seviyeye ulaştığında bildirim gönderir"

    // ─── Arka Plan Sync Kanalı (Foreground Service için kalıcı bildirim) ───────
    const val SYNC_CHANNEL_ID = "price_sync_channel"
    private const val SYNC_CHANNEL_NAME = "Fiyat Takibi"
    private const val SYNC_CHANNEL_DESC = "Varlık fiyatlarını arka planda takip eder"
    const val SYNC_NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Fiyat alarm kanalı
            val alertChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
            }
            nm.createNotificationChannel(alertChannel)

            // Arka plan sync kanalı (LOW importance → sessiz, status bar'da görünür)
            val syncChannel = NotificationChannel(SYNC_CHANNEL_ID, SYNC_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = SYNC_CHANNEL_DESC
                setShowBadge(false)
            }
            nm.createNotificationChannel(syncChannel)
        }
    }

    /** Foreground Service için kalıcı bildirim oluşturur */
    fun buildSyncNotification(context: Context, statusText: String = "Fiyatlar takip ediliyor…"): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showPriceAlertNotification(context: Context, title: String, message: String, notificationId: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
