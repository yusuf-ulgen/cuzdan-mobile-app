package com.yusufulgen.cuzdan.ui.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.data.local.entity.Asset
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.Portfolio
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.data.repository.PortfolioRepository
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.yusufulgen.cuzdan.util.PriceSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class ReportsUiState(
    val categories: List<ReportCategory> = emptyList(),
    val totalValue: BigDecimal = BigDecimal.ZERO,
    val totalProfitLoss: BigDecimal = BigDecimal.ZERO,
    val totalProfitPerc: BigDecimal = BigDecimal.ZERO,
    val currency: String = "TL",
    val portfolios: List<Portfolio> = emptyList(),
    val isLoading: Boolean = false,
    val lastUpdated: String? = null,
    val isOffline: Boolean = false
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val assetRepository: AssetRepository,
    private val portfolioRepository: PortfolioRepository,
    private val prefManager: PreferenceManager,
    private val priceSyncManager: PriceSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private val _currentCurrency = MutableStateFlow(prefManager.getReportsCurrency())
    private val _selectedPortfolioId = MutableStateFlow(prefManager.getSelectedPortfolioId())
    private var lastAssets: List<Asset> = emptyList()

    private val _usdRate = assetRepository.getLatestPrice("USDTRY=X")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal("44.52"))
    private val _eurRate = assetRepository.getLatestPrice("EURTRY=X")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal("35.2"))


    init {
        observePortfolios()
        observeAssets()
    }

    /**
     * Home'da portföy değiştiğinde Reports'u senkronize eder.
     */
    fun syncPortfolioSelection() {
        val currentPrefId = prefManager.getSelectedPortfolioId()
        if (_selectedPortfolioId.value != currentPrefId) {
            _selectedPortfolioId.value = currentPrefId
        }
    }

    private fun observePortfolios() {
        viewModelScope.launch {
            portfolioRepository.getAllPortfolios().collect { portfolios ->
                _uiState.update { it.copy(portfolios = portfolios) }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeAssets() {
        val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        viewModelScope.launch {
            combine(
                _selectedPortfolioId.flatMapLatest { id ->
                    if (id == -1L) {
                        portfolioRepository.getIncludedPortfolios().flatMapLatest { included ->
                            if (included.isEmpty()) flowOf(emptyList<Asset>())
                            else {
                                val flows = included.map { p -> assetRepository.getAssetsByPortfolioId(p.id) }
                                combine(flows) { lists -> lists.flatMap { it }.let { mergeDuplicateAssets(it) } }
                            }
                        }
                    } else assetRepository.getAssetsByPortfolioId(id)
                },
                _currentCurrency,
                _usdRate,
                _eurRate,
                priceSyncManager.syncStatus,
                assetRepository.getMarketAssetsFlow(null) // Market fiyatlarını dinle
            ) { array ->
                val rawAssets = array[0] as List<Asset>
                val currency = array[1] as String
                val usdRate = array[2] as BigDecimal?
                val eurRate = array[3] as BigDecimal?
                val syncStatus = array[4] as com.yusufulgen.cuzdan.util.PriceSyncManager.SyncStatus
                val marketAssets = (array[5] as List<com.yusufulgen.cuzdan.data.local.entity.MarketAsset>).associateBy { it.symbol to it.assetType }

                // Market fiyatlarını asset'lere merge et (HomeViewModel ile aynı mantık)
                val assets = rawAssets.map { asset ->
                    val market = marketAssets[asset.symbol to asset.assetType]
                    if (market != null) {
                        asset.copy(
                            currentPrice = market.currentPrice,
                            dailyChangePercentage = market.dailyChangePercentage
                        )
                    } else asset
                }

                lastAssets = assets
                
                // Fetch start of day balance when portfolio changes
                if (_selectedPortfolioId.value != -1L) {
                    fetchStartOfDayBalance(_selectedPortfolioId.value)
                }

                calculateReports(assets, currency, usdRate, eurRate)
                
                val timeStr = if (syncStatus.lastUpdate > 0) dateFormat.format(java.util.Date(syncStatus.lastUpdate)) else null
                _uiState.update { it.copy(
                    lastUpdated = timeStr,
                    isOffline = syncStatus.isOffline
                )}
            }.collect()
        }
    }

    private var _startOfDayBalanceValue = BigDecimal.ZERO

    private fun fetchStartOfDayBalance(id: Long) {
        viewModelScope.launch {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startOfToday = calendar.timeInMillis
            
            val history = assetRepository.getLatestHistoryBefore(id, startOfToday)
            _startOfDayBalanceValue = history?.totalValue ?: BigDecimal.ZERO
            
            if (_startOfDayBalanceValue == BigDecimal.ZERO) {
                // Fallback to current deposit if no history
                val p = portfolioRepository.getPortfolioById(id)?.depositedAmount ?: BigDecimal.ZERO
                _startOfDayBalanceValue = p
            }
            
            calculateReports(lastAssets, _currentCurrency.value)
        }
    }

    private fun mergeDuplicateAssets(assets: List<Asset>): List<Asset> {
        return assets.groupBy { it.symbol }.map { (_, symbolAssets) ->
            if (symbolAssets.size == 1) return@map symbolAssets.first()
            var totalAmount = BigDecimal.ZERO
            var totalCost = BigDecimal.ZERO
            symbolAssets.forEach {
                totalAmount = totalAmount.add(it.amount)
                totalCost = totalCost.add(it.amount.multiply(it.averageBuyPrice))
            }
            val avgPrice = if (totalAmount > BigDecimal.ZERO) totalCost.divide(totalAmount, 8, RoundingMode.HALF_UP) else BigDecimal.ZERO
            symbolAssets.first().copy(amount = totalAmount, averageBuyPrice = avgPrice)
        }
    }

    private fun calculateReports(
        assets: List<Asset>, 
        currency: String, 
        usdRate: BigDecimal? = _usdRate.value,
        eurRate: BigDecimal? = _eurRate.value,
        resolveContext: Context = context
    ) {
        var exchangeRate = BigDecimal.ONE
        if (currency == "USD") exchangeRate = usdRate ?: BigDecimal("32.5")
        else if (currency == "EUR") exchangeRate = eurRate ?: BigDecimal("35.2")

        val selectedPortfolio = if (_selectedPortfolioId.value == -1L) null
            else _uiState.value.portfolios.find { it.id == _selectedPortfolioId.value }
        val depositedAmountTry = selectedPortfolio?.depositedAmount ?: BigDecimal.ZERO

        var totalValueBase = BigDecimal.ZERO
        var totalCostBase = BigDecimal.ZERO

        assets.forEach { asset ->
            val assetRate = when (asset.currency) {
                "USD" -> usdRate ?: BigDecimal("32.5")
                "EUR" -> eurRate ?: BigDecimal("35.2")
                else -> BigDecimal.ONE
            }
            val costRate = when (asset.buyCurrency) {
                "USD" -> usdRate ?: BigDecimal("32.5")
                "EUR" -> eurRate ?: BigDecimal("35.2")
                else -> BigDecimal.ONE
            }
            val assetValue = asset.amount.multiply(asset.currentPrice).multiply(assetRate)
            val assetCost = asset.amount.multiply(asset.averageBuyPrice).multiply(costRate)
            totalValueBase = totalValueBase.add(assetValue)
            totalCostBase = totalCostBase.add(assetCost)
        }

        val totalProfitLossAbs = totalValueBase.subtract(totalCostBase).divide(exchangeRate, 2, RoundingMode.HALF_UP)
        
        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val isBistClosedForToday = isWeekend || (hr < 9) || (hr == 9 && min < 55)

        // DAILY PROFIT/LOSS (excluding deposits/withdrawals)
        // Approximation based on today's percentage change:
        // prevPrice = currentPrice / (1 + pct/100)
        var dailyProfitBase = BigDecimal.ZERO
        var prevTotalValueBase = BigDecimal.ZERO

        assets.forEach { asset ->
            if (asset.amount <= BigDecimal.ZERO) return@forEach
            // Ignore cash-like assets; we only want price-driven P/L
            if (asset.assetType == AssetType.NAKIT) return@forEach

            val assetRate = when (asset.currency) {
                "USD" -> usdRate ?: BigDecimal("32.5")
                "EUR" -> eurRate ?: BigDecimal("35.2")
                else -> BigDecimal.ONE
            }

            var pct = asset.dailyChangePercentage
            if (asset.assetType == AssetType.BIST && isBistClosedForToday) {
                pct = BigDecimal.ZERO
            }

            val denom = BigDecimal.ONE.add(pct.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
            if (denom.compareTo(BigDecimal.ZERO) == 0) return@forEach

            val prevPrice = asset.currentPrice.divide(denom, 12, RoundingMode.HALF_UP)
            val diff = asset.currentPrice.subtract(prevPrice)

            dailyProfitBase = dailyProfitBase.add(asset.amount.multiply(diff).multiply(assetRate))
            prevTotalValueBase = prevTotalValueBase.add(asset.amount.multiply(prevPrice).multiply(assetRate))
        }

        val dailyProfitAbs = dailyProfitBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
        val dailyProfitPerc = if (prevTotalValueBase > BigDecimal.ZERO) {
            dailyProfitBase.multiply(BigDecimal("100")).divide(prevTotalValueBase, 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val reportCategories = assets.groupBy { it.assetType }.map { (type, typeAssets) ->
            var catValueBase = BigDecimal.ZERO
            var catCostBase = BigDecimal.ZERO
            typeAssets.forEach { asset ->
                val assetRate = when (asset.currency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }
                val costRate = when (asset.buyCurrency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }
                catValueBase = catValueBase.add(asset.amount.multiply(asset.currentPrice).multiply(assetRate))
                catCostBase = catCostBase.add(asset.amount.multiply(asset.averageBuyPrice).multiply(costRate))
            }
            
            val convCatValue = catValueBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
            val convCatCost = catCostBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
            val catPLAbs = convCatValue.subtract(convCatCost)
            val catPLPerc = if (catCostBase.compareTo(BigDecimal.ZERO) != 0) {
                catValueBase.subtract(catCostBase).divide(catCostBase, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100))
            } else BigDecimal.ZERO


            val reportAssets = when (type) {
                AssetType.NAKIT -> {
                    val order = listOf("TRY", "TL", "USD", "EUR", "GBP", "CHF", "JPY", "GBPUSD=X")
                    typeAssets.sortedWith(compareBy { asset ->
                        val symbol = asset.symbol.uppercase()
                        val name = asset.name.lowercase()
                        if (symbol == "TRY" || symbol == "TL" || symbol == "₺" || symbol.contains("TRY") || symbol.contains("TL") || symbol.contains("₺") || 
                            name.contains("türk lirası") || name.contains("tl") || name.contains("türk") || name == "türk lirasi") {
                            -1
                        } else {
                            val index = order.indexOf(symbol)
                            if (index == -1) Int.MAX_VALUE else index
                        }
                    })
                }
                AssetType.KRIPTO -> {
                    // Kripto varlıkları havuz büyüklüğüne (toplam değer) göre azalan şekilde sırala
                    typeAssets.sortedByDescending { asset ->
                        val assetRate = when (asset.currency) {
                            "USD" -> usdRate ?: BigDecimal("32.5")
                            "EUR" -> eurRate ?: BigDecimal("35.2")
                            else -> BigDecimal.ONE
                        }
                        asset.amount.multiply(asset.currentPrice).multiply(assetRate)
                    }
                }
                else -> typeAssets
            }

            val convertedAssets = reportAssets.map { asset ->
                val assetRate = when (asset.currency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }
                val costRate = when (asset.buyCurrency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }
                val finalPriceRate = assetRate.divide(exchangeRate, 12, RoundingMode.HALF_UP)
                val finalCostRate = costRate.divide(exchangeRate, 12, RoundingMode.HALF_UP)
                
                var adjustedDailyPerc = asset.dailyChangePercentage
                if (asset.assetType == AssetType.BIST && isBistClosedForToday) {
                    adjustedDailyPerc = BigDecimal.ZERO
                }

                asset.copy(
                    currentPrice = asset.currentPrice.multiply(finalPriceRate),
                    averageBuyPrice = asset.averageBuyPrice.multiply(finalCostRate),
                    currency = currency,
                    dailyChangePercentage = adjustedDailyPerc
                )
            }

            ReportCategory(
                type = type,
                name = getLocalizedAssetTypeName(type, resolveContext),
                totalValue = convCatValue,
                changePerc = catPLPerc,
                changeAbs = catPLAbs,
                assets = convertedAssets
            )
        }.toMutableList()

        // Filter out empty categories and categories where all assets have zero amount
        reportCategories.removeAll { it.assets.isEmpty() || it.assets.all { a -> a.amount.compareTo(BigDecimal.ZERO) == 0 } }

        val categoryOrder = listOf(
            AssetType.NAKIT,
            AssetType.BIST,
            AssetType.KRIPTO,
            AssetType.EMTIA,
            AssetType.FON,
            AssetType.DOVIZ
        )
        reportCategories.sortBy { categoryOrder.indexOf(it.type).let { idx -> if (idx == -1) Int.MAX_VALUE else idx } }

        val totalProfitPercAbs = if (totalCostBase > BigDecimal.ZERO) {
            totalValueBase.subtract(totalCostBase).multiply(BigDecimal("100")).divide(totalCostBase, 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        _uiState.update { it.copy(
            categories = reportCategories,
            totalValue = totalProfitLossAbs, // BÜYÜK YAZI: Toplam Kâr/Zarar (Varlık Değeri - Maliyet)
            totalProfitLoss = totalProfitLossAbs, // KÜÇÜK YAZI: Toplam Kâr/Zarar
            totalProfitPerc = totalProfitPercAbs, // KÜÇÜK YAZI: Toplam Kâr/Zarar (%)
            currency = currency
        )}
    }

    fun selectPortfolio(id: Long) {
        _selectedPortfolioId.value = id
        prefManager.setSelectedPortfolioId(id)
    }

    fun selectNextPortfolio() {
        val portfolios = _uiState.value.portfolios
        if (portfolios.isEmpty()) return
        val currentId = _selectedPortfolioId.value
        val currentIndex = if (currentId == -1L) -1 else portfolios.indexOfFirst { it.id == currentId }
        val nextIndex = currentIndex + 1
        if (nextIndex >= portfolios.size) selectPortfolio(-1L) else selectPortfolio(portfolios[nextIndex].id)
    }

    fun selectPrevPortfolio() {
        val portfolios = _uiState.value.portfolios
        if (portfolios.isEmpty()) return
        val currentId = _selectedPortfolioId.value
        val currentIndex = if (currentId == -1L) -1 else portfolios.indexOfFirst { it.id == currentId }
        if (currentIndex == -1) selectPortfolio(portfolios.last().id)
        else if (currentIndex == 0) selectPortfolio(-1L)
        else selectPortfolio(portfolios[currentIndex - 1].id)
    }

    fun setCurrency(currency: String) {
        prefManager.setReportsCurrency(currency)
        _currentCurrency.value = currency
    }

    fun refreshLocalization(activityContext: Context) {
        calculateReports(lastAssets, _currentCurrency.value, _usdRate.value, _eurRate.value, activityContext)
    }


    private fun getLocalizedAssetTypeName(type: AssetType, resolveContext: Context): String {
        return resolveContext.getString(when(type) {
            AssetType.KRIPTO -> R.string.asset_type_crypto
            AssetType.BIST -> R.string.asset_type_stocks
            AssetType.DOVIZ -> R.string.asset_type_currency
            AssetType.EMTIA -> R.string.asset_type_commodity
            AssetType.NAKIT -> R.string.asset_type_cash
            AssetType.FON -> R.string.asset_type_fund
        })
    }
}
