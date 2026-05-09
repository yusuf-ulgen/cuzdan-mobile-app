package com.yusufulgen.cuzdan.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yusufulgen.cuzdan.data.local.converter.BigDecimalConverter
import com.yusufulgen.cuzdan.data.local.dao.AssetDao
import com.yusufulgen.cuzdan.data.local.dao.PortfolioDao
import com.yusufulgen.cuzdan.data.local.entity.Asset
import com.yusufulgen.cuzdan.data.local.entity.Portfolio
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset
import com.yusufulgen.cuzdan.data.local.dao.MarketAssetDao
import com.yusufulgen.cuzdan.data.local.entity.PortfolioHistory
import com.yusufulgen.cuzdan.data.local.dao.PortfolioHistoryDao
import com.yusufulgen.cuzdan.data.local.entity.PriceAlert
import com.yusufulgen.cuzdan.data.local.dao.PriceAlertDao


@Database(
    entities = [Asset::class, Portfolio::class, MarketAsset::class, PortfolioHistory::class, PriceAlert::class],
    version = 15,
    exportSchema = false
)

@TypeConverters(BigDecimalConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun marketAssetDao(): MarketAssetDao
    abstract fun portfolioHistoryDao(): PortfolioHistoryDao
    abstract fun priceAlertDao(): PriceAlertDao
}

