package com.yusufulgen.cuzdan.util

import com.yusufulgen.cuzdan.data.local.entity.AssetType
import java.util.Calendar

object MarketStatusUtils {

    /**
     * Sabit Türkiye Resmi Tatilleri (Ay, Gün) - Borsa İstanbul kapalı
     */
    private val turkishHolidays = listOf(
        Pair(Calendar.JANUARY, 1),   // Yılbaşı
        Pair(Calendar.APRIL, 23),    // 23 Nisan Ulusal Egemenlik ve Çocuk Bayramı
        Pair(Calendar.MAY, 1),       // 1 Mayıs Emek ve Dayanışma Günü
        Pair(Calendar.MAY, 19),      // 19 Mayıs Atatürk'ü Anma, Gençlik ve Spor Bayramı
        Pair(Calendar.JULY, 15),     // 15 Temmuz Demokrasi ve Milli Birlik Günü
        Pair(Calendar.AUGUST, 30),   // 30 Ağustos Zafer Bayramı
        Pair(Calendar.OCTOBER, 29)   // 29 Ekim Cumhuriyet Bayramı
        // Not: Dini bayramlar her yıl değiştiği için sabit listeye eklenemez, ancak
        // basitlik açısından mevcut yılların dini bayramları manuel eklenebilir veya 
        // daha gelişmiş bir takvim servisi kullanılabilir. Şimdilik sabit olanlar.
    )

    /**
     * Bugünün hafta sonu olup olmadığını kontrol eder.
     */
    fun isWeekend(): Boolean {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    }

    /**
     * Bugünün Türkiye için resmi bir tatil olup olmadığını kontrol eder.
     */
    fun isTurkishHoliday(): Boolean {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return turkishHolidays.contains(Pair(month, day))
    }

    /**
     * Belirtilen varlık türü için şu an piyasanın kapalı olup olmadığını (veya
     * tatil olup olmadığını) döndürür. Bu fonksiyon sayesinde kapalı günlerde
     * günlük değişimi %0.00 olarak gösterebiliriz.
     */
    fun isMarketClosedToday(type: AssetType): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        
        // Borsa İstanbul 00:00 - 09:55 arası dünkü değişimi göstermemeli
        val isBeforeBistOpen = hour < 9 || (hour == 9 && minute < 55)

        return when (type) {
            AssetType.BIST -> isWeekend() || isTurkishHoliday() || isBeforeBistOpen
            AssetType.DOVIZ, AssetType.EMTIA, AssetType.NAKIT -> isWeekend()
            // Kripto her zaman açık
            else -> false
        }
    }

    /**
     * Belirtilen varlık türü için şu an seansın/piyasanın açık olup olmadığını döndürür.
     */
    fun isMarketOpenNow(type: AssetType): Boolean {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        return when (type) {
            AssetType.BIST -> {
                if (isWeekend || isTurkishHoliday()) return false
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val timeInMinutes = hour * 60 + minute
                
                val openTime = 9 * 60 + 55    // 09:55
                val closeTime = 18 * 60 + 15   // 18:15
                
                timeInMinutes in openTime..closeTime
            }
            AssetType.DOVIZ, AssetType.EMTIA -> {
                // Forex / emtia hafta sonları kapalıdır, hafta içi 24 saat açıktır.
                !isWeekend
            }
            // Kripto ve Nakit her zaman açık/geçerli kabul edilir
            else -> true
        }
    }
}
