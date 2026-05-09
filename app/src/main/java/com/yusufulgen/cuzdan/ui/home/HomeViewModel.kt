package com.yusufulgen.cuzdan.ui.home

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class PortfolioWithBalance(
    val portfolio: Portfolio,
    val balance: BigDecimal = BigDecimal.ZERO,
    val dailyChangeAbs: BigDecimal = BigDecimal.ZERO,
    val dailyChangePerc: BigDecimal = BigDecimal.ZERO,
    val totalCost: BigDecimal = BigDecimal.ZERO,
    val depositedAmount: BigDecimal = BigDecimal.ZERO
)

data class WalletUiState(
    val portfolios: List<PortfolioWithBalance> = emptyList(),
    val selectedPortfolioId: Long = -1,
    val selectedPortfolioName: String = "",
    val totalBalance: BigDecimal = BigDecimal.ZERO,
    val cashBalance: BigDecimal = BigDecimal.ZERO,
    val dailyChangeAbs: BigDecimal = BigDecimal.ZERO,
    val dailyChangePerc: BigDecimal = BigDecimal.ZERO,
    val categorySummaries: List<WalletCategorySummary> = emptyList(),
    val donutSegments: List<DonutChartView.Segment> = emptyList(),
    val donutCenterLabel: String = "Dağılım",
    val donutCenterPercent: String = "%100",
    val isLoading: Boolean = false,
    val currency: String = "TL",
    val lastUpdated: String? = null,
    val isOffline: Boolean = false
)

data class WalletCategorySummary(
    val type: AssetType,
    val title: String,
    val totalValue: java.math.BigDecimal,
    val totalProfitLoss: java.math.BigDecimal,
    val profitLossPerc: java.math.BigDecimal,
    val assets: List<Asset> = emptyList(),
    val isExpanded: Boolean = false,
    val iconRes: Int = 0
)

data class AssetDailyValues(
    val dailyProfitBase: BigDecimal,
    val prevValueBase: BigDecimal
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val assetRepository: AssetRepository,
    private val portfolioRepository: PortfolioRepository,
    private val prefManager: PreferenceManager,
    private val priceSyncManager: PriceSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val _homeCurrency = MutableStateFlow(prefManager.getHomeCurrency())
    private val _selectedPortfolioId = MutableStateFlow(prefManager.getSelectedPortfolioId())
    private val _expandedCategory = MutableStateFlow<AssetType?>(null)
    private val _currentAssets = MutableStateFlow<List<Asset>>(emptyList())

    private val _usdRate = assetRepository.getLatestPrice("USDTRY=X")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal("44.52"))
    private val _eurRate = assetRepository.getLatestPrice("EURTRY=X")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal("35.2"))


    init {
        observePortfolios()
        observeAssets()
        refreshPrices()
    }

    fun refreshPrices() {
        val TAG = "CUZDAN_LOG"
        viewModelScope.launch {
            Log.d(TAG, "Manual refresh triggered from HomeViewModel")
            assetRepository.refreshYahooPrices().collect { }
            assetRepository.refreshCryptoPrices().collect { }
            assetRepository.refreshOwnedFundPrices().collect { }
        }
    }

    private fun observePortfolios() {
        viewModelScope.launch {
            combine(
                portfolioRepository.getAllPortfolios(),
                assetRepository.getAllAssets(),
                _homeCurrency,
                _usdRate,
                _eurRate
            ) { portfolios, allAssets, currency, usdRate, eurRate ->
                val exchangeRate = when (currency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }

                val portfolioList = portfolios.map { p ->
                    val assets = allAssets.filter { it.portfolioId == p.id }
                    var balanceBase = BigDecimal.ZERO
                    var costBase = BigDecimal.ZERO
                    assets.forEach { asset ->
                        val assetRate = when (asset.currency) {
                            "USD" -> usdRate ?: BigDecimal("32.5")
                            "EUR" -> eurRate ?: BigDecimal("35.2")
                            else -> BigDecimal.ONE
                        }
                        balanceBase = balanceBase.add(asset.amount.multiply(asset.currentPrice).multiply(assetRate))
                        costBase = costBase.add(asset.amount.multiply(asset.averageBuyPrice).multiply(assetRate))
                    }
                    val convCost = costBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
                    
                    // Boştaki nakit (TRY): Yatırılan para - Varlıkların alış maliyeti
                    val idleCashInTry = if (p.depositedAmount > BigDecimal.ZERO) {
                        (p.depositedAmount - costBase).coerceAtLeast(BigDecimal.ZERO)
                    } else {
                        BigDecimal.ZERO
                    }
                    
                    // Toplam bakiye (Seçili para biriminde): (Varlıkların değeri + Boştaki nakit) / Kur
                    val totalValueBase = balanceBase.add(idleCashInTry)
                    val convBalance = totalValueBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)

                    // DAILY CHANGE Calculation for the portfolio
                    var portfolioDailyProfitBase = BigDecimal.ZERO
                    var portfolioPrevValueBase = idleCashInTry

                    val cal = java.util.Calendar.getInstance()
                    val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
                    val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val min = cal.get(java.util.Calendar.MINUTE)
                    val isBistClosed = isWeekend || (hr < 9) || (hr == 9 && min < 55)

                    assets.forEach { asset ->
                        val values = calculateAssetDailyValues(asset, usdRate, eurRate, isBistClosed)
                        portfolioDailyProfitBase = portfolioDailyProfitBase.add(values.dailyProfitBase)
                        portfolioPrevValueBase = portfolioPrevValueBase.add(values.prevValueBase)
                    }

                    val dailyChangeAbs = portfolioDailyProfitBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
                    val dailyChangePerc = if (portfolioPrevValueBase > BigDecimal.ZERO) {
                        portfolioDailyProfitBase.multiply(BigDecimal("100")).divide(portfolioPrevValueBase, 2, RoundingMode.HALF_UP)
                    } else BigDecimal.ZERO

                    PortfolioWithBalance(p, convBalance, dailyChangeAbs, dailyChangePerc, convCost, p.depositedAmount)
                }


                val currentId = _selectedPortfolioId.value
                // IMPORTANT: Do not auto-select a portfolio.
                // If the user is in "total / none selected" (-1), keep it that way. This prevents
                // silently attaching new assets to the first portfolio on fresh installs.
                val newId = when {
                    currentId == -1L -> -1L
                    portfolios.any { it.id == currentId } -> currentId
                    else -> -1L
                }
                val selectedPortfolio = portfolios.find { it.id == newId }
                val localizedName =
                    if (newId == -1L) context.getString(R.string.total_portfolios)
                    else selectedPortfolio?.name.orEmpty()

                _selectedPortfolioId.value = newId
                prefManager.setSelectedPortfolioId(newId)
                
                _uiState.update { it.copy(
                    portfolios = portfolioList,
                    selectedPortfolioId = newId,
                    selectedPortfolioName = localizedName,
                    currency = currency
                )}
                calculateStats(_currentAssets.value, _expandedCategory.value, currency, usdRate, eurRate)
            }
            .conflate()
            .flowOn(Dispatchers.Default)
            .collect()

        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeAssets() {
        val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        viewModelScope.launch {
            // İlk 5 Flow'u combine ediyoruz
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
                _expandedCategory,
                _homeCurrency,
                _usdRate,
                _eurRate
            ) { assets, expanded, currency, usdRate, eurRate ->
                _currentAssets.value = assets
                calculateStats(assets, expanded, currency, usdRate, eurRate)
            }
            .conflate()
            .flowOn(Dispatchers.Default)
            .collect() // <--- SADECE İLK 5'İNİ DİNLİYORUZ
        }

        // SyncStatus'u (6. Flow'u) Ayrı Bir Yerde Dinliyoruz
        viewModelScope.launch {
            priceSyncManager.syncStatus.collect { syncStatus ->
                val timeStr = if (syncStatus.lastUpdate > 0) dateFormat.format(java.util.Date(syncStatus.lastUpdate)) else null
                _uiState.update { it.copy(
                    lastUpdated = timeStr,
                    isOffline = syncStatus.isOffline
                )}
            }
        }
    }


    fun toggleCategoryExpansion(type: AssetType) {
        _expandedCategory.value = if (_expandedCategory.value == type) null else type
    }

    fun selectPortfolio(id: Long) {
        val name = if (id == -1L) context.getString(R.string.total_portfolios) else _uiState.value.portfolios.find { it.portfolio.id == id }?.portfolio?.name ?: ""
        _selectedPortfolioId.value = id
        prefManager.setSelectedPortfolioId(id)
        _uiState.update { it.copy(selectedPortfolioId = id, selectedPortfolioName = name) }
    }

    fun selectNextPortfolio() {
        val portfolios = _uiState.value.portfolios
        if (portfolios.isEmpty()) return
        val currentIndex = if (_selectedPortfolioId.value == -1L) -1 else portfolios.indexOfFirst { it.portfolio.id == _selectedPortfolioId.value }
        val nextIndex = currentIndex + 1
        if (nextIndex >= portfolios.size) selectPortfolio(-1L) else selectPortfolio(portfolios[nextIndex].portfolio.id)
    }

    fun selectPrevPortfolio() {
        val portfolios = _uiState.value.portfolios
        if (portfolios.isEmpty()) return
        val currentIndex = if (_selectedPortfolioId.value == -1L) -1 else portfolios.indexOfFirst { it.portfolio.id == _selectedPortfolioId.value }
        if (currentIndex == -1) selectPortfolio(portfolios.last().portfolio.id)
        else if (currentIndex == 0) selectPortfolio(-1L)
        else selectPortfolio(portfolios[currentIndex - 1].portfolio.id)
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

    private fun calculateStats(
        assets: List<Asset>, 
        expandedCategory: AssetType?, 
        currency: String = prefManager.getHomeCurrency(),
        usdRate: BigDecimal? = _usdRate.value,
        eurRate: BigDecimal? = _eurRate.value,
        resolveContext: Context = context
    ) {
        // Build correct locale context for labels even if flow triggers this from background
        val langContext = com.yusufulgen.cuzdan.util.LocaleHelper.setLocale(context, prefManager.getLanguage())
        var exchangeRate = BigDecimal.ONE

        if (currency == "USD") exchangeRate = usdRate ?: BigDecimal("32.5")
        else if (currency == "EUR") exchangeRate = eurRate ?: BigDecimal("35.2")

        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val isBistClosedForToday = isWeekend || (hr < 9) || (hr == 9 && min < 55)

        var totalBalanceBase = BigDecimal.ZERO
        var totalCostBase = BigDecimal.ZERO
        var totalDailyProfitBase = BigDecimal.ZERO
        var totalPrevDayValueBase = BigDecimal.ZERO

        val isBistClosed = isBistClosedForToday
        assets.forEach { asset ->
            val assetRate = when(asset.currency){"USD"->usdRate?:"32.5".toBigDecimal() "EUR"->eurRate?:"35.2".toBigDecimal() else->BigDecimal.ONE}
            val costRate = when(asset.buyCurrency){"USD"->usdRate?:"32.5".toBigDecimal() "EUR"->eurRate?:"35.2".toBigDecimal() else->BigDecimal.ONE}
            
            totalBalanceBase = totalBalanceBase.add(asset.amount.multiply(asset.currentPrice).multiply(assetRate))
            totalCostBase = totalCostBase.add(asset.amount.multiply(asset.averageBuyPrice).multiply(costRate))
            
            val values = calculateAssetDailyValues(asset, usdRate, eurRate, isBistClosed)
            totalDailyProfitBase = totalDailyProfitBase.add(values.dailyProfitBase)
            totalPrevDayValueBase = totalPrevDayValueBase.add(values.prevValueBase)
        }

        // 1. Calculate Idle Cash in Base Currency (TRY)
        val currentId = _selectedPortfolioId.value
        val portfolios = _uiState.value.portfolios
        
        val finalTotalBalance: BigDecimal
        val dailyChangeAbs: BigDecimal
        val dailyChangePerc: BigDecimal
        val idleCashTry: BigDecimal
        val idleCashConv: BigDecimal

        if (currentId == -1L) {
            // "Portföyler Toplamı" mode
            val includedPortfolios = portfolios.filter { it.portfolio.isIncludedInTotal }
            finalTotalBalance = includedPortfolios.sumOf { it.balance }
            dailyChangeAbs = includedPortfolios.sumOf { it.dailyChangeAbs }
            
            val totalIdleCashTry = includedPortfolios.sumOf { p ->
                // Recalculate idle cash correctly: Investment - Cost
                var pCostBase = BigDecimal.ZERO
                assets.filter { it.portfolioId == p.portfolio.id }.forEach { a ->
                    val costRate = when(a.buyCurrency){"USD"->usdRate?:"32.5".toBigDecimal() "EUR"->eurRate?:"35.2".toBigDecimal() else->BigDecimal.ONE}
                    pCostBase = pCostBase.add(a.amount.multiply(a.averageBuyPrice).multiply(costRate))
                }
                (p.portfolio.depositedAmount - pCostBase).coerceAtLeast(BigDecimal.ZERO)
            }
            
            val totalPrevValueTry = totalPrevDayValueBase.add(totalIdleCashTry)
            dailyChangePerc = if (totalPrevValueTry > BigDecimal.ZERO) {
                totalDailyProfitBase.multiply(BigDecimal("100")).divide(totalPrevValueTry, 2, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO
            
            idleCashTry = totalIdleCashTry
            idleCashConv = idleCashTry.divide(exchangeRate, 2, RoundingMode.HALF_UP)
        } else {
            // Single portfolio mode
            val p = portfolios.find { it.portfolio.id == currentId }
            finalTotalBalance = p?.balance ?: BigDecimal.ZERO
            dailyChangeAbs = p?.dailyChangeAbs ?: BigDecimal.ZERO
            dailyChangePerc = p?.dailyChangePerc ?: BigDecimal.ZERO
            idleCashTry = p?.portfolio?.depositedAmount ?: BigDecimal.ZERO
            idleCashConv = idleCashTry.divide(exchangeRate, 2, RoundingMode.HALF_UP)
        }

        // Record a history snapshot if none exists for today (for tomorrow's reference)
        viewModelScope.launch {
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            if (currentId != -1L && finalTotalBalance > BigDecimal.ZERO) {
                // Get snapshots taken TODAY
                val snapshotToday = assetRepository.getLatestHistoryBefore(currentId, todayStart + 86400000)?.let { 
                    it.date >= todayStart 
                } ?: false
                
                if (!snapshotToday) {
                    // Record FIRST snapshot of the day (Total Value in TRY)
                    assetRepository.recordPortfolioSnapshot(currentId, finalTotalBalance.multiply(exchangeRate), "TRY")
                }
            }
        }

        val categorySummaries = assets.groupBy { it.assetType }.map { (type, typeAssets) ->
            var catValueBase = BigDecimal.ZERO
            var catCostBase = BigDecimal.ZERO
            var catDailyProfitBase = BigDecimal.ZERO
            var catPrevDayValueBase = BigDecimal.ZERO
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
                val assetValue = asset.amount.multiply(asset.currentPrice).multiply(assetRate)
                catValueBase = catValueBase.add(assetValue)
                catCostBase = catCostBase.add(asset.amount.multiply(asset.averageBuyPrice).multiply(costRate))
                
                // Daily P/L: prevPrice = currentPrice / (1 + dailyChangePercentage/100)
                if (asset.amount > BigDecimal.ZERO) {
                    var pct = asset.dailyChangePercentage
                    if (asset.assetType == AssetType.BIST && isBistClosedForToday) {
                        pct = BigDecimal.ZERO
                    }


                    val denom = BigDecimal.ONE.add(pct.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))
                    if (denom.compareTo(BigDecimal.ZERO) != 0) {
                        val prevPrice = asset.currentPrice.divide(denom, 12, RoundingMode.HALF_UP)
                        val diff = asset.currentPrice.subtract(prevPrice)
                        catDailyProfitBase = catDailyProfitBase.add(asset.amount.multiply(diff).multiply(assetRate))
                        catPrevDayValueBase = catPrevDayValueBase.add(asset.amount.multiply(prevPrice).multiply(assetRate))
                    }
                }
            }
            val convCatValue = catValueBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
            val convCatDailyProfit = catDailyProfitBase.divide(exchangeRate, 2, RoundingMode.HALF_UP)
            
            // Weighted daily change % for the category
            val catDailyPerc = if (catPrevDayValueBase > BigDecimal.ZERO) {
                catDailyProfitBase.multiply(BigDecimal("100")).divide(catPrevDayValueBase, 2, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO

            val sortedAssets = typeAssets.sortedByDescending { asset ->
                val assetRate = when (asset.currency) {
                    "USD" -> usdRate ?: BigDecimal("32.5")
                    "EUR" -> eurRate ?: BigDecimal("35.2")
                    else -> BigDecimal.ONE
                }
                asset.amount.multiply(asset.currentPrice).multiply(assetRate)
            }

            val convertedAssets = sortedAssets.map { asset ->
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
                if (com.yusufulgen.cuzdan.util.MarketStatusUtils.isMarketClosedToday(asset.assetType)) {
                    adjustedDailyPerc = BigDecimal.ZERO
                }

                asset.copy(
                    currentPrice = asset.currentPrice.multiply(finalPriceRate),
                    averageBuyPrice = asset.averageBuyPrice.multiply(finalCostRate),
                    currency = currency,
                    dailyChangePercentage = adjustedDailyPerc
                )
            }

             WalletCategorySummary(
                type = type,
                title = getLocalizedAssetTypeName(type, langContext),
                totalValue = convCatValue,
                totalProfitLoss = convCatDailyProfit,
                profitLossPerc = catDailyPerc,
                assets = convertedAssets,
                isExpanded = expandedCategory == type,
                iconRes = getCategoryIcon(type)
            )
        }.toMutableList()

        // Filter out empty categories AND categories where all assets have zero amount (fully sold)
        categorySummaries.removeAll { it.assets.isEmpty() || it.assets.all { a -> a.amount.compareTo(BigDecimal.ZERO) == 0 } }

        // Inject 'Nakit' (Idle Cash) category as the first item if there is idle cash
        if (idleCashConv > BigDecimal.ZERO) {
            val existingNakitIndex = categorySummaries.indexOfFirst { it.type == AssetType.NAKIT }
            if (existingNakitIndex != -1) {
                val existing = categorySummaries[existingNakitIndex]
                categorySummaries[existingNakitIndex] = existing.copy(
                    totalValue = existing.totalValue.add(idleCashConv),
                    totalProfitLoss = existing.totalProfitLoss,
                    profitLossPerc = existing.profitLossPerc
                )
            } else {
                categorySummaries.add(WalletCategorySummary(
                    type = AssetType.NAKIT,
                    title = getLocalizedAssetTypeName(AssetType.NAKIT, langContext),
                    totalValue = idleCashConv,
                    totalProfitLoss = BigDecimal.ZERO,
                    profitLossPerc = BigDecimal.ZERO,
                    assets = emptyList(),
                    isExpanded = false,
                    iconRes = R.drawable.nakit
                ))
            }
        }

        // Final Category Sorting: Sort all categories (including injected ones) by total value descending
        categorySummaries.sortByDescending { it.totalValue }

        val segments = mutableListOf<DonutChartView.Segment>()
        var centerLabel = langContext.getString(com.yusufulgen.cuzdan.R.string.label_distribution)
        var centerPercent = "%100"

        if (expandedCategory != null) {
            val catAssets = assets.filter { it.assetType == expandedCategory }
            val catTotalRaw = catAssets.sumOf { it.amount.multiply(it.currentPrice) }
            
            // If Nakit is expanded, we need to include idle cash in the distribution
            val catTotal = if (expandedCategory == AssetType.NAKIT) catTotalRaw.add(idleCashTry) else catTotalRaw

            centerLabel = getLocalizedAssetTypeName(expandedCategory, langContext)
            
            if (catTotal > BigDecimal.ZERO) {
                // Sort category assets by value for the chart distribution too
                val sortedCatAssets = catAssets.sortedByDescending { it.amount.multiply(it.currentPrice) }

                sortedCatAssets.forEachIndexed { index, asset ->
                    val assetValue = asset.amount.multiply(asset.currentPrice)
                    val weight = assetValue.divide(catTotal, 4, RoundingMode.HALF_UP).toFloat()
                    
                    // Clean symbol for display in the chart
                    val cleanLabel = asset.symbol.uppercase()
                        .replace(".IS", "")
                        .replace("USDT", "")
                        .replace("TRY=X", "")
                        .replace("USDTRY", "USD")
                        .replace("EURTRY", "EUR")
                        .replace("GBPTRY", "GBP")
                    
                    segments.add(DonutChartView.Segment(weight, getAssetColor(index), cleanLabel))
                }
                // If it's Nakit, add the idle cash portion as a segment (Idle cash is also a part of Nakit category)
                if (expandedCategory == AssetType.NAKIT && idleCashTry > BigDecimal.ZERO) {
                    val weight = idleCashTry.divide(catTotal, 4, RoundingMode.HALF_UP).toFloat()
                    // Add at the correct sorted position for consistency if we wanted, but let's just add it
                    segments.add(DonutChartView.Segment(weight, getAssetColor(sortedCatAssets.size), langContext.getString(R.string.asset_type_cash)))
                    // Re-sort segments to ensure highest value is first in expanded view too
                    segments.sortByDescending { it.percentage }
                }
            }
        } else {
            val totalValueWithNakitTry = totalBalanceBase.add(idleCashTry)
            if (totalValueWithNakitTry > BigDecimal.ZERO) {
                categorySummaries.forEach { summary ->
                    val weight = (summary.totalValue.multiply(exchangeRate)).divide(totalValueWithNakitTry, 4, RoundingMode.HALF_UP).toFloat()
                    segments.add(DonutChartView.Segment(weight, getCategoryColor(summary.type), summary.title))
                }
            }
        }

        _uiState.update { 
            it.copy(
                totalBalance = finalTotalBalance,
                cashBalance = idleCashConv,
                dailyChangeAbs = dailyChangeAbs,
                dailyChangePerc = dailyChangePerc,
                categorySummaries = categorySummaries,
                donutSegments = segments,
                donutCenterLabel = centerLabel,
                donutCenterPercent = centerPercent,
                currency = currency
            )
        }
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

    private fun getCategoryIcon(type: AssetType): Int {
        return when(type) {
            AssetType.KRIPTO -> R.drawable.kripto
            AssetType.BIST -> R.drawable.borsa
            AssetType.DOVIZ -> R.drawable.doviz
            AssetType.EMTIA -> R.drawable.emtia
            AssetType.NAKIT -> R.drawable.nakit
            AssetType.FON -> R.drawable.fon
        }
    }

    private fun getCategoryColor(type: AssetType): Int {
        return when(type) {
            AssetType.KRIPTO -> 0xFF8B5CF6.toInt() // Violet
            AssetType.BIST -> 0xFFDDD6FE.toInt()   // Lavender
            AssetType.DOVIZ -> 0xFF93C5FD.toInt()  // Blue
            AssetType.EMTIA -> 0xFF818CF8.toInt()  // Indigo
            AssetType.NAKIT -> 0xFFF472B6.toInt()  // Pink
            AssetType.FON -> 0xFFC084FC.toInt()    // Bright Purple
        }
    }

    private fun getAssetColor(index: Int): Int {
        val colors = listOf(0xFF8B5CF6, 0xFFDDD6FE, 0xFF93C5FD, 0xFF818CF8, 0xFFF472B6, 0xFFC084FC)
        return colors[index % colors.size].toInt()
    }


    suspend fun getPortfolioById(id: Long) = portfolioRepository.getPortfolioById(id)
    suspend fun updatePortfolio(id: Long, name: String, isIncluded: Boolean) {
        portfolioRepository.getPortfolioById(id)?.let {
            portfolioRepository.updatePortfolio(it.copy(name = name, isIncludedInTotal = isIncluded))
        }
    }
    suspend fun deletePortfolio(id: Long) {
        portfolioRepository.getPortfolioById(id)?.let {
            portfolioRepository.deletePortfolio(it)
            if (_selectedPortfolioId.value == id) selectPortfolio(-1L)
        }
    }

    /**
     * Seçili portföye sermaye yatır veya çek.
     * amountInSelectedCurrency: Kullanıcının girdiği tutar (seçilen dövizde)
     * currency: "TL", "USD" veya "EUR"
     * isWithdraw: true ise çekme işlemi
     */
    fun depositOrWithdraw(amountInSelectedCurrency: BigDecimal, currency: String, isWithdraw: Boolean) {
        val portfolioId = _selectedPortfolioId.value
        if (portfolioId == -1L) return // Tüm portföyler modunda işlem yapılamaz
        viewModelScope.launch {
            val usdRate = _usdRate.value ?: BigDecimal("32.5")
            val eurRate = _eurRate.value ?: BigDecimal("35.2")
            // Para birimini TRY'ye çevir
            val amountInTry = when (currency) {
                "USD" -> amountInSelectedCurrency.multiply(usdRate)
                "EUR" -> amountInSelectedCurrency.multiply(eurRate)
                else -> amountInSelectedCurrency // TL
            }
            val signedAmount = if (isWithdraw) amountInTry.negate() else amountInTry
            portfolioRepository.updateDepositedAmount(portfolioId, signedAmount)
        }
    }

    fun setCurrency(currency: String) {
        prefManager.setHomeCurrency(currency)
        _homeCurrency.value = currency
        _uiState.update { it.copy(currency = currency) }
    }

    fun resetState() {
        _selectedPortfolioId.value = -1L
        _homeCurrency.value = prefManager.getHomeCurrency()
        _expandedCategory.value = null
        _uiState.update { WalletUiState(currency = _homeCurrency.value) }
    }


    fun refreshLocalization(activityContext: Context) {
        val currentId = _selectedPortfolioId.value
        val portfolios = _uiState.value.portfolios
        val name = if (currentId == -1L) activityContext.getString(R.string.total_portfolios) 
                   else portfolios.find { it.portfolio.id == currentId }?.portfolio?.name ?: ""
        
        _uiState.update { it.copy(selectedPortfolioName = name) }
        viewModelScope.launch(Dispatchers.Default) {
            calculateStats(_currentAssets.value, _expandedCategory.value, _homeCurrency.value, _usdRate.value, _eurRate.value, activityContext)
        }
    }

    private fun calculateAssetDailyValues(asset: Asset, usdRate: BigDecimal?, eurRate: BigDecimal?, isBistClosed: Boolean): AssetDailyValues {
        val assetRate = when (asset.currency) {
            "USD" -> usdRate ?: BigDecimal("32.5")
            "EUR" -> eurRate ?: BigDecimal("35.2")
            else -> BigDecimal.ONE
        }
        
        if (asset.amount <= BigDecimal.ZERO) return AssetDailyValues(BigDecimal.ZERO, BigDecimal.ZERO)
        
        var pct = asset.dailyChangePercentage
        if (asset.assetType == AssetType.BIST && isBistClosed) {
            pct = BigDecimal.ZERO
        }
        
        val denom = BigDecimal.ONE.add(pct.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
        if (denom.compareTo(BigDecimal.ZERO) == 0) return AssetDailyValues(BigDecimal.ZERO, asset.amount.multiply(asset.currentPrice).multiply(assetRate))
        
        val prevPrice = asset.currentPrice.divide(denom, 12, RoundingMode.HALF_UP)
        val diff = asset.currentPrice.subtract(prevPrice)
        
        return AssetDailyValues(
            dailyProfitBase = asset.amount.multiply(diff).multiply(assetRate),
            prevValueBase = asset.amount.multiply(prevPrice).multiply(assetRate)
        )
    }
}