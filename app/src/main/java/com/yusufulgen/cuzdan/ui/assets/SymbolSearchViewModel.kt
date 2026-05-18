package com.yusufulgen.cuzdan.ui.assets

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.data.local.entity.Asset
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.math.BigDecimal
import javax.inject.Inject
import com.yusufulgen.cuzdan.util.PreferenceManager

data class SymbolSearchUiState(
    val results: List<MarketAsset> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currency: String = "TL",
    val isFavoritesOnly: Boolean = false
)

@HiltViewModel
class SymbolSearchViewModel @Inject constructor(
    private val repository: AssetRepository,
    private val prefManager: PreferenceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SymbolSearchUiState(currency = prefManager.getCryptoCurrency()))
    val uiState: StateFlow<SymbolSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var usdRate: BigDecimal = BigDecimal("44.52")

    init {
        viewModelScope.launch {
            // Keep USD/TRY rate fresh for converting crypto USD quotes to TL.
            repository.getLatestPrice("USDTRY=X").collectLatest { rate ->
                if (rate != null && rate > BigDecimal.ZERO) {
                    usdRate = rate
                } else {
                    // Fallback: fetch directly once if DB isn't ready yet.
                    repository.getYahooPriceOnce("USDTRY=X")?.let { fetched ->
                        if (fetched > BigDecimal.ZERO) usdRate = fetched
                    }
                }
            }
        }
    }

    fun loadInitialSymbols(type: AssetType) {
        val targetCurrency = if (type == AssetType.KRIPTO || type == AssetType.EMTIA) {
            prefManager.getCryptoCurrency()
        } else {
            "TL"
        }
        _uiState.update { it.copy(currency = targetCurrency, isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val marketAssets = repository.getMarketAssetsOnce(type)
                val shouldRefresh = marketAssets.isEmpty() || (type == AssetType.NAKIT && marketAssets.size <= 1)
                
                if (shouldRefresh) {
                    android.util.Log.d("CuzdanDebug", "LoadInitialSymbols: type=$type, current results size=${_uiState.value.results.size}")
                    repository.refreshMarketAssets(type).collect { resource ->
                        if (resource is com.yusufulgen.cuzdan.util.Resource.Success) {
                            val refreshedAssets = repository.getMarketAssetsOnce(type)
                            android.util.Log.d("CuzdanDebug", "Refresh Success: type=$type, new size=${refreshedAssets.size}")
                            val filteredAssets = filterByType(refreshedAssets, type)
                            val finalAssets = if (_uiState.value.isFavoritesOnly) filteredAssets.filter { it.isFavorite } else filteredAssets
                            _uiState.update { it.copy(results = transformAssets(finalAssets, type), isLoading = false) }
                        } else if (resource is com.yusufulgen.cuzdan.util.Resource.Error) {
                            android.util.Log.e("CuzdanDebug", "Refresh Error: ${resource.message}")
                            _uiState.update { it.copy(isLoading = false, error = resource.message) }
                        }
                    }
                } else {
                    val filteredAssets = filterByType(marketAssets, type)
                    android.util.Log.d("CuzdanDebug", "LoadInitialSymbols (Cache hit): type=$type, size=${filteredAssets.size}")
                    val finalAssets = if (_uiState.value.isFavoritesOnly) filteredAssets.filter { it.isFavorite } else filteredAssets
                    _uiState.update { it.copy(results = transformAssets(finalAssets, type), isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "${context.getString(R.string.error_loading)}: ${e.localizedMessage}") }
            }
        }
    }

    private fun filterByType(assets: List<MarketAsset>, type: AssetType): List<MarketAsset> {
        return if (type == AssetType.NAKIT) {
            assets.filter { it.symbol.equals("TRY", ignoreCase = true) || it.symbol.equals("TL", ignoreCase = true) }
        } else {
            assets
        }
    }

    fun search(query: String, type: AssetType) {
        searchJob?.cancel()
        if (query.isBlank()) {
            loadInitialSymbols(type)
            return
        }
        val targetCurrency = if (type == AssetType.KRIPTO || type == AssetType.EMTIA) {
            prefManager.getCryptoCurrency()
        } else {
            "TL"
        }
        searchJob = viewModelScope.launch {
            delay(500)
            _uiState.update { it.copy(currency = targetCurrency, isLoading = true) }
            
            val allAssets = repository.getMarketAssetsOnce(type)
            val queryKeywords = query.trim().split("\\s+".toRegex())
            
            val searchResults = allAssets.filter { asset ->
                val searchableText = (asset.name + " " + asset.symbol + " " + (asset.fullName ?: ""))
                    .lowercase(java.util.Locale("tr", "TR"))
                    .replace("ı", "i")
                    .replace("ğ", "g")
                    .replace("ü", "u")
                    .replace("ş", "s")
                    .replace("ö", "o")
                    .replace("ç", "c")
 
                queryKeywords.all { keyword ->
                    val normalizedKeyword = keyword.lowercase(java.util.Locale("tr", "TR"))
                        .replace("ı", "i")
                        .replace("ğ", "g")
                        .replace("ü", "u")
                        .replace("ş", "s")
                        .replace("ö", "o")
                        .replace("ç", "c")
                    searchableText.contains(normalizedKeyword)
                }
            }
            
            val finalAssets = if (_uiState.value.isFavoritesOnly) searchResults.filter { it.isFavorite } else searchResults
            _uiState.update { it.copy(results = transformAssets(finalAssets, type), isLoading = false) }
        }
    }

    fun toggleFavoritesOnly(type: AssetType) {
        _uiState.update { it.copy(isFavoritesOnly = !it.isFavoritesOnly) }
        loadInitialSymbols(type)
    }

    fun toggleFavorite(asset: MarketAsset, type: AssetType) {
        viewModelScope.launch {
            repository.toggleFavorite(asset.symbol, asset.assetType, !asset.isFavorite)
            // Local state'i güncelle (Re-load initial symbols en garantisi ama performans için manual map de olabilir)
            val updatedResults = _uiState.value.results.map {
                if (it.symbol == asset.symbol && it.assetType == asset.assetType) {
                    it.copy(isFavorite = !it.isFavorite)
                } else it
            }
            
            val filteredResults = if (_uiState.value.isFavoritesOnly) {
                updatedResults.filter { it.isFavorite }
            } else {
                updatedResults
            }
            
            _uiState.update { it.copy(results = filteredResults) }
        }
    }

    private fun transformAssets(assets: List<MarketAsset>, type: AssetType): List<MarketAsset> {
        val currency = _uiState.value.currency
        val prioritySymbols = listOf("BTC", "ETH", "USDT", "SOL", "BNB", "XRP", "USDC", "ADA", "DOGE", "AVAX", "SHIB", "DOT", "TRX", "LINK", "MATIC")
        val priorityEmtia = listOf("GRAM_ALTIN", "GRAM_GUMUS", "GOLD", "SILVER")

        val sortedAssets = if (type == AssetType.KRIPTO) {
            assets.sortedWith(compareByDescending<MarketAsset> { asset ->
                val cleanSym = asset.symbol.replace("USDT", "").replace("TRY", "")
                val priorityIndex = prioritySymbols.indexOf(cleanSym)
                if (priorityIndex != -1) 1000 - priorityIndex else 0
            }.thenBy { it.name })
        } else if (type == AssetType.EMTIA) {
            assets.sortedWith(compareByDescending<MarketAsset> { asset ->
                val priorityIndex = priorityEmtia.indexOf(asset.symbol)
                if (priorityIndex != -1) 1000 - priorityIndex else 0
            }.thenBy { it.name })
        } else {
            assets
        }

        val results = sortedAssets.map { asset ->
            if (currency == "TL" && asset.currency == "USD") {
                asset.copy(
                    currentPrice = asset.currentPrice.multiply(usdRate).setScale(2, java.math.RoundingMode.HALF_UP),
                    currency = "TRY"
                )
            } else if (currency == "USD" && asset.currency == "TRY" && asset.symbol != "TRY" && asset.symbol != "TL") {
                asset.copy(
                    currentPrice = if (usdRate > BigDecimal.ZERO) asset.currentPrice.divide(usdRate, 4, java.math.RoundingMode.HALF_UP) else asset.currentPrice,
                    currency = "USD"
                )
            } else {
                asset
            }
        }
        return results
    }

    fun toggleCurrency(type: AssetType) {
        val newCurrency = if (_uiState.value.currency == "TL") "USD" else "TL"
        prefManager.setCryptoCurrency(newCurrency)
        _uiState.update { it.copy(currency = newCurrency) }
        loadInitialSymbols(type)
    }

    fun saveAssetFromMarket(marketAsset: MarketAsset, portfolioId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val asset = Asset(
                symbol = marketAsset.symbol,
                name = marketAsset.name,
                amount = BigDecimal.ZERO,
                averageBuyPrice = BigDecimal.ZERO,
                currentPrice = marketAsset.currentPrice,
                dailyChangePercentage = marketAsset.dailyChangePercentage,
                assetType = marketAsset.assetType,
                portfolioId = portfolioId
            )
            repository.upsertAsset(asset)
            _uiState.update { it.copy(results = emptyList(), isLoading = false) }
        }
    }
}

