package com.yusufulgen.cuzdan.ui.markets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yusufulgen.cuzdan.data.local.entity.Asset
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.PriceAlert
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import com.yusufulgen.cuzdan.data.repository.PortfolioRepository
import com.yusufulgen.cuzdan.util.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

enum class TransactionType { BUY, SELL }

data class AssetDetailUiState(
    val symbol: String = "",
    val name: String = "",
    val assetType: AssetType = AssetType.BIST,
    val currentPrice: BigDecimal = BigDecimal.ZERO,
    val dailyChangePercentage: BigDecimal = BigDecimal.ZERO,
    val currency: String = "TRY",
    val displayCurrency: String = "TRY",
    val history: List<Pair<Long, Double>> = emptyList(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val averageBuyPrice: BigDecimal = BigDecimal.ZERO,
    val buyCurrency: String = "TRY",
    val portfolioName: String = "Ana Portföy",
    val transactionType: TransactionType = TransactionType.BUY,
    val selectedRange: String = "1d",
    val selectedInterval: String = "1m"
)


@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    private val repository: AssetRepository,
    private val portfolioRepository: PortfolioRepository,
    private val prefManager: PreferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssetDetailUiState())
    val uiState: StateFlow<AssetDetailUiState> = _uiState.asStateFlow()

    fun init(symbol: String, name: String, typeString: String, currency: String = "TRY") {
        if (_uiState.value.symbol.isNotEmpty()) return 
        
        val type = try { AssetType.valueOf(typeString) } catch (e: Exception) { AssetType.BIST }
        val displayCurrency = when(type) {
            AssetType.KRIPTO -> prefManager.getCryptoCurrency()
            AssetType.EMTIA -> prefManager.getCryptoCurrency()
            else -> currency
        }

        _uiState.update { it.copy(
            symbol = symbol, 
            name = name, 
            assetType = type, 
            currency = currency, 
            displayCurrency = displayCurrency,
            selectedRange = "1d",
            selectedInterval = "1m"
        ) }
        loadHistory(symbol, "1d", "1m")
        observeCurrentPrice(symbol)
        loadExistingAsset(symbol)
        observeUsdRate()
    }

    private var usdRate: BigDecimal = BigDecimal("44.52")

    private fun observeUsdRate() {
        viewModelScope.launch {
            repository.getLatestPrice("USDTRY=X").collect { rate ->
                if (rate != null && rate > BigDecimal.ZERO) {
                    usdRate = rate
                    // Refresh display if currency is TL
                    if (_uiState.value.displayCurrency == "TL") {
                        refreshDisplayPrices()
                    }
                } else {
                    // Fallback: fetch directly once if DB isn't ready yet
                    repository.getYahooPriceOnce("USDTRY=X")?.let { fetched ->
                        if (fetched > BigDecimal.ZERO) {
                            usdRate = fetched
                            if (_uiState.value.displayCurrency == "TL") {
                                refreshDisplayPrices()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refreshDisplayPrices() {
        // Trigger a refresh of history and current price based on new rate
        val state = _uiState.value
        loadHistory(state.symbol, state.selectedRange, state.selectedInterval)
    }

    private fun loadExistingAsset(symbol: String) {
        viewModelScope.launch {
            val portfolioId = prefManager.getSelectedPortfolioId()
            val portfolio = if (portfolioId != -1L) portfolioRepository.getPortfolioById(portfolioId) else null
            val existing = if (portfolioId != -1L) repository.getAssetBySymbolAndPortfolioId(symbol, portfolioId) else null
            
            _uiState.update { it.copy(
                currentAmount = existing?.amount ?: BigDecimal.ZERO,
                averageBuyPrice = existing?.averageBuyPrice ?: BigDecimal.ZERO,
                buyCurrency = existing?.buyCurrency ?: "TRY",
                portfolioName = portfolio?.name ?: "—"
            ) }
        }
    }

    fun updateRange(rangeInput: String) {
        val range = if (rangeInput == "1w") "5d" else rangeInput
        val interval = when (range) {
            "1d" -> "1m"
            "5d" -> "15m"
            "1mo" -> "1h"
            "1y" -> "1d"
            else -> "1d"
        }
        _uiState.update { it.copy(selectedRange = range, selectedInterval = interval) }
        loadHistory(_uiState.value.symbol, range, interval)
    }


    private fun loadHistory(symbol: String, range: String = "1d", interval: String = "1m") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val assetType = _uiState.value.assetType
            val history = repository.getAssetHistory(symbol, assetType, range, interval)
            
            val convertedHistory = if (_uiState.value.displayCurrency == "TL" && (_uiState.value.currency == "USD" || _uiState.value.symbol.endsWith("USDT") || _uiState.value.symbol.endsWith("=F"))) {
                history.map { it.first to (it.second * usdRate.toDouble()) }
            } else if (_uiState.value.displayCurrency == "USD" && (_uiState.value.currency == "TRY" || _uiState.value.currency == "TL")) {
                if (usdRate.toDouble() > 0.0) history.map { it.first to (it.second / usdRate.toDouble()) } else history
            } else history

            _uiState.update { it.copy(history = convertedHistory, isLoading = false) }
        }
    }

    private fun observeCurrentPrice(symbol: String) {
        val TAG = "CUZDAN_LOG"
        viewModelScope.launch {
            repository.getMarketAssetBySymbolFlow(symbol).collect { marketAsset ->
                if (marketAsset != null) {
                    Log.d(TAG, "Detail observed price update for $symbol: Price=${marketAsset.currentPrice}, Change=${marketAsset.dailyChangePercentage}%")
                    
                    val displayPrice = if (_uiState.value.displayCurrency == "TL" && marketAsset.currency == "USD") {
                        marketAsset.currentPrice.multiply(usdRate)
                    } else if (_uiState.value.displayCurrency == "USD" && (marketAsset.currency == "TRY" || marketAsset.currency == "TL")) {
                        if (usdRate > BigDecimal.ZERO) marketAsset.currentPrice.divide(usdRate, 8, java.math.RoundingMode.HALF_UP) else marketAsset.currentPrice
                    } else marketAsset.currentPrice

                    _uiState.update { state ->
                        state.copy(
                            currentPrice = displayPrice,
                            dailyChangePercentage = marketAsset.dailyChangePercentage
                        )
                    }
                } else {
                    Log.w(TAG, "Detail observed NULL market asset for $symbol")
                }
            }
        }
    }

    fun setTransactionType(type: TransactionType) {
        // Clear any previous error (e.g. "yetersiz bakiye") when switching modes.
        _uiState.update { it.copy(transactionType = type, errorMessage = null) }
    }

    fun saveAsset(enteredAmount: BigDecimal, enteredCost: BigDecimal, typeString: String) {
        viewModelScope.launch {
            // Clear stale error state before processing a new action.
            _uiState.update { it.copy(errorMessage = null) }
            val state = _uiState.value
            val currentAmount = state.currentAmount
            val transactionType = state.transactionType
            
            val newAmount = if (transactionType == TransactionType.BUY) {
                currentAmount.add(enteredAmount)
            } else {
                if (currentAmount < enteredAmount) {
                    _uiState.update { it.copy(errorMessage = "Yetersiz bakiye! Mevcut: $currentAmount") }
                    return@launch
                }
                currentAmount.subtract(enteredAmount)
            }

            val portfolioId = prefManager.getSelectedPortfolioId()
            if (portfolioId == -1L) {
                _uiState.update { it.copy(errorMessage = "Önce portföy seçin") }
                return@launch
            }

            val assetType = try { AssetType.valueOf(typeString) } catch (e: Exception) { AssetType.BIST }
            val isCash = assetType == AssetType.NAKIT

            // Ortalama maliyet hesabı
            val newAvgCost = if (transactionType == TransactionType.BUY) {
                if (newAmount.compareTo(BigDecimal.ZERO) > 0) {
                    // Existing cost normalization: Convert existing cost to the display currency (which enteredCost is in)
                    val displayCurrency = state.displayCurrency
                    val buyCurrency = state.buyCurrency
                    
                    val existingCostInDisplayCurrency = if (buyCurrency != displayCurrency) {
                        when {
                            (buyCurrency == "TRY" || buyCurrency == "TL") && displayCurrency == "USD" -> {
                                if (usdRate > BigDecimal.ZERO) state.averageBuyPrice.divide(usdRate, 8, java.math.RoundingMode.HALF_UP) else state.averageBuyPrice
                            }
                            buyCurrency == "USD" && (displayCurrency == "TRY" || displayCurrency == "TL") -> {
                                state.averageBuyPrice.multiply(usdRate)
                            }
                            else -> state.averageBuyPrice
                        }
                    } else state.averageBuyPrice

                    val currentValue = currentAmount.multiply(existingCostInDisplayCurrency)
                    val newValue = enteredAmount.multiply(enteredCost)
                    val totalCost = currentValue.add(newValue)
                    totalCost.divide(newAmount, 8, java.math.RoundingMode.HALF_UP)
                } else enteredCost
            } else {
                state.averageBuyPrice // Satışta maliyet değişmez (maliyet para birimi de değişmez)
            }

            val finalCurrentPrice = if (isCash) BigDecimal.ONE else {
                // Save price in native currency
                if (state.displayCurrency == "TL" && state.currency == "USD") state.currentPrice.divide(usdRate, 8, java.math.RoundingMode.HALF_UP)
                else if (state.displayCurrency == "USD" && (state.currency == "TRY" || state.currency == "TL")) state.currentPrice.multiply(usdRate)
                else state.currentPrice
            }
            
            val finalBuyCurrency = state.displayCurrency.let { if (it == "TL") "TRY" else it }
            
            val asset = Asset(
                symbol = state.symbol,
                name = state.name,
                amount = newAmount,
                averageBuyPrice = newAvgCost,
                currentPrice = finalCurrentPrice,
                dailyChangePercentage = if (isCash) BigDecimal.ZERO else state.dailyChangePercentage,
                assetType = assetType,
                portfolioId = portfolioId,
                currency = if (isCash) "TRY" else state.currency,
                buyCurrency = finalBuyCurrency
            )

            repository.addAsset(asset)
            _uiState.update { it.copy(isSaved = true, currentAmount = newAmount, averageBuyPrice = newAvgCost) }
        }
    }

    fun deleteAsset() {
        viewModelScope.launch {
            val state = _uiState.value
            val portfolioId = prefManager.getSelectedPortfolioId().let { if (it == -1L) 1L else it }
            val asset = repository.getAssetBySymbolAndPortfolioId(state.symbol, portfolioId)
            if (asset != null) {
                repository.deleteAsset(asset)
            }
            _uiState.update { it.copy(isDeleted = true) }
        }
    }

    fun setPriceAlert(alert: PriceAlert) {
        viewModelScope.launch {
            repository.insertPriceAlert(alert)
        }
    }
}
