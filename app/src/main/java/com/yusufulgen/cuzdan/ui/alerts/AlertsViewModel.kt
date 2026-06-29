package com.yusufulgen.cuzdan.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yusufulgen.cuzdan.data.local.entity.PriceAlert
import com.yusufulgen.cuzdan.data.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class AlertsUiState(
    val active: List<PriceAlert> = emptyList(),
    val triggered: List<PriceAlert> = emptyList()
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repository: AssetRepository
) : ViewModel() {
    val uiState: StateFlow<AlertsUiState> =
        repository.getAllPriceAlerts()
            .map { all ->
                AlertsUiState(
                    active = all.filter { it.isEnabled && !it.isTriggered },
                    triggered = all.filter { it.isTriggered }
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun deleteAlert(alert: PriceAlert) {
        viewModelScope.launch {
            repository.deletePriceAlert(alert)
        }
    }

    fun updateAlert(alert: PriceAlert) {
        viewModelScope.launch {
            repository.updatePriceAlert(alert)
        }
    }

    suspend fun getCurrentPrice(symbol: String): BigDecimal? {
        return repository.getLatestPrice(symbol).first()
    }
}

