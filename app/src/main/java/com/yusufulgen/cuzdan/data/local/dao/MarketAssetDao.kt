package com.yusufulgen.cuzdan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset

@Dao
interface MarketAssetDao {
    @Query("SELECT * FROM market_assets WHERE assetType = :type ORDER BY name ASC")
    fun getMarketAssetsByType(type: AssetType): kotlinx.coroutines.flow.Flow<List<MarketAsset>>

    @Query("SELECT * FROM market_assets WHERE assetType = :type ORDER BY name ASC")
    suspend fun getMarketAssetsByTypeOnce(type: AssetType): List<MarketAsset>

    @Query("SELECT * FROM market_assets ORDER BY name ASC")
    fun getAllMarketAssetsFlow(): kotlinx.coroutines.flow.Flow<List<MarketAsset>>

    @Query("SELECT * FROM market_assets ORDER BY name ASC")
    suspend fun getAllMarketAssetsOnce(): List<MarketAsset>


    @Query("SELECT * FROM market_assets WHERE (symbol LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' OR fullName LIKE '%' || :query || '%') AND assetType = :type LIMIT 50")
    fun searchMarketAssets(query: String, type: AssetType): kotlinx.coroutines.flow.Flow<List<MarketAsset>>

    @Query("SELECT * FROM market_assets WHERE (symbol LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' OR fullName LIKE '%' || :query || '%') AND assetType = :type LIMIT 50")
    suspend fun searchMarketAssetsOnce(query: String, type: AssetType): List<MarketAsset>

    @Query("SELECT * FROM market_assets WHERE (symbol LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' OR fullName LIKE '%' || :query || '%') LIMIT 50")
    fun searchAllMarketAssets(query: String): kotlinx.coroutines.flow.Flow<List<MarketAsset>>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketAssets(assets: List<MarketAsset>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketAsset(asset: MarketAsset)

    @Query("DELETE FROM market_assets WHERE assetType = :type")
    suspend fun deleteMarketAssetsByType(type: AssetType)
    
    @Query("SELECT COUNT(*) FROM market_assets WHERE assetType = :type")
    suspend fun getCountByType(type: AssetType): Int

    @Query("SELECT * FROM market_assets WHERE symbol = :symbol AND assetType = :type LIMIT 1")
    suspend fun getMarketAssetBySymbolAndTypeOnce(symbol: String, type: AssetType): MarketAsset?

    @Query("UPDATE market_assets SET isFavorite = :isFav WHERE symbol = :symbol AND assetType = :type")
    suspend fun updateFavorite(symbol: String, type: AssetType, isFav: Boolean)

    @Query("UPDATE market_assets SET chartDataJson = :json WHERE symbol = :symbol AND assetType = :type")
    suspend fun updateChartData(symbol: String, type: AssetType, json: String)

    @Query("SELECT * FROM market_assets WHERE assetType = :type AND isFavorite = 1 ORDER BY name ASC")
    fun getFavoritesByType(type: AssetType): kotlinx.coroutines.flow.Flow<List<MarketAsset>>

    @Query("SELECT * FROM market_assets WHERE isFavorite = 1 ORDER BY name ASC")
    fun getAllFavoritesFlow(): kotlinx.coroutines.flow.Flow<List<MarketAsset>>

    @Query("DELETE FROM market_assets WHERE symbol = :symbol AND assetType = :type")
    suspend fun deleteMarketAssetBySymbolAndType(symbol: String, type: AssetType)

    @Query("DELETE FROM market_assets WHERE symbol = 'AEDTRY=X'")
    suspend fun deleteAed()

    @Query("DELETE FROM market_assets WHERE assetType = 'DOVIZ' AND (name LIKE '%Türk Lirası%' OR name LIKE '%Turkish Lira%' OR name = 'USD/TRY' OR symbol = 'TRY' OR symbol = 'TRY=X')")
    suspend fun deleteProblematicDoviz()
    
    // NOT: Bu sorgu artık kullanılmıyor - tüm BIST sembolleri zaten .IS ile bitiyor,
    // dolayısıyla 'LIKE %.IS' tüm BIST veritabanını siliyordu. Temizleme refreshBistIncrementally
    // içinde sembol bazında yapılmaktadır.
    @Query("SELECT COUNT(*) FROM market_assets WHERE 1=0")
    suspend fun cleanStaleBistSymbols(): Int

    @Query("DELETE FROM market_assets WHERE assetType = 'KRIPTO' AND symbol NOT LIKE '%USDT'")
    suspend fun deleteNonUsdtCrypto()

    @Query("SELECT * FROM market_assets WHERE symbol = :symbol LIMIT 1")
    fun getMarketAssetBySymbol(symbol: String): kotlinx.coroutines.flow.Flow<MarketAsset?>
}

