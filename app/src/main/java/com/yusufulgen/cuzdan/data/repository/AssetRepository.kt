package com.yusufulgen.cuzdan.data.repository

import android.util.Log
import com.google.gson.Gson
import com.yusufulgen.cuzdan.data.local.dao.AssetDao
import com.yusufulgen.cuzdan.data.local.dao.MarketAssetDao
import com.yusufulgen.cuzdan.data.local.dao.PortfolioDao
import com.yusufulgen.cuzdan.data.local.dao.PortfolioHistoryDao
import com.yusufulgen.cuzdan.data.local.dao.PriceAlertDao
import com.yusufulgen.cuzdan.data.local.entity.Asset
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset
import com.yusufulgen.cuzdan.data.local.entity.PortfolioHistory
import com.yusufulgen.cuzdan.data.local.entity.PriceAlert
import com.yusufulgen.cuzdan.data.remote.api.BinanceApi
import com.yusufulgen.cuzdan.data.remote.api.TefasApi
import com.yusufulgen.cuzdan.data.remote.api.YahooFinanceApi
import com.yusufulgen.cuzdan.data.remote.model.TefasNewHistoryResponse
import com.yusufulgen.cuzdan.data.remote.model.TefasNewWrapper
import com.yusufulgen.cuzdan.data.remote.model.TefasNewRequest
import com.yusufulgen.cuzdan.util.Resource
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class AssetRepository
@Inject
constructor(
        private val assetDao: AssetDao,
        private val marketAssetDao: MarketAssetDao,
        private val portfolioHistoryDao: PortfolioHistoryDao,
        private val binanceApi: BinanceApi,
        private val yahooFinanceApi: YahooFinanceApi,
        private val tefasApi: TefasApi,
        private val portfolioDao: PortfolioDao,
        private val priceAlertDao: PriceAlertDao
) {
    // Memory cache for historical data to prevent rate limiting
    private data class CachedHistory(
        val data: List<Pair<Long, Double>>,
        val timestamp: Long
    )
    private val historyCache = mutableMapOf<String, CachedHistory>()
    private val CACHE_EXPIRATION_MS = 10 * 1000 // 10 seconds cache (User request)

    private var bistJob: Job? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    
    init {
        repositoryScope.launch {
            try {
                // Initial cleanup of spurious data
                marketAssetDao.deleteProblematicDoviz()
                
                // Also cleanup Asset table (user's portfolio)
                val assets = assetDao.getAllAssets().first()
                val spuriousAssets = assets.filter { 
                    val isBadName = it.name.contains("Türk Lira", ignoreCase = true) || 
                                   it.name.contains("Turkish Lira", ignoreCase = true) ||
                                   it.name == "USD/TRY"
                    isBadName && it.assetType != AssetType.NAKIT
                }
                
                if (spuriousAssets.isNotEmpty()) {
                    spuriousAssets.forEach { asset ->
                        if (asset.amount.compareTo(java.math.BigDecimal.ZERO) == 0) {
                            assetDao.deleteAsset(asset)
                        } else {
                            // If user has balance, move it to NAKIT category
                            assetDao.updateAsset(asset.copy(assetType = AssetType.NAKIT))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AssetRepo", "Cleanup failed: ${e.message}")
            }
        }
    }

    /** Tüm kripto varlıkları Flow olarak döner. */
    fun getCryptoAssets(): Flow<List<Asset>> {
        return assetDao.getAssetsByTypes(listOf(AssetType.KRIPTO))
    }

    /** Fon varlıklarını döner. */
    fun getFundAssets(): Flow<List<Asset>> {
        return assetDao.getAssetsByTypes(listOf(AssetType.FON))
    }

    /** BIST, Döviz ve Altın varlıklarını döner. */
    fun getOtherAssets(): Flow<List<Asset>> {
        return assetDao.getAssetsByTypes(listOf(AssetType.BIST, AssetType.DOVIZ, AssetType.EMTIA))
    }

    /** Belirli bir portföye ait varlıkları döner. */
    fun getAssetsByPortfolioId(portfolioId: Long): Flow<List<Asset>> {
        return assetDao.getAssetsByPortfolioId(portfolioId)
    }

    /** Tüm varlıkları döner. */
    fun getAllAssets(): Flow<List<Asset>> {
        return assetDao.getAllAssets()
    }

    /** Piyasa verilerini DB'den Flow olarak döner. */
    fun getMarketAssetsFlow(type: AssetType?): Flow<List<MarketAsset>> {
        return if (type == null) {
            marketAssetDao.getAllMarketAssetsFlow()
        } else {
            marketAssetDao.getMarketAssetsByType(type)
        }
    }

    /** Sembole göre tüm piyasa verisini Flow olarak döner. */
    fun getMarketAssetBySymbolFlow(symbol: String): Flow<MarketAsset?> {
        return marketAssetDao.getMarketAssetBySymbol(symbol)
    }

    /** Sembole göre en güncel fiyatı DB'den Flow olarak döner. */
    fun getLatestPrice(symbol: String): Flow<BigDecimal?> {
        return marketAssetDao.getMarketAssetBySymbol(symbol).map { it?.currentPrice }
    }

    /** Yahoo Finance API'den tek bir sembolün fiyatını döner. */
    suspend fun getYahooPriceOnce(symbol: String): BigDecimal? {
        return try {
            val response = yahooFinanceApi.getQuotes(symbol)
            val quote = response.quoteResponse.result?.firstOrNull()
            quote?.regularMarketPrice
        } catch (e: Exception) {
            null
        }
    }

    suspend fun refreshCryptoPrices(): Flow<Resource<Unit>> = flow {
        emit(Resource.Loading())
        try {
            val tickers = binanceApi.getAllTickers()
            val currentAssets = getCryptoAssets().first()

            val updatedAssets = mutableListOf<Asset>()
            currentAssets.forEach { asset ->
                val ticker = tickers.find { it.symbol == asset.symbol }
                ticker?.let {
                    updatedAssets.add(
                            asset.copy(
                                    currentPrice = BigDecimal(it.lastPrice),
                                    dailyChangePercentage = BigDecimal(it.priceChangePercent),
                                    currency = "USD"
                            )
                    )
                }
            }
            if (updatedAssets.isNotEmpty()) {
                assetDao.updateAssets(updatedAssets)
            }
            emit(Resource.Success(Unit))
        } catch (e: Exception) {
            e.printStackTrace()
            emit(Resource.Error(e.message ?: "Kripto fiyatları güncellenemedi"))
        }
    }

    private fun toYahooSymbol(symbol: String, type: AssetType): String {
        val clean = symbol.uppercase()
        return when (type) {
            AssetType.BIST -> if (clean.endsWith(".IS")) clean else "$clean.IS"
            AssetType.DOVIZ -> {
                if (clean == "USD" || clean == "USDTRY") "USDTRY=X"
                else if (clean == "EUR" || clean == "EURTRY") "EURTRY=X"
                else if (clean == "TRY" || clean == "TL") "USDTRY=X" // TRY itself isn't a pair, but maybe they mean USD/TRY
                else if (clean.contains("TRY=X")) clean 
                else "${clean}TRY=X"
            }
            AssetType.EMTIA ->
                    when (clean) {
                        "GOLD", "ONS" -> "GC=F"
                        "SILVER", "GUMUS" -> "SI=F"
                        "PLATINUM", "PLATIN" -> "PL=F"
                        "PALLADIUM", "PALADYUM" -> "PA=F"
                        "COPPER", "BAKIR" -> "HG=F"
                        "GAS", "DOGALGAZ" -> "NG=F"
                        "OIL", "PETROL" -> "CL=F"
                        "BRENT" -> "BZ=F"
                        else -> symbol
                    }
            else -> symbol
        }
    }

    /**
     * Yahoo Finance API'den BIST, Döviz ve Altın fiyatlarını günceller. v7/quote endpoint'i 401
     * hatası verdiği için v8/chart endpoint'ine geçildi.
     */
    suspend fun refreshYahooPrices(): Flow<Resource<Unit>> = flow {
        val TAG = "CUZDAN_LOG"
        emit(Resource.Loading())
        try {
            val currentOtherAssets = getOtherAssets().first()
            if (currentOtherAssets.isEmpty()) {
                emit(Resource.Success(Unit))
                return@flow
            }

            // 1. Prepare symbols for batch request
            val symbolMap = currentOtherAssets.filter { it.symbol != "GRAM_ALTIN" }.associate {
                it.symbol to toYahooSymbol(it.symbol, it.assetType)
            }
            
            val symbolsToFetch = symbolMap.values.toMutableList()
            if (currentOtherAssets.any { it.symbol == "GRAM_ALTIN" }) {
                if (!symbolsToFetch.contains("GC=F")) symbolsToFetch.add("GC=F")
                if (!symbolsToFetch.contains("USDTRY=X")) symbolsToFetch.add("USDTRY=X")
            }
            
            val batchQuery = symbolsToFetch.distinct().joinToString(",")
            Log.d(TAG, ">>> Batch Refreshing Yahoo Prices: $batchQuery")

            // 2. Single batch request instead of parallel chart calls
            val quoteResponse = try {
                yahooFinanceApi.getQuotes(batchQuery)
            } catch (e: Exception) {
                Log.e(TAG, "Batch quote request failed: ${e.message}")
                null
            }

            val results = quoteResponse?.quoteResponse?.result ?: emptyList()
            val marketAssetsResultsMap = results.associateBy { it.symbol }

            Log.d(TAG, "Received ${results.size} batch results.")

            var onsPrice: BigDecimal? = null
            var usdTryPrice: BigDecimal? = null
            var onsChange: BigDecimal = BigDecimal.ZERO
            var usdTryChange: BigDecimal = BigDecimal.ZERO

            val updatedAssets = mutableListOf<Asset>()
            currentOtherAssets.forEach { asset ->
                if (asset.symbol == "GRAM_ALTIN") return@forEach
                val yahooSymbol = symbolMap[asset.symbol]
                val q = marketAssetsResultsMap[yahooSymbol]

                if (q != null) {
                    val current = q.regularMarketPrice ?: BigDecimal.ZERO
                    val change = q.regularMarketChangePercent ?: BigDecimal.ZERO

                    if (yahooSymbol == "GC=F") {
                        onsPrice = current
                        onsChange = change
                    }
                    if (yahooSymbol == "USDTRY=X") {
                        usdTryPrice = current
                        usdTryChange = change
                    }

                    updatedAssets.add(
                        asset.copy(
                            currentPrice = current,
                            dailyChangePercentage = change,
                            currency = q.currency ?: "USD"
                        )
                    )
                    
                    // Also update MarketAsset table for search/market screen
                    val exist = marketAssetDao.getMarketAssetBySymbolAndTypeOnce(asset.symbol, asset.assetType)
                    cleanMarketAssetNaming(
                        MarketAsset(
                            symbol = asset.symbol,
                            name = q.shortName ?: q.longName ?: asset.name,
                            fullName = q.longName ?: q.shortName ?: asset.name,
                            currentPrice = current.setScale(4, RoundingMode.HALF_UP),
                            dailyChangePercentage = change,
                            assetType = asset.assetType,
                            currency = q.currency ?: "USD",
                            isFavorite = exist?.isFavorite ?: false,
                            lastUpdated = System.currentTimeMillis(),
                            chartDataJson = exist?.chartDataJson // Preserve existing chart data
                        ),
                        asset.assetType
                    ).let { marketAssetDao.insertMarketAsset(it) }

                }
            }

            

                // 4. Calculate Gram Gold separately with accurate Change %
                // Force change to 0 on weekends/holidays - markets are closed
                val gramMarketClosed = com.yusufulgen.cuzdan.util.MarketStatusUtils.isMarketClosedToday(AssetType.EMTIA)
                if (onsPrice != null &&
                                usdTryPrice != null &&
                                onsPrice!! > BigDecimal.ZERO &&
                                usdTryPrice!! > BigDecimal.ZERO
                ) {
                    val gramGoldPrice =
                            onsPrice!!
                                    .divide(BigDecimal("31.1035"), 8, RoundingMode.HALF_UP)
                                    .multiply(usdTryPrice!!)

                    // Gram Gold Change % = ((1 + OnsChange/100) * (1 + UsdTryChange/100) - 1) * 100
                    val onsFactor =
                            BigDecimal.ONE.add(
                                    onsChange.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
                            )
                    val usdFactor =
                            BigDecimal.ONE.add(
                                    usdTryChange.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
                            )
                    val rawGramGoldChange =
                            onsFactor
                                    .multiply(usdFactor)
                                    .subtract(BigDecimal.ONE)
                                    .multiply(BigDecimal("100"))
                                    .setScale(2, RoundingMode.HALF_UP)
                    // Piyasa kapalıysa (hafta sonu / tatil) değişimi sıfırla
                    val gramGoldChange = if (gramMarketClosed) BigDecimal.ZERO else rawGramGoldChange

                    Log.d(
                            TAG,
                            "Gram Gold Calc: Ons($onsPrice) / 31.1035 * USDTRY($usdTryPrice) = $gramGoldPrice | Change: $gramGoldChange% (marketClosed=$gramMarketClosed)"
                    )

                    assetDao.getAssetsBySymbolOnce("GRAM_ALTIN").forEach { asset ->
                        updatedAssets.add(
                                asset.copy(
                                        currentPrice = gramGoldPrice,
                                        dailyChangePercentage = gramGoldChange
                                )
                        )
                    }

                    // Also update MarketAsset table for Gram Gold consistency
                    val gramMarket =
                            marketAssetDao.getMarketAssetBySymbolAndTypeOnce(
                                    "GRAM_ALTIN",
                                    AssetType.EMTIA
                            )
                    if (gramMarket != null) {
                        marketAssetDao.insertMarketAsset(
                                gramMarket.copy(
                                        currentPrice = gramGoldPrice,
                                        dailyChangePercentage = gramGoldChange,
                                        lastUpdated = System.currentTimeMillis()
                                )
                        )
                    }
                    Log.d(TAG, "Gram Gold updated in DB for all portfolios.")
                }

                if (updatedAssets.isNotEmpty()) {
                    assetDao.updateAssets(updatedAssets)
                }

            Log.d(TAG, "<<< Refresh Yahoo Prices Finished Successfully")
            emit(Resource.Success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "!!! refreshYahooPrices CRASHED: ${e.message}", e)
            emit(Resource.Error(e.message ?: "Yahoo verileri güncellenemedi"))
        }
    }

    suspend fun refreshOwnedFundPrices(): Flow<Resource<Unit>> = flow {
        emit(Resource.Loading())
        try {
            val fundAssets = getFundAssets().first()
            if (fundAssets.isEmpty()) {
                emit(Resource.Success(Unit))
                return@flow
            }

            Log.d("TEFAS_SONUC", "Refreshing ${fundAssets.size} owned funds via new API.")

            fundAssets.forEachIndexed { index, asset ->
                try {
                    // Stagger requests to avoid rate limiting
                    if (index > 0) delay(1000L)
                    
                    val response = tefasApi.getFundHistory(
                        TefasNewRequest(fonKodu = asset.symbol.uppercase())
                    )
                    
                    val results = response.resultList?.sortedByDescending { it.tarih }
                    val latest = results?.firstOrNull()
                    val previous = results?.getOrNull(1)

                    if (latest != null && latest.price != null && latest.price > 0.0) {
                        val price = BigDecimal.valueOf(latest.price)
                        val prevPrice = if (previous != null && previous.price != null) {
                            BigDecimal.valueOf(previous.price)
                        } else {
                            BigDecimal.ZERO
                        }
                        
                        val dailyChange = if (prevPrice > BigDecimal.ZERO) {
                            price.subtract(prevPrice)
                                .divide(prevPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP)
                        } else {
                            BigDecimal.ZERO
                        }

                        assetDao.updateAsset(
                            asset.copy(
                                currentPrice = price,
                                dailyChangePercentage = dailyChange,
                                currency = "TRY"
                            )
                        )
                        Log.d("TEFAS_SONUC", "Owned fund updated ${asset.symbol}: price=$price change=${dailyChange}%")
                    } else {
                        Log.w("TEFAS_SONUC", "No price data for ${asset.symbol}")
                    }
                } catch (e: Exception) {
                    Log.w("TEFAS_SONUC", "Error updating fund ${asset.symbol}: ${e.message}")
                }
            }
            emit(Resource.Success(Unit))
        } catch (e: Exception) {
            Log.e("TEFAS_SONUC", "refreshOwnedFundPrices error", e)
            emit(Resource.Error(e.message ?: "Fon fiyatları güncellenemedi"))
        }
    }


    suspend fun refreshMarketAssets(type: AssetType?): Flow<Resource<Unit>> = flow {
        emit(Resource.Loading())
        try {
            val typesToRefresh =
                    if (type == null) {
                        listOf(
                                AssetType.BIST,
                                AssetType.KRIPTO,
                                AssetType.DOVIZ,
                                AssetType.EMTIA,
                                AssetType.FON,
                                AssetType.NAKIT
                        )
                    } else listOf(type)

            coroutineScope {
                typesToRefresh.forEach { currentType ->
                    launch { refreshMarketAssetsInternal(this@flow, currentType) }
                }
            }
            emit(Resource.Success(Unit))
        } catch (e: Exception) {
            Log.e("AssetRepo", "refreshMarketAssets Error: ${e.message}")
            emit(Resource.Error(e.message ?: "Piyasa verileri güncellenemedi"))
        }
    }

    private suspend fun refreshMarketAssetsInternal(
            collector: FlowCollector<Resource<Unit>>,
            type: AssetType
    ) {
        try {
            val marketAssets = mutableListOf<MarketAsset>()
            when (type) {
                AssetType.KRIPTO -> {
                    // CRITICAL: First, delete all old TRY-based crypto pairs to clean the database
                    marketAssetDao.deleteNonUsdtCrypto()

                    val tickers = binanceApi.getAllTickers().filter { it.symbol.endsWith("USDT") }
                    val existingAssets = marketAssetDao.getMarketAssetsByTypeOnce(AssetType.KRIPTO)
                    val favoriteMap =
                            existingAssets.filter { it.isFavorite }.associateBy { it.symbol }

                    tickers.forEach { ticker ->
                        val symbol = ticker.symbol
                        marketAssets.add(
                                MarketAsset(
                                        symbol = symbol,
                                        name = symbol.replace("USDT", ""),
                                        fullName = symbol.replace("USDT", ""),
                                        currentPrice = BigDecimal(ticker.lastPrice),
                                        dailyChangePercentage =
                                                BigDecimal(ticker.priceChangePercent)
                                                        .setScale(2, RoundingMode.HALF_UP),
                                        assetType = AssetType.KRIPTO,
                                        currency = "USD", // Binance USDT prices are USD base
                                        isFavorite = favoriteMap.containsKey(symbol)
                                )
                        )
                    }
                }
                AssetType.FON -> {
                    val symbols =
                            listOf(
                                    "TTE", "IJP", "MAC", "GSP", "AFT", "KOC", "IPV", "OPI", "RPD", "TAU",
                                    "YAY", "TI1", "GMR", "TE3", "HVS", "TDF", "IKL", "NJR", "BUY", "NNF",
                                    "BGP", "KZT", "ZPE", "OJT", "IDL", "KDV", "GPA", "RTG", "OTJ", "ZPF",
                                    "YZG", "HKH", "ZHB", "AFO", "GL1", "IVY", "YAS", "IHK", "EID", "ST1",
                                    "GAY", "DBH", "YHS", "ZPC", "AES", "IPJ", "GUH", "IEY", "YTD", "YEG",
                                    "ZPF", "ZRE", "KDJ", "KRA", "OJK", "AME", "OKT", "HAY", "TUK", "TUA",
                                    "TPZ", "TUT", "TID", "TIE", "TIG", "TIV", "TKF", "TLA", "TLM", "TLE",
                                    "TMS", "TMG"
                            )
                    Log.d("TEFAS_SONUC", "Market Funds Refresh: ${symbols.size} symbols via new API.")

                    val fundAssets = coroutineScope {
                        symbols.mapIndexed { index, symbol ->
                            async {
                                try {
                                    // Use 1s stagger to prevent rate limiting
                                    if (index > 0) delay(1000L)
                                    
                                    val response = tefasApi.getFundHistory(
                                        TefasNewRequest(fonKodu = symbol)
                                    )
                                    val results = response.resultList?.sortedByDescending { it.tarih }
                                    val latest = results?.firstOrNull()
                                    val previous = results?.getOrNull(1)

                                    if (latest != null && latest.price != null && latest.price > 0.0) {
                                        val price = BigDecimal.valueOf(latest.price)
                                        val prevPrice = if (previous != null && previous.price != null) {
                                            BigDecimal.valueOf(previous.price)
                                        } else {
                                            BigDecimal.ZERO
                                        }
                                        
                                        val change = if (prevPrice > BigDecimal.ZERO) {
                                            price.subtract(prevPrice)
                                                .divide(prevPrice, 6, RoundingMode.HALF_UP)
                                                .multiply(BigDecimal("100"))
                                                .setScale(2, RoundingMode.HALF_UP)
                                        } else {
                                            BigDecimal.ZERO
                                        }
                                        
                                        Triple(symbol, latest.fundName ?: "$symbol Fonu", Pair(price, change))
                                    } else {
                                        Triple(symbol, "$symbol Fonu", Pair(BigDecimal.ZERO, BigDecimal.ZERO))
                                    }
                                } catch (e: Exception) {
                                    Log.e("TEFAS_SONUC", "Market fund error for $symbol: ${e.message}")
                                    Triple(symbol, "$symbol Fonu", Pair(BigDecimal.ZERO, BigDecimal.ZERO))
                                }
                            }
                        }.awaitAll()
                    }

                    fundAssets.forEach { (symbol, name, priceChange) ->
                        val (price, change) = priceChange
                        val existing = marketAssetDao.getMarketAssetBySymbolAndTypeOnce(symbol, AssetType.FON)
                        val finalPrice = if (price > BigDecimal.ZERO) price else (existing?.currentPrice ?: BigDecimal.ZERO)
                        val finalChange = if (price > BigDecimal.ZERO) change else (existing?.dailyChangePercentage ?: BigDecimal.ZERO)
                        val finalName = if (name.isNotBlank() && name != "$symbol Fonu") name else (existing?.name ?: "$symbol Fonu")
                        
                        marketAssets.add(
                            MarketAsset(
                                symbol = symbol,
                                name = finalName,
                                fullName = finalName,
                                currentPrice = finalPrice,
                                dailyChangePercentage = finalChange,
                                assetType = AssetType.FON,
                                currency = "TRY",
                                isFavorite = existing?.isFavorite ?: false
                            )
                        )
                    }
                }
                AssetType.NAKIT -> {
                    val cashPairs = listOf("USD", "EUR", "GBP", "CHF", "JPY")
                    marketAssets.add(
                            MarketAsset(
                                    "TRY",
                                    "Türk Lirası",
                                    "Türk Lirası",
                                    BigDecimal.ONE,
                                    BigDecimal.ZERO,
                                    AssetType.NAKIT,
                                    "TRY"
                            )
                    )
                    val results = coroutineScope {
                        cashPairs
                                .map { code ->
                                    async {
                                        try {
                                            val result =
                                                    yahooFinanceApi
                                                            .getChartData(
                                                                    "${code}TRY=X",
                                                                    range = "1d",
                                                                    interval = "1d"
                                                            )
                                                            .chart
                                                            .result
                                                            ?.firstOrNull()
                                                            ?.meta
                                            val price =
                                                    result?.regularMarketPrice ?: BigDecimal.ZERO
                                            val prev = result?.previousClose ?: BigDecimal.ZERO
                                            val change =
                                                    if (prev > BigDecimal.ZERO)
                                                            (price - prev)
                                                                    .divide(
                                                                            prev,
                                                                            4,
                                                                            RoundingMode.HALF_UP
                                                                    )
                                                                    .multiply(BigDecimal("100"))
                                                    else BigDecimal.ZERO
                                            val existing =
                                                    marketAssetDao
                                                            .getMarketAssetBySymbolAndTypeOnce(
                                                                    code,
                                                                    AssetType.NAKIT
                                                            )
                                            MarketAsset(
                                                    "${code}TRY=X",
                                                    code,
                                                    code,
                                                    price.setScale(4, RoundingMode.HALF_UP),
                                                    change.setScale(2, RoundingMode.HALF_UP),
                                                    AssetType.NAKIT,
                                                    "TRY",
                                                    existing?.isFavorite ?: false
                                            )
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                                .awaitAll()
                                .filterNotNull()
                    }
                    marketAssets.addAll(results)
                }
                AssetType.BIST, AssetType.DOVIZ, AssetType.EMTIA -> {
                    if (type == AssetType.DOVIZ) {
                        marketAssetDao.deleteAed()
                    }
                    if (type == AssetType.BIST) {
                        refreshBistIncrementally()
                        return
                    }

                    android.util.Log.d("CuzdanDebug", "refreshMarketAssets: start for type=$type")
                    val symbols =
                            when (type) {
                                AssetType.DOVIZ ->
                                        listOf(
                                                "USDTRY=X",
                                                "EURTRY=X",
                                                "GBPTRY=X",
                                                "CHFTRY=X",
                                                "JPYTRY=X",
                                                "AUDTRY=X",
                                                "CADTRY=X",
                                                "SARTRY=X",
                                                "QARTRY=X",
                                                "RUBTRY=X",
                                                "CNYTRY=X",
                                                "AZNTRY=X",
                                                "SGDTRY=X",
                                                "NOKTRY=X",
                                                "SEKTRY=X",
                                                "DKKTRY=X",
                                                "NZDTRY=X",
                                                "MXNTRY=X",
                                                "BRLTRY=X",
                                                "INRTRY=X",
                                                "KRWTRY=X",
                                                "HKDTRY=X",
                                                "PLNTRY=X",
                                                "CZKTRY=X",
                                                "HUFTRY=X",
                                                "RONTRY=X",
                                                "ILSTRY=X",
                                                "KWDTRY=X",
                                                "AEDTRY=X",
                                                "OMRTRY=X",
                                                "BHDTRY=X",
                                                "THBTRY=X",
                                                "MYRTRY=X",
                                                "IDRTRY=X",
                                                "PHPTRY=X",
                                                "EGPTRY=X",
                                                "ZARTRY=X",
                                                "MADTRY=X",
                                                "GELTRY=X",
                                                "UAHTRY=X",
                                                "BGNTRY=X",
                                                "ISKTRY=X",
                                                "KAZTRY=X",
                                                "VNDDTRY=X",
                                                "PKRTRY=X"
                                        )
                                else ->
                                        listOf(
                                                "GC=F",
                                                "SI=F",
                                                "PL=F",
                                                "PA=F",
                                                "HG=F", // Ana Metaller
                                                "ALI=F",
                                                "NI=F",
                                                "ZN=F",
                                                "PB=F",
                                                "SN=F", // Diğer Metaller
                                                "CL=F",
                                                "BZ=F",
                                                "NG=F",
                                                "RB=F",
                                                "HO=F", // Enerji
                                                "KC=F",
                                                "CC=F",
                                                "CT=F",
                                                "SB=F",
                                                "ZC=F",
                                                "ZW=F",
                                                "ZS=F", // Tarım
                                                "LBS=F" // Orman Ürünleri
                                        )
                            }
                    android.util.Log.d("CuzdanDebug", "Symbols to fetch count: ${symbols.size}")

                    val symbolsToFetch = symbols.filter { it != "GRAM_ALTIN" }

                    if (type == AssetType.DOVIZ) {
                        marketAssetDao.deleteAed()
                        marketAssetDao.deleteProblematicDoviz()
                    }

                    // Safe USDTRY fetch beforehand to calculate cross rates for DOVIZ & EMTIA
                    val usdTryPrice =
                            try {
                                val res =
                                        yahooFinanceApi
                                                .getChartData(
                                                        "USDTRY=X",
                                                        range = "1d",
                                                        interval = "1d"
                                                )
                                                .chart
                                                .result
                                                ?.firstOrNull()
                                                ?.meta
                                res?.regularMarketPrice ?: BigDecimal.ZERO
                            } catch (e: Exception) {
                                marketAssetDao.getMarketAssetBySymbolAndTypeOnce(
                                                "USDTRY=X",
                                                AssetType.DOVIZ
                                        )
                                        ?.currentPrice
                                        ?: BigDecimal.ZERO
                            }

                    // IMPORTANT: We switch ALL Doviz/Emtia to getChartData pattern because v7/quote
                    // is returning 401 Unauthorized
                    val results = mutableListOf<MarketAsset>()
                    val chunkedSymbols = symbolsToFetch.chunked(5)
                    
                    for (chunk in chunkedSymbols) {
                        val chunkResults = coroutineScope {
                            chunk.map { sym ->
                                    async {
                                        try {
                                            val fetchSymbol =
                                                    if (type == AssetType.DOVIZ) {
                                                        when (sym) {
                                                            "USDTRY=X" -> "USDTRY=X"
                                                            "EURTRY=X" -> "EURUSD=X"
                                                            "GBPTRY=X" -> "GBPUSD=X"
                                                            "AUDTRY=X" -> "AUDUSD=X"
                                                            "NZDTRY=X" -> "NZDUSD=X"
                                                            "VNDDTRY=X" -> "VND=X"
                                                            else -> {
                                                                val base = sym.replace("TRY=X", "")
                                                                "$base=X"
                                                            }
                                                        }
                                                    } else sym

                                            val response =
                                                    yahooFinanceApi.getChartData(
                                                            fetchSymbol,
                                                            range = "5d",
                                                            interval = "1d"
                                                    )
                                            val result = response.chart.result?.firstOrNull()?.meta

                                            if (result != null) {
                                                var current =
                                                        result.regularMarketPrice ?: BigDecimal.ZERO
                                                if (current.compareTo(BigDecimal.ZERO) == 0)
                                                        return@async null

                                                if (type == AssetType.DOVIZ &&
                                                                result.symbol == "TRY=X" &&
                                                                sym != "USDTRY=X"
                                                ) {
                                                    android.util.Log.d(
                                                            "CUZDAN_LOG",
                                                            "Filtered out Yahoo fallback $sym -> TRY=X"
                                                    )
                                                    return@async null
                                                }

                                                var change =
                                                        result.regularMarketChangePercent?.let {
                                                            BigDecimal.valueOf(it)
                                                        }
                                                val prev =
                                                        result.previousClose
                                                                ?: result.chartPreviousClose

                                                if (change == null &&
                                                                prev != null &&
                                                                prev.compareTo(BigDecimal.ZERO) > 0
                                                ) {
                                                    change =
                                                            (current - prev)
                                                                    .divide(
                                                                            prev,
                                                                            10,
                                                                            RoundingMode.HALF_UP
                                                                    )
                                                                    .multiply(BigDecimal("100"))
                                                }

                                                var finalChange =
                                                        change?.setScale(2, RoundingMode.HALF_UP)
                                                                ?: BigDecimal.ZERO

                                                if (type == AssetType.DOVIZ &&
                                                                sym != "USDTRY=X" &&
                                                                usdTryPrice > BigDecimal.ZERO
                                                ) {
                                                    current =
                                                            if (fetchSymbol.endsWith("USD=X")) {
                                                                current.multiply(usdTryPrice)
                                                            } else {
                                                                usdTryPrice.divide(
                                                                        current,
                                                                        8,
                                                                        RoundingMode.HALF_UP
                                                                )
                                                            }
                                                }

                                                android.util.Log.d(
                                                        "CUZDAN_LOG",
                                                        "FetchMarket($sym via $fetchSymbol) -> Price: $current, Change: $finalChange%"
                                                )

                                                val exist =
                                                        marketAssetDao
                                                                .getMarketAssetBySymbolAndTypeOnce(
                                                                        sym,
                                                                        type
                                                                )

                                                val itemCurrency =
                                                        if (type == AssetType.BIST ||
                                                                        type == AssetType.DOVIZ
                                                        )
                                                                "TRY"
                                                        else "USD"

                                                val namedAsset = cleanMarketAssetNaming(
                                                         MarketAsset(
                                                                 symbol = sym,
                                                                 name = result.shortName
                                                                                 ?: result.longName
                                                                                         ?: sym,
                                                                 fullName = result.longName
                                                                                 ?: result.shortName
                                                                                         ?: sym,
                                                                 currentPrice =
                                                                         current.setScale(
                                                                                 4,
                                                                                 RoundingMode.HALF_UP
                                                                         ),
                                                                 dailyChangePercentage = finalChange,
                                                                 assetType = type,
                                                                 currency = itemCurrency,
                                                                 isFavorite = exist?.isFavorite
                                                                                 ?: false,
                                                                 lastUpdated =
                                                                         System.currentTimeMillis()
                                                         ),
                                                         type
                                                 )

                                                 namedAsset
                                             } else null
                                        } catch (e: Exception) {
                                            android.util.Log.e(
                                                    "CuzdanDebug",
                                                    "Resilient fetch FAILED for $sym: ${e.message}"
                                            )
                                            null
                                        }
                                    }
                                }
                                .awaitAll()
                                .filterNotNull()
                        }
                        // Filter out fake "Türk Lirası" entries in DOVIZ that Yahoo sometimes returns
                        // for non-TRY symbols (e.g. a cross-rate that resolves to TRY=X)
                        val filteredChunkResults = if (type == AssetType.DOVIZ) {
                            chunkResults.filter { asset ->
                                val isSpuriousTry = (asset.name?.contains("Türk Lira", ignoreCase = true) == true || 
                                    asset.name?.contains("Turkish Lira", ignoreCase = true) == true ||
                                    asset.name == "USD/TRY" ||
                                    asset.fullName?.contains("Türk Lira", ignoreCase = true) == true || 
                                    asset.fullName?.contains("Turkish Lira", ignoreCase = true) == true ||
                                    asset.symbol == "TRY" || asset.symbol == "TRY=X")
                                !isSpuriousTry
                            }
                        } else chunkResults
                        results.addAll(filteredChunkResults)
                        
                        // Incrementally save to DB so the UI updates immediately!
                        if (filteredChunkResults.isNotEmpty()) {
                            marketAssetDao.insertMarketAssets(filteredChunkResults)
                        }
                        
                        delay(1000L) // Wait 1 second between chunks of 5
                    }
                    marketAssets.addAll(results)

                    if (type == AssetType.EMTIA) {
                        val ons =
                                marketAssets.find {
                                    it.symbol == "GOLD" ||
                                            it.symbol == "GC=F" ||
                                            it.symbol == "XAUUSD=X"
                                }

                        android.util.Log.d(
                                "CuzdanDebug",
                                "EMTIA processing: onsFound=${ons != null}, usdTryPrice=$usdTryPrice"
                        )

                        // Piyasa kapalıysa (hafta sonu / tatil) değişimi her zaman sıfırla
                        val emtiaMarketClosed = com.yusufulgen.cuzdan.util.MarketStatusUtils.isMarketClosedToday(AssetType.EMTIA)

                        if (ons != null && usdTryPrice > BigDecimal.ZERO) {
                            val gp =
                                    ons.currentPrice
                                            .divide(BigDecimal("31.1035"), 8, RoundingMode.HALF_UP)
                                            .multiply(usdTryPrice)
                            
                            // Gram Gold Change % = ((1 + OnsChange/100) * (1 + UsdTryChange/100) - 1) * 100
                            // Note: We need the USDTRY change here. 'ons' has its change, but we need USDTRY change too.
                            // Since we might not have a MarketAsset for USDTRY in the same list, we can look it up or use the one from DB.
                            val usdAsset = marketAssets.find { it.symbol == "USDTRY=X" } ?: marketAssetDao.getMarketAssetBySymbolAndTypeOnce("USDTRY=X", AssetType.DOVIZ)
                            val usdChange = usdAsset?.dailyChangePercentage ?: BigDecimal.ZERO
                            
                            val onsFactor = BigDecimal.ONE.add(ons.dailyChangePercentage.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
                            val usdFactor = BigDecimal.ONE.add(usdChange.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
                            val gramAltinChange = onsFactor.multiply(usdFactor).subtract(BigDecimal.ONE).multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)

                            marketAssets.add(
                                    MarketAsset(
                                            "GRAM_ALTIN",
                                            "Gram Altın",
                                            "Gram Altın",
                                            gp.setScale(2, RoundingMode.HALF_UP),
                                            gramAltinChange,
                                            AssetType.EMTIA,
                                            "TRY"
                                    )
                            )
                        } else if (marketAssets.find { it.symbol == "GRAM_ALTIN" } == null) {
                            marketAssets.add(
                                    MarketAsset(
                                            "GRAM_ALTIN",
                                            "Gram Altın",
                                            "Gram Altın",
                                            BigDecimal.ZERO,
                                            BigDecimal.ZERO,
                                            AssetType.EMTIA,
                                            "TRY"
                                    )
                            )
                        }

                        // Calculate Gram Silver
                        val silverOns = marketAssets.find { it.symbol == "SILVER" || it.symbol == "SI=F" }
                        if (silverOns != null && usdTryPrice > BigDecimal.ZERO) {
                            val sp = silverOns.currentPrice
                                    .divide(BigDecimal("31.1035"), 8, RoundingMode.HALF_UP)
                                    .multiply(usdTryPrice)
                            
                            val usdAsset = marketAssets.find { it.symbol == "USDTRY=X" } ?: marketAssetDao.getMarketAssetBySymbolAndTypeOnce("USDTRY=X", AssetType.DOVIZ)
                            val usdChange = usdAsset?.dailyChangePercentage ?: BigDecimal.ZERO
                            
                            val silverFactor = BigDecimal.ONE.add(silverOns.dailyChangePercentage.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
                            val usdFactor = BigDecimal.ONE.add(usdChange.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
                            val gramGumusChange = silverFactor.multiply(usdFactor).subtract(BigDecimal.ONE).multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)

                            marketAssets.add(
                                    MarketAsset(
                                            "GRAM_GUMUS",
                                            "Gram Gümüş",
                                            "Gram Gümüş",
                                            sp.setScale(2, RoundingMode.HALF_UP),
                                            gramGumusChange,
                                            AssetType.EMTIA,
                                            "TRY"
                                    )
                            )
                        } else if (marketAssets.find { it.symbol == "GRAM_GUMUS" } == null) {
                            marketAssets.add(
                                    MarketAsset(
                                            "GRAM_GUMUS",
                                            "Gram Gümüş",
                                            "Gram Gümüş",
                                            BigDecimal.ZERO,
                                            BigDecimal.ZERO,
                                            AssetType.EMTIA,
                                            "TRY"
                                    )
                            )
                        }
                    }
                    android.util.Log.d(
                            "CuzdanDebug",
                            "Final results to save for $type: ${marketAssets.size}"
                    )
                }
            }
            if (marketAssets.isNotEmpty()) {
                val currentAssets = marketAssetDao.getMarketAssetsByTypeOnce(type)
                val currentFavorites = currentAssets.filter { it.isFavorite }.associateBy { it.symbol }
                val fetchedSymbols = marketAssets.map { it.symbol }.toSet()
                
                // Keep the assets that we failed to fetch this time so they don't disappear
                val assetsToKeep = currentAssets.filter { it.symbol !in fetchedSymbols }

                val deduplicatedAssets =
                        marketAssets.map { asset ->
                            if (currentFavorites.containsKey(asset.symbol)) {
                                asset.copy(isFavorite = true)
                            } else asset
                        }

                val finalAssets = deduplicatedAssets + assetsToKeep

                marketAssetDao.deleteMarketAssetsByType(type)
                marketAssetDao.insertMarketAssets(finalAssets)
                android.util.Log.d("CuzdanDebug", "Successfully saved to Database")
            }
        } catch (e: Exception) {
            android.util.Log.e("CuzdanDebug", "Internal error: ${e.message}")
        }
    }

    suspend fun searchAssets(query: String, type: AssetType): List<MarketAsset> {
        if (query.isBlank()) return emptyList()
        try {
            // STEP 1: Search local database first (Much faster with SQL LIKE)
            val localResults = marketAssetDao.searchMarketAssetsOnce(query, type)

            // If we have many local results for non-crypto types, return them (they are more
            // standard)
            if (type != AssetType.KRIPTO && localResults.size >= 10) {
                return localResults
            }

            // STEP 2: Remote search
            val remoteResults =
                    when (type) {
                        AssetType.KRIPTO -> {
                            try {
                                binanceApi
                                        .getAllTickers()
                                        .filter {
                                            it.symbol.endsWith("USDT") &&
                                                    it.symbol.contains(query, ignoreCase = true)
                                        }
                                        .take(20)
                                        .map { ticker ->
                                            MarketAsset(
                                                    symbol = ticker.symbol,
                                                    name = ticker.symbol.replace("USDT", ""),
                                                    fullName = ticker.symbol.replace("USDT", ""),
                                                    currentPrice =
                                                            ticker.lastPrice.toBigDecimalOrNull()
                                                                    ?: BigDecimal.ZERO,
                                                    dailyChangePercentage =
                                                            ticker.priceChangePercent
                                                                    .toBigDecimalOrNull()
                                                                    ?.setScale(
                                                                            2,
                                                                            RoundingMode.HALF_UP
                                                                    )
                                                                    ?: BigDecimal.ZERO,
                                                    assetType = AssetType.KRIPTO,
                                                    currency = "USD"
                                            )
                                        }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        AssetType.BIST -> {
                            coroutineScope {
                                // Parallel search: Static list matches + Yahoo API search
                                val staticSymbols =
                                        BistSymbols.all
                                                .filter { it.contains(query, ignoreCase = true) }
                                                .take(10)

                                val yahooSearch =
                                        try {
                                            yahooFinanceApi.search(query)
                                        } catch (e: Exception) {
                                            null
                                        }
                                val remoteBistSymbols =
                                        yahooSearch?.quotes
                                                ?.filter { it.symbol.endsWith(".IS") }
                                                ?.map { it.symbol }
                                                ?: emptyList()

                                val allCandidateSymbols =
                                        (staticSymbols + remoteBistSymbols).distinct().take(15)

                                allCandidateSymbols
                                        .map { sym ->
                                            async { fetchYahooMarketAsset(sym, AssetType.BIST) }
                                        }
                                        .awaitAll()
                                        .filterNotNull()
                            }
                        }
                        else -> {
                            try {
                                val response = yahooFinanceApi.search(query)
                                val symbols =
                                        response.quotes?.map { it.symbol }?.take(15) ?: emptyList()
                                if (symbols.isEmpty()) emptyList()
                                else {
                                    val quotes =
                                            yahooFinanceApi.getQuotes(symbols.joinToString(","))
                                                    .quoteResponse
                                                    .result
                                                    ?: emptyList()
                                    quotes.map { quote ->
                                        MarketAsset(
                                                symbol = quote.symbol,
                                                name = quote.shortName
                                                                ?: quote.longName ?: quote.symbol,
                                                fullName = quote.longName
                                                                ?: quote.shortName ?: quote.symbol,
                                                currentPrice =
                                                        (quote.regularMarketPrice
                                                                        ?: BigDecimal.ZERO)
                                                                .setScale(2, RoundingMode.HALF_UP),
                                                dailyChangePercentage =
                                                        (quote.regularMarketChangePercent
                                                                        ?: BigDecimal.ZERO)
                                                                .setScale(2, RoundingMode.HALF_UP),
                                                assetType = type,
                                                currency = quote.currency ?: "USD"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }

            val totalResults =
                    (localResults + remoteResults).distinctBy { it.symbol }.map {
                        cleanMarketAssetNaming(it, type)
                    }

            // Persist newly discovered assets to local Market database asynchronously
            if (remoteResults.isNotEmpty()) {
                repositoryScope.launch {
                    try {
                        val toInsert =
                                remoteResults.map { asset ->
                                    val existing =
                                            marketAssetDao.getMarketAssetBySymbolAndTypeOnce(
                                                    asset.symbol,
                                                    asset.assetType
                                            )
                                    asset.copy(isFavorite = existing?.isFavorite ?: false)
                                }
                        marketAssetDao.insertMarketAssets(toInsert)
                    } catch (e: Exception) {
                        Log.e("AssetRepo", "Error persisting search results: ${e.message}")
                    }
                }
            }

            return totalResults
        } catch (e: Exception) {
            Log.e("AssetRepo", "searchAssets failed for query=$query: ${e.message}")
            return emptyList()
        }
    }

    suspend fun getMarketAssetsOnce(type: AssetType): List<MarketAsset> {
        val assets = marketAssetDao.getMarketAssetsByTypeOnce(type)
        return if (type == AssetType.KRIPTO) {
            assets.filter { it.symbol.endsWith("USDT") }
        } else if (type == AssetType.DOVIZ) {
            assets.filter { it.symbol != "AEDTRY=X" }
        } else {
            assets
        }
    }

    /**
     * Varlığı veritabanına kaydeder veya mevcutsa günceller. Miktarı toplamaz, gönderilen varlık
     * nesnesi nihai haldir.
     */
    /**
     * Varlığı veritabanına kaydeder veya mevcutsa günceller. Miktarı toplamaz, gönderilen varlık
     * nesnesi nihai haldir. Var olan TÜM mükerrer kayıtları temizleyerek tekil bir kayıt bırakır
     * (Bug 1 Fix).
     */
    suspend fun getMarketAssetBySymbolAndTypeOnce(symbol: String, type: AssetType): MarketAsset? {
        return marketAssetDao.getMarketAssetBySymbolAndTypeOnce(symbol, type)
    }

    suspend fun addAsset(asset: Asset) {
        val portfolioAssets = assetDao.getAssetsByPortfolioIdOnce(asset.portfolioId)
        val existingMatches = portfolioAssets.filter { it.symbol == asset.symbol }

        if (existingMatches.isNotEmpty()) {
            // İlkini güncelle, diğerlerini sil (mükerrer kayıtları temizle)
            val primary = existingMatches.first()
            assetDao.updateAsset(asset.copy(id = primary.id))

            if (existingMatches.size > 1) {
                existingMatches.drop(1).forEach { assetDao.deleteAsset(it) }
            }
        } else {
            assetDao.insertAsset(asset)
        }
    }

    suspend fun upsertAsset(asset: Asset) {
        val existing = assetDao.getAssetBySymbolAndPortfolioId(asset.symbol, asset.portfolioId)
        if (existing != null)
                assetDao.updateAsset(
                        existing.copy(
                                currentPrice = asset.currentPrice,
                                dailyChangePercentage = asset.dailyChangePercentage
                        )
                )
        else assetDao.insertAsset(asset)
    }

    /**
     * Get historical price data for a symbol.
     * Implements a fallback logic: if requested range is empty (e.g. market closed),
     * it tries broader ranges to show the last available state.
     */
    /**
     * Get historical price data for a symbol.
     * Implements a fallback logic: if requested range is empty (e.g. market closed),
     * it tries broader ranges and lower intervals to show the last available state.
     */
    suspend fun getAssetHistory(
            symbol: String,
            assetType: com.yusufulgen.cuzdan.data.local.entity.AssetType? = null,
            range: String = "1d",
            interval: String = "1m"
    ): List<Pair<Long, Double>> {
        if (symbol == "TRY" || symbol == "TL") {
            return listOf(
                    System.currentTimeMillis() - 86400000 to 1.0,
                    System.currentTimeMillis() to 1.0
            )
        }
        val cacheKey = "${symbol}_${range}_${interval}"
        val cached = historyCache[cacheKey]
        // 3 seconds cache: Long enough to stop infinite loops, short enough to feel "live" on every entry
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 3000) {
            return cached.data
        }

        // 1. Try requested range/interval
        var history = fetchYahooHistoryData(symbol, assetType, range, interval)
        
        // 1b. If it failed and was 1m, try 5m immediately
        if (history.isEmpty() && interval == "1m") {
            history = fetchYahooHistoryData(symbol, assetType, range, "5m")
        }
        
        // 2. Fallback for weekend/closed market: Jump straight to 5d/15m
        if (history.isEmpty() && range == "1d") {
            history = fetchYahooHistoryData(symbol, assetType, "5d", "15m")
        }
        
        // 3. Last resort fallback (1 month history)
        if (history.isEmpty() && (range == "1d" || range == "5d")) {
            history = fetchYahooHistoryData(symbol, assetType, "1mo", "1d")
        }

        // 4. PERSISTENCE: If still empty, try to load from DB
        if (history.isEmpty() && assetType != null) {
            val marketAsset = marketAssetDao.getMarketAssetBySymbolAndTypeOnce(symbol, assetType)
            val savedJson = marketAsset?.chartDataJson
            if (!savedJson.isNullOrEmpty()) {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<Pair<Long, Double>>>() {}.type
                    history = gson.fromJson(savedJson, type)
                } catch (_: Exception) {}
            }
        } else if (history.isNotEmpty() && assetType != null) {
            // Save successful history to DB for future fallback
            try {
                val json = gson.toJson(history)
                marketAssetDao.updateChartData(symbol, assetType, json)
            } catch (_: Exception) {}
        }

        if (history.isNotEmpty()) {
            historyCache[cacheKey] = CachedHistory(history, System.currentTimeMillis())
        }
        return history
    }

    private suspend fun fetchYahooHistoryData(
        symbol: String,
        assetType: com.yusufulgen.cuzdan.data.local.entity.AssetType?,
        range: String,
        interval: String
    ): List<Pair<Long, Double>> {
        // Special synthetic symbol: Gram Altın (TRY) = (Ons Altın (USD) / 31.1) * USDTRY
        if (symbol.uppercase() == "GRAM_ALTIN") {
            return try {
                val onsData = fetchYahooHistoryData("GC=F", null, range, interval)
                val usdTryData = fetchYahooHistoryData("TRY=X", null, range, interval)

                if (onsData.isEmpty() || usdTryData.isEmpty()) return emptyList()

                val allTs = (onsData.map { it.first } + usdTryData.map { it.first })
                    .distinct()
                    .sorted()
                
                fun lastValueAt(series: List<Pair<Long, Double>>, t: Long): Double? =
                    series.lastOrNull { it.first <= t }?.second ?: series.firstOrNull()?.second

                val ozToGram = 31.1
                allTs.mapNotNull { t ->
                    val onsP = lastValueAt(onsData, t)
                    val usdP = lastValueAt(usdTryData, t)
                    if (onsP == null || usdP == null) null else t to (onsP / ozToGram) * usdP
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        return try {
            val clean = symbol.uppercase()
            val target = when (assetType) {
                com.yusufulgen.cuzdan.data.local.entity.AssetType.KRIPTO -> {
                    when {
                        clean.endsWith("USDT") && clean.length > 4 -> "${clean.dropLast(4)}-USD"
                        clean.endsWith("-USD") -> clean
                        else -> "$clean-USD"
                    }
                }
                com.yusufulgen.cuzdan.data.local.entity.AssetType.BIST -> {
                    if (clean.endsWith(".IS")) clean else "$clean.IS"
                }
                // DOVIZ, EMTIA, NAKIT, FON, null → use symbol as-is (already correct format)
                else -> symbol
            }

            val result = yahooFinanceApi.getChartData(target, range, interval)
                .chart.result?.firstOrNull() ?: return emptyList()
            
            val timestamps = result.timestamp ?: emptyList()
            val prices = result.indicators?.quote?.firstOrNull()?.close ?: emptyList()
            
            timestamps.zip(prices).mapNotNull { (ts, p) -> 
                p?.let { ts * 1000 to it.toDouble() } 
            }
        } catch (_: Exception) {
            emptyList()
        }
    }


    suspend fun getPortfolioById(id: Long) = portfolioDao.getPortfolioById(id)
    suspend fun getAssetBySymbolAndPortfolioId(s: String, pId: Long): Asset? =
            assetDao.getAssetBySymbolAndPortfolioId(s, pId)
    suspend fun deleteAsset(a: Asset) = assetDao.deleteAsset(a)
    suspend fun toggleFavorite(s: String, t: AssetType, f: Boolean) =
            marketAssetDao.updateFavorite(s, t, f)

    suspend fun getAssetsByPortfolioIdOnce(id: Long) = assetDao.getAssetsByPortfolioIdOnce(id)

    fun getAllPriceAlerts(): Flow<List<PriceAlert>> = priceAlertDao.getAllAlerts()
    fun getAlertsForAsset(s: String, t: AssetType): Flow<List<PriceAlert>> =
            priceAlertDao.getAlertsForAsset(s, t)
    suspend fun insertPriceAlert(a: PriceAlert) = priceAlertDao.insertAlert(a)
    suspend fun updatePriceAlert(a: PriceAlert) = priceAlertDao.updateAlert(a)
    suspend fun deletePriceAlert(a: PriceAlert) = priceAlertDao.deleteAlert(a)
    suspend fun markAlertAsTriggered(id: Long) = priceAlertDao.markAsTriggered(id)
    suspend fun getActivePriceAlerts() = priceAlertDao.getActiveAlerts()

    suspend fun recordPortfolioSnapshot(pId: Long, v: BigDecimal, c: String) =
            portfolioHistoryDao.insert(
                    PortfolioHistory(
                            portfolioId = pId,
                            date = System.currentTimeMillis(),
                            totalValue = v,
                            currency = c
                    )
            )
    suspend fun getLatestHistoryBefore(pId: Long, date: Long): PortfolioHistory? =
            portfolioHistoryDao.getLatestBefore(pId, date)
    fun getPortfolioHistory(pId: Long): Flow<List<PortfolioHistory>> =
            portfolioHistoryDao.getAllHistory(pId)

    suspend fun reconstructPortfolioHistory(pId: Long, range: String): List<PortfolioHistory> =
            coroutineScope {
                val assets =
                        if (pId == -1L)
                                portfolioDao
                                        .getIncludedPortfoliosOnce()
                                        .flatMap { assetDao.getAssetsByPortfolioIdOnce(it.id) }
                                        .groupBy { it.symbol }
                                        .map { (_, sAssets) ->
                                            val totalAmt =
                                                    sAssets.fold(BigDecimal.ZERO) { acc, a ->
                                                        acc + a.amount
                                                    }
                                            val totalCost =
                                                    sAssets.fold(BigDecimal.ZERO) { acc, a ->
                                                        acc + (a.amount * a.averageBuyPrice)
                                                    }
                                            sAssets.first()
                                                    .copy(
                                                            amount = totalAmt,
                                                            averageBuyPrice =
                                                                    if (totalAmt > BigDecimal.ZERO)
                                                                            totalCost.divide(
                                                                                    totalAmt,
                                                                                    8,
                                                                                    RoundingMode
                                                                                            .HALF_UP
                                                                            )
                                                                    else BigDecimal.ZERO
                                                    )
                                        }
                        else assetDao.getAssetsByPortfolioIdOnce(pId)

                if (assets.isEmpty()) return@coroutineScope emptyList()
                val interval = if (range == "7d") "1h" else "1d"
                val histories =
                        assets
                                .map { a ->
                                    async { a to getAssetHistory(a.symbol, assetType = a.assetType, range = range, interval = interval) }
                                }
                                .awaitAll()
                val usdHistory = getAssetHistory("USDTRY=X", assetType = null, range = range, interval = interval)
                val eurHistory = getAssetHistory("EURTRY=X", assetType = null, range = range, interval = interval)
                val allTs =
                        (histories.flatMap { it.second.map { p -> p.first } } +
                                        usdHistory.map { it.first } +
                                        eurHistory.map { it.first })
                                .distinct()
                                .sorted()

                // Total cost base = sum of (amount * averageBuyPrice * exchangeRate) for all assets
                var totalCostBase = BigDecimal.ZERO
                assets.forEach { a ->
                    val rate =
                            when (a.currency) {
                                "USD" -> usdHistory.lastOrNull()?.second ?: 44.52
                                "EUR" -> eurHistory.lastOrNull()?.second ?: 35.2
                                else -> 1.0
                            }
                    totalCostBase += (a.amount * a.averageBuyPrice * BigDecimal(rate.toString()))
                }

                allTs.map { ts ->
                    var dayVal = BigDecimal.ZERO
                    histories.forEach { (a, h) ->
                        val p =
                                h.lastOrNull { it.first <= ts }?.second
                                        ?: h.firstOrNull()?.second ?: 0.0
                        val rate =
                                when (a.currency) {
                                    "USD" -> usdHistory.lastOrNull { it.first <= ts }?.second
                                                    ?: 44.52
                                    "EUR" -> eurHistory.lastOrNull { it.first <= ts }?.second
                                                    ?: 35.0
                                    else -> 1.0
                                }
                        dayVal +=
                                (a.amount * BigDecimal(p.toString()) * BigDecimal(rate.toString()))
                    }
                    // profitLoss = current market value at this timestamp - total cost of all
                    // assets
                    PortfolioHistory(
                            portfolioId = pId,
                            date = ts,
                            totalValue = dayVal,
                            currency = "TRY",
                            profitLoss = dayVal - totalCostBase
                    )
                }
            }

    private fun cleanMarketAssetNaming(asset: MarketAsset, type: AssetType): MarketAsset {
        var name = asset.name
        var symbol = asset.symbol
        var fullName = asset.fullName ?: asset.name
        val cleanSymbol = symbol.uppercase().replace(".IS", "").replace("=F", "").replace("=X", "")

        when {
            type == AssetType.BIST -> {
                // User wants Ticker as Title (name) and Company as Subtitle (fullName)
                val ticker = symbol.replace(".IS", "").replace(".is", "").trim().uppercase()
                // Use the longer name as the company name to avoid losing data
                val rawCompanyName = if (fullName.length > name.length) fullName else name
                fullName = fixYahooEncoding(rawCompanyName).replace(".IS", "").replace(".is", "").trim()
                name = ticker
                symbol = ticker
                android.util.Log.d(
                        "CUZDAN_LOG",
                        "CleanBIST: Sym=$symbol, Name=$name, Full=$fullName"
                )
            }
            type == AssetType.KRIPTO -> {
                symbol = symbol.replace("USDT", "").replace("usdt", "").trim()
                name = symbol
            }
            type == AssetType.DOVIZ ||
                    type == AssetType.NAKIT ||
                    (type == AssetType.EMTIA &&
                            (cleanSymbol == "USDTRY")) -> {
                val localized =
                        when {
                            cleanSymbol.contains("USDTRY") || cleanSymbol == "USD" ->
                                    "Amerikan Doları"
                            cleanSymbol == "TRY" && type == AssetType.NAKIT -> "Türk Lirası"
                            cleanSymbol == "TRY" && type != AssetType.NAKIT -> "USD/TRY" // Fallback naming for bad symbols
                            cleanSymbol.contains("EURTRY") || cleanSymbol == "EUR" -> "Euro"
                            cleanSymbol.contains("GBPTRY") || cleanSymbol == "GBP" ->
                                    "İngiliz Sterlini"
                            cleanSymbol.contains("CHFTRY") || cleanSymbol == "CHF" ->
                                    "İsviçre Frangı"
                            cleanSymbol.contains("JPYTRY") || cleanSymbol == "JPY" -> "Japon Yeni"
                            cleanSymbol.contains("AUDTRY") || cleanSymbol == "AUD" ->
                                    "Avustralya Doları"
                            cleanSymbol.contains("CADTRY") || cleanSymbol == "CAD" ->
                                    "Kanada Doları"
                            cleanSymbol.contains("NZDTRY") || cleanSymbol == "NZD" ->
                                    "Y. Zelanda Doları"
                            cleanSymbol.contains("SGDTRY") || cleanSymbol == "SGD" ->
                                    "Singapur Doları"
                            cleanSymbol.contains("AEDTRY") || cleanSymbol == "AED" -> "BAE Dirhemi"
                            cleanSymbol.contains("SARTRY") || cleanSymbol == "SAR" ->
                                    "Suudi A. Riyali"
                            cleanSymbol.contains("QARTRY") || cleanSymbol == "QAR" -> "Katar Riyali"
                            cleanSymbol.contains("RUBTRY") || cleanSymbol == "RUB" -> "Rus Rublesi"
                            cleanSymbol.contains("CNYTRY") || cleanSymbol == "CNY" -> "Çin Yuanı"
                            cleanSymbol.contains("AZNTRY") || cleanSymbol == "AZN" ->
                                    "Azerbaycan Manatı"
                            cleanSymbol.contains("NOKTRY") || cleanSymbol == "NOK" -> "Norveç Kronu"
                            cleanSymbol.contains("SEKTRY") || cleanSymbol == "SEK" -> "İsveç Kronu"
                            cleanSymbol.contains("DKKTRY") || cleanSymbol == "DKK" ->
                                    "Danimarka Kronu"
                            cleanSymbol.contains("MXNTRY") || cleanSymbol == "MXN" ->
                                    "Meksika Pesosu"
                            cleanSymbol.contains("BRLTRY") || cleanSymbol == "BRL" ->
                                    "Brezilya Reali"
                            cleanSymbol.contains("INRTRY") || cleanSymbol == "INR" ->
                                    "Hindistan Rupisi"
                            cleanSymbol.contains("KRWTRY") || cleanSymbol == "KRW" ->
                                    "Güney Kore Wonu"
                            cleanSymbol.contains("HKDTRY") || cleanSymbol == "HKD" ->
                                    "Hong Kong Doları"
                            cleanSymbol.contains("PLNTRY") || cleanSymbol == "PLN" ->
                                    "Polonya Zlotisi"
                            cleanSymbol.contains("CZKTRY") || cleanSymbol == "CZK" -> "Çek Korunası"
                            cleanSymbol.contains("HUFTRY") || cleanSymbol == "HUF" ->
                                    "Macar Forinti"
                            cleanSymbol.contains("RONTRY") || cleanSymbol == "RON" -> "Rumen Leyi"
                            cleanSymbol.contains("ILSTRY") || cleanSymbol == "ILS" ->
                                    "İsrail Şekeli"
                            cleanSymbol.contains("KWDTRY") || cleanSymbol == "KWD" ->
                                    "Kuveyt Dinarı"
                            cleanSymbol.contains("OMRTRY") || cleanSymbol == "OMR" -> "Umman Riyali"
                            cleanSymbol.contains("BHDTRY") || cleanSymbol == "BHD" ->
                                    "Bahreyn Dinarı"
                            cleanSymbol.contains("THBTRY") || cleanSymbol == "THB" ->
                                    "Tayland Bahtı"
                            cleanSymbol.contains("MYRTRY") || cleanSymbol == "MYR" ->
                                    "Malezya Ringgiti"
                            cleanSymbol.contains("IDRTRY") || cleanSymbol == "IDR" ->
                                    "Endonezya Rupisi"
                            cleanSymbol.contains("PHPTRY") || cleanSymbol == "PHP" ->
                                    "Filipinler Pesosu"
                            cleanSymbol.contains("EGPTRY") || cleanSymbol == "EGP" -> "Mısır Lirası"
                            cleanSymbol.contains("ZARTRY") || cleanSymbol == "ZAR" ->
                                    "Güney Afrika Randı"
                            cleanSymbol.contains("MADTRY") || cleanSymbol == "MAD" -> "Fas Dirhemi"
                            cleanSymbol.contains("GELTRY") || cleanSymbol == "GEL" ->
                                    "Gürcistan Larisi"
                            cleanSymbol.contains("UAHTRY") || cleanSymbol == "UAH" ->
                                    "Ukrayna Grivnası"
                            cleanSymbol.contains("BGNTRY") || cleanSymbol == "BGN" ->
                                    "Bulgar Levası"
                            cleanSymbol.contains("ISKTRY") || cleanSymbol == "ISK" ->
                                    "İzlanda Kronu"
                            cleanSymbol.contains("KAZTRY") || cleanSymbol == "KAZ" ->
                                    "Kazakistan Tengesi"
                            cleanSymbol.contains("VNDDTRY") ||
                                    cleanSymbol == "VNDD" ||
                                    cleanSymbol == "VND" -> "Vietnam Dongu"
                            cleanSymbol.contains("PKRTRY") || cleanSymbol == "PKR" ->
                                    "Pakistan Rupisi"
                            else -> fullName
                        }
                fullName = localized
                name = localized
                symbol = if (cleanSymbol == "TRY") "TRY" else cleanSymbol.replace("TRY", "")
            }
            type == AssetType.EMTIA -> {
                val naming =
                        when {
                            asset.symbol == "GC=F" -> "Altın (Ons)" to "GOLD"
                            asset.symbol == "SI=F" -> "Gümüş (Ons)" to "SILVER"
                            asset.symbol == "HG=F" -> "Bakır" to "HG"
                            asset.symbol == "CL=F" -> "Ham Petrol" to "CL"
                            asset.symbol == "BZ=F" -> "Brent Petrol" to "BZ"
                            asset.symbol == "NG=F" -> "Doğal Gaz" to "NG"
                            asset.symbol == "ALI=F" -> "Alüminyum" to "ALI"
                            asset.symbol == "NI=F" -> "Nikel" to "NI"
                            asset.symbol == "ZN=F" -> "Çinko" to "ZN"
                            asset.symbol == "PA=F" -> "Paladyum" to "PA"
                            asset.symbol == "PL=F" -> "Platin" to "PL"
                            asset.symbol == "KC=F" -> "Kahve" to "KC"
                            asset.symbol == "CC=F" -> "Kakao" to "CC"
                            asset.symbol == "CT=F" -> "Pamuk" to "CT"
                            asset.symbol == "SB=F" -> "Şeker" to "SB"
                            asset.symbol == "ZC=F" -> "Mısır" to "ZC"
                            asset.symbol == "ZW=F" -> "Buğday" to "ZW"
                            asset.symbol == "ZS=F" -> "Soya Fasulyesi" to "ZS"
                            asset.symbol == "LBS=F" -> "Kereste" to "LBS"
                            asset.symbol == "RB=F" -> "RBOB Benzin" to "RB"
                            asset.symbol == "HO=F" -> "Isınma Yakıtı" to "HO"
                            asset.symbol == "GRAM_ALTIN" -> "Gram Altın" to "GRAM_ALTIN"
                            asset.symbol == "GRAM_GUMUS" -> "Gram Gümüş" to "GRAM_GUMUS"
                            else -> name.replace(" Futures", "") to symbol.replace("=F", "")
                        }
                name = naming.first
                symbol = naming.second
                fullName = name
            }
        }
        return asset.copy(name = name, fullName = fullName, symbol = symbol)
    }

    private fun cleanAssetNaming(asset: Asset, type: AssetType): Asset {
        var name = asset.name
        var symbol = asset.symbol
        val cleanSymbol = symbol.uppercase()
        when {
            type == AssetType.BIST -> {
                name = name.replace(".IS", "").replace(".is", "").trim()
                symbol = symbol.replace(".IS", "").replace(".is", "").trim()
            }
            type == AssetType.KRIPTO -> {
                symbol = symbol.replace("USDT", "").replace("usdt", "").trim()
                if (name.contains("USDT", ignoreCase = true)) {
                    name = name.replace("USDT", "", ignoreCase = true).trim()
                }
            }
            type == AssetType.DOVIZ ||
                    type == AssetType.NAKIT ||
                    (type == AssetType.EMTIA &&
                            (cleanSymbol == "TRY=X" || cleanSymbol == "USDTRY=X")) -> {
                name =
                        when {
                            cleanSymbol.contains("USD") -> "Amerikan Doları"
                            cleanSymbol.contains("EUR") -> "Euro"
                            cleanSymbol.contains("GBP") -> "İngiliz Sterlini"
                            cleanSymbol.contains("CHF") -> "İsviçre Frangı"
                            cleanSymbol.contains("JPY") -> "Japon Yeni"
                            cleanSymbol.contains("AUD") -> "Avustralya Doları"
                            cleanSymbol.contains("CAD") -> "Kanada Doları"
                            cleanSymbol.contains("AED") -> "Birleşik Arap Emirlikleri Dirhemi"
                            else -> name
                        }
                symbol =
                        when {
                            cleanSymbol.contains("USD") -> "USD"
                            cleanSymbol.contains("EUR") -> "EUR"
                            cleanSymbol.contains("GBP") -> "GBP"
                            cleanSymbol.contains("CHF") -> "CHF"
                            cleanSymbol.contains("JPY") -> "JPY"
                            cleanSymbol.contains("AUD") -> "AUD"
                            cleanSymbol.contains("CAD") -> "CAD"
                            cleanSymbol.contains("AED") -> "AED"
                            else -> symbol
                        }
            }
            type == AssetType.EMTIA -> {
                name =
                        when {
                            cleanSymbol.startsWith("GC=F") || cleanSymbol == "GOLD" -> "Altın (Ons)"
                            cleanSymbol.startsWith("SI=F") || cleanSymbol == "SILVER" -> "Gümüş (Ons)"
                            cleanSymbol.startsWith("PL=F") -> "Platin"
                            cleanSymbol.startsWith("PA=F") -> "Paladyum"
                            cleanSymbol.startsWith("HG=F") -> "Bakır"
                            cleanSymbol.startsWith("GRAM_ALTIN") -> "Gram Altın"
                            cleanSymbol.startsWith("GRAM_GUMUS") -> "Gram Gümüş"
                            else -> name
                        }
                name = name.replace(Regex("\\s+[A-Za-z]{3}\\s+\\d{2}$"), "").trim()
                if (cleanSymbol.startsWith("GC=F")) symbol = "GOLD"
                if (cleanSymbol.startsWith("SI=F")) symbol = "SILVER"
            }
        }
        return asset.copy(name = name, symbol = symbol)
    }

    private fun refreshBistIncrementally() {
        if (bistJob?.isActive == true) return

        bistJob =
                repositoryScope.launch {
                    try {
                        // Her yenilemede eski .IS uzantılı hatalı verileri temizle
                        marketAssetDao.cleanStaleBistSymbols()

                        val dbSymbols =
                                assetDao.getAllAssets()
                                        .first()
                                        .filter { it.assetType == AssetType.BIST }
                                        .map {
                                            if (it.symbol.endsWith(".IS")) it.symbol
                                            else "${it.symbol}.IS"
                                        }

                        val allSymbols = (BistSymbols.all + dbSymbols).distinct()
                        val popularSymbols = BistSymbols.popular

                        // 1. Önce popüler hisseleri gruplar halinde çek (Rate limit'e takılmamak için)
                        val initialResults = mutableListOf<MarketAsset>()
                        popularSymbols.chunked(5).forEach { chunk ->
                            val chunkResults = coroutineScope {
                                chunk.map { sym ->
                                    async { fetchYahooMarketAsset(sym, AssetType.BIST) }
                                }.awaitAll().filterNotNull()
                            }
                            if (chunkResults.isNotEmpty()) {
                                marketAssetDao.insertMarketAssets(chunkResults)
                                chunkResults.forEach {
                                    if (!it.symbol.endsWith(".IS")) {
                                        marketAssetDao.deleteMarketAssetBySymbolAndType(
                                                "${it.symbol}.IS",
                                                AssetType.BIST
                                        )
                                    }
                                }
                                initialResults.addAll(chunkResults)
                            }
                            delay(1000) // Her 5'li gruptan sonra 1 saniye bekle
                        }

                        // 2. Geri kalan hisseleri 5'erli gruplar halinde her saniye çek
                        val remainingSymbols = allSymbols.filter { it !in popularSymbols }
                        remainingSymbols.chunked(5).forEach { chunk ->
                            val chunkResults = coroutineScope {
                                chunk
                                        .map { sym ->
                                            async { fetchYahooMarketAsset(sym, AssetType.BIST) }
                                        }
                                        .awaitAll()
                                        .filterNotNull()
                            }

                            if (chunkResults.isNotEmpty()) {
                                marketAssetDao.insertMarketAssets(chunkResults)
                                // Cleanup old .IS symbols in chunks
                                chunkResults.forEach {
                                    if (!it.symbol.endsWith(".IS")) {
                                        marketAssetDao.deleteMarketAssetBySymbolAndType(
                                                "${it.symbol}.IS",
                                                AssetType.BIST
                                        )
                                    }
                                }
                            }
                            delay(1000) // Her 20'li gruptan sonra 1 saniye bekle
                        }
                    } catch (e: Exception) {
                        Log.e("AssetRepository", "Incremental BIST load failed: ${e.message}")
                    }
                }
    }

    private fun fixYahooEncoding(text: String): String {
        return try {
            // Check if it looks like UTF-8 bytes read as ISO-8859-1
            // Common pattern: Ã¼ for ü, Ã§ for ç, etc.
            if (text.contains("Ã")) {
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                val fixed = String(bytes, Charsets.UTF_8)
                fixed
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun remapSymbol(sym: String): String {
        return when (sym.uppercase()) {
            "KVNPY.IS" -> "KERVN.IS"
            "METUR.IS" -> "METUR.IS" // Usually correct, but keep for mapping
            else -> sym
        }
    }

    private suspend fun fetchYahooMarketAsset(sym: String, type: AssetType): MarketAsset? {
        val targetSym = remapSymbol(sym)
        return try {
            val response = yahooFinanceApi.getChartData(targetSym)
            val result = response.chart.result?.firstOrNull()?.meta
            if (result != null) {
                val current = result.regularMarketPrice ?: BigDecimal.ZERO

                // Prioritize regularMarketChangePercent if available
                var change =
                        result.regularMarketChangePercent?.let {
                            BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP)
                        }

                if (change == null) {
                    val prev = result.previousClose ?: result.chartPreviousClose ?: current
                    change =
                            if (prev > BigDecimal.ZERO) {
                                current.subtract(prev)
                                        .divide(prev, 4, RoundingMode.HALF_UP)
                                        .multiply(BigDecimal("100"))
                                        .setScale(2, RoundingMode.HALF_UP)
                            } else BigDecimal.ZERO
                }
                


                Log.d(
                        "CUZDAN_LOG",
                        "FetchMarket($targetSym) -> Price: $current, Change: $change%, Prev: ${result.previousClose}, ChartPrev: ${result.chartPreviousClose}, MetaPct: ${result.regularMarketChangePercent}"
                )

                val exist = marketAssetDao.getMarketAssetBySymbolAndTypeOnce(sym, type)
                cleanMarketAssetNaming(
                        MarketAsset(
                                symbol = sym, // Keep original symbol for DB consistency
                                name = result.shortName ?: result.longName ?: sym,
                                fullName = result.longName ?: result.shortName ?: sym,
                                currentPrice = current.setScale(4, RoundingMode.HALF_UP),
                                dailyChangePercentage = change ?: BigDecimal.ZERO,
                                assetType = type,
                                currency = result.currency ?: "USD",
                                isFavorite = exist?.isFavorite ?: false,
                                lastUpdated = System.currentTimeMillis()
                        ),
                        type
                )
            } else null
        } catch (e: Exception) {
            Log.e("CUZDAN_LOG", "fetchYahooMarketAsset Error for $targetSym: ${e.message}")
            null
        }
    }
}
