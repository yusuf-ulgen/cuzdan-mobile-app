package com.yusufulgen.cuzdan.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.yusufulgen.cuzdan.util.formatCurrency
import com.yusufulgen.cuzdan.util.resolveCurrencySwitcherIcon
import com.yusufulgen.cuzdan.databinding.FragmentReportsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject
import com.yusufulgen.cuzdan.ui.currency.CurrencyBottomSheet

@AndroidEntryPoint
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReportsViewModel by activityViewModels()
    
    @Inject
    lateinit var prefManager: PreferenceManager
    
    private var isHidden = false
    private lateinit var adapter: ReportCategoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        
        isHidden = prefManager.isPrivacyModeEnabled()
        
        setupRecyclerView()
        setupUI()
        observeState()
        
        // Home'daki portföy değişikliklerini senkronize et
        viewModel.syncPortfolioSelection()
        // Ensure localized strings are updated
        viewModel.refreshLocalization(requireContext())
        
        return binding.root
    }

    private fun setupUI() {
        binding.btnPrivacyToggle.setOnClickListener {
            isHidden = !isHidden
            prefManager.setPrivacyModeEnabled(isHidden)
            updateHideShowUI(viewModel.uiState.value)
        }

        binding.layoutDailyChange.setOnClickListener {
            findNavController().navigate(R.id.navigation_profit_loss_chart)
        }

        binding.btnPrevPortfolio.setOnClickListener { 
            viewModel.selectPrevPortfolio()
        }
        
        binding.btnNextPortfolio.setOnClickListener { 
            viewModel.selectNextPortfolio()
        }

        binding.btnCurrencySwitcher.setOnClickListener {
            showCurrencySwitcher()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: ReportsUiState) {
        val currentId = prefManager.getSelectedPortfolioId()
        val portfolios = state.portfolios
        
        binding.textPortfolioName.text = if (currentId == -1L) {
            getString(R.string.total_portfolios)
        } else {
            portfolios.find { it.id == currentId }?.name ?: ""
        }

        updateHideShowUI(state)
        adapter.setCurrency(state.currency)
        adapter.setItems(state.categories)
    }

    private fun updateHideShowUI(state: ReportsUiState) {
        if (isHidden) {
            binding.btnPrivacyToggle.setImageResource(R.drawable.ic_eye_off)
            binding.textTotalAmount.text = "***** ${state.currency}"
            binding.textDailyChangeAbs.text = "*****"
            binding.textDailyChangePerc.text = "*****"
        } else {
            val isLight = prefManager.getThemeMode() == "light"
            val isNeutralDaily = state.totalProfitLoss.abs() < java.math.BigDecimal("0.01")
            val dailyColor = when {
                isNeutralDaily -> if (isLight) R.color.text_secondary_light else R.color.text_secondary
                state.totalProfitLoss > java.math.BigDecimal.ZERO -> R.color.accent_green
                else -> R.color.accent_red
            }
            val dailyColorInt = requireContext().getColor(dailyColor)

            val isNeutralTotal = state.totalValue.abs() < java.math.BigDecimal("0.01")
            val totalColor = when {
                isNeutralTotal -> if (isLight) R.color.text_secondary_light else R.color.text_secondary
                state.totalValue > java.math.BigDecimal.ZERO -> R.color.accent_green
                else -> R.color.accent_red
            }
            
            binding.textTotalAmount.setTextColor(requireContext().getColor(totalColor))
            binding.textDailyChangeAbs.setTextColor(dailyColorInt)
            binding.textDailyChangePerc.setTextColor(dailyColorInt)
            
            binding.textTotalAmount.text = state.totalValue.formatCurrency(state.currency, showSign = true)
            binding.textDailyChangeAbs.text = state.totalProfitLoss.formatCurrency(state.currency, showSign = true)
            binding.textDailyChangePerc.text = String.format("%%%+.1f", state.totalProfitPerc)
            
            // Update daily change icon
            if (isNeutralDaily) {
                binding.imageDailyChangeArrow.visibility = View.GONE
            } else {
                binding.imageDailyChangeArrow.visibility = View.VISIBLE
                binding.imageDailyChangeArrow.setImageResource(R.drawable.ic_arrow_drop_down)
                binding.imageDailyChangeArrow.rotation = if (state.totalProfitLoss > java.math.BigDecimal.ZERO) 180f else 0f
                binding.imageDailyChangeArrow.imageTintList = android.content.res.ColorStateList.valueOf(dailyColorInt)
            }
        }
        
        // Currency icon update
        binding.btnCurrencySwitcher.setImageResource(
            requireContext().resolveCurrencySwitcherIcon(state.currency)
        )
        
        if (::adapter.isInitialized) {
            adapter.setPrivacyEnabled(isHidden)
        }

        // Sync Status and Offline Indicator
        binding.textLastUpdated.text = state.lastUpdated ?: ""
        binding.textLastUpdated.visibility = if (state.lastUpdated != null) View.VISIBLE else View.GONE
        binding.textOfflineIndicator.visibility = if (state.isOffline) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        adapter = ReportCategoryAdapter(emptyList(), isHidden) { _ -> }
        binding.recyclerReportCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerReportCategories.adapter = adapter
    }

    private fun showCurrencySwitcher() {
        val bottomSheet = com.yusufulgen.cuzdan.ui.currency.CurrencyBottomSheet.newInstance(com.yusufulgen.cuzdan.ui.currency.CurrencyBottomSheet.SOURCE_REPORTS)
        bottomSheet.show(childFragmentManager, "CurrencyBottomSheet")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
