package com.yusufulgen.cuzdan.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "market_assets", primaryKeys = ["symbol", "assetType"])
data class MarketAsset(
    val symbol: String,
    val name: String,
    val fullName: String? = null,
    val currentPrice: BigDecimal,
    val dailyChangePercentage: BigDecimal,
    val assetType: AssetType,
    val currency: String = "TRY",
    val isFavorite: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val chartDataJson: String? = null
)

