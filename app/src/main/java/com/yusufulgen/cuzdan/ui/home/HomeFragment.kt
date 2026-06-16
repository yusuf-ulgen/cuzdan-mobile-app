package com.yusufulgen.cuzdan.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import com.yusufulgen.cuzdan.data.local.entity.Asset
import androidx.recyclerview.widget.LinearLayoutManager
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.databinding.FragmentHomeBinding
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.yusufulgen.cuzdan.util.formatCurrency
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.yusufulgen.cuzdan.ui.currency.CurrencyBottomSheet
import com.yusufulgen.cuzdan.util.resolveCurrencySwitcherIcon

@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject
    lateinit var prefManager: PreferenceManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by activityViewModels()
    private lateinit var adapter: WalletCategoryAdapter
    
    private var isFirstLoad = true
    private var lastChartLabel: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        
        setupRecyclerView()
        setupUI()
        observeState()
        
        // Ensure localized strings are updated with current Activity context
        viewModel.refreshLocalization(requireContext())
        
        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = WalletCategoryAdapter(emptyList(), { category ->
            viewModel.toggleCategoryExpansion(category.type)
        }, { asset, _, _ ->
            val action = HomeFragmentDirections.actionNavigationHomeToAssetDetailFragment(
                symbol = asset.symbol,
                name = asset.name,
                assetType = asset.assetType.name,
                currency = asset.currency
            )
            findNavController().navigate(action)
        })
        binding.recyclerWalletCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerWalletCategories.adapter = adapter
    }

    private fun setupUI() {
        binding.btnPrevPortfolio.setOnClickListener {
            viewModel.selectPrevPortfolio()
        }
        binding.btnNextPortfolio.setOnClickListener {
            viewModel.selectNextPortfolio()
        }
        binding.layoutPortfolioSelector.setOnClickListener {
            showPortfolioManagement()
        }
        binding.btnPrivacyToggle.setOnClickListener {
            val isEnabled = prefManager.isPrivacyModeEnabled()
            prefManager.setPrivacyModeEnabled(!isEnabled)
            updateUI(viewModel.uiState.value)
        }
        binding.btnCurrencySwitcher.setOnClickListener {
            showCurrencySwitcher()
        }
        binding.btnAddPortfolio.setOnClickListener {
            com.yusufulgen.cuzdan.util.HapticManager.tap(it)
            AddPortfolioDialogFragment().show(childFragmentManager, "AddPortfolio")
        }
        binding.textPortfolioName.setOnClickListener {
            com.yusufulgen.cuzdan.util.HapticManager.tap(it)
            PortfolioManagementBottomSheet().show(childFragmentManager, "PortfolioManagement")
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

    private fun updateUI(state: WalletUiState) {
        val isPrivacyEnabled = prefManager.isPrivacyModeEnabled()
        
        binding.textPortfolioName.text = state.selectedPortfolioName

        binding.btnPrivacyToggle.setImageResource(

            if (isPrivacyEnabled) R.drawable.ic_eye_off else R.drawable.ic_eye_on
        )



        val shouldAnimate = isFirstLoad || (lastChartLabel != null && lastChartLabel != state.donutCenterLabel)
        
        if (shouldAnimate && state.donutSegments.isNotEmpty()) {
            binding.donutChart.animateChart()
            isFirstLoad = false
        }
        
        binding.donutChart.setSegments(state.donutSegments)
        lastChartLabel = state.donutCenterLabel

        binding.donutChart.setLabelColor(
            requireContext().getColor(if (prefManager.getThemeMode() == "light") R.color.text_primary_light else R.color.white)
        )


        if (isPrivacyEnabled) {

            binding.textTotalBalance.text = "**** ${state.currency}"
            binding.textDailyChangePerc.text = "****"
        } else {

            binding.textTotalBalance.text = state.totalBalance.formatCurrency(state.currency)
            val changeStr = state.dailyChangeAbs.formatCurrency(state.currency)
            val percStr = String.format("%%%+.2f", state.dailyChangePerc)
            binding.textDailyChangePerc.text = "$changeStr ($percStr)"
            
            val isLight = prefManager.getThemeMode() == "light"
            val isNeutral = state.dailyChangeAbs.abs() < java.math.BigDecimal("0.01")
            val (textColor, bgColor) = when {
                isNeutral -> (if (isLight) R.color.text_secondary_light else R.color.text_secondary) to (if (isLight) R.color.divider_light else R.color.surface_glass_strong)
                state.dailyChangeAbs > java.math.BigDecimal.ZERO -> R.color.pill_green_text to R.color.pill_green_bg
                else -> R.color.pill_red_text to R.color.pill_red_bg
            }
            
            binding.textTotalBalance.setTextColor(requireContext().getColor(if (isLight) R.color.text_primary_light else R.color.white))
            binding.textDailyChangePerc.setTextColor(requireContext().getColor(textColor))
            binding.layoutDailyChangePill.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(bgColor))
        }


        
        // Currency icon update
        binding.btnCurrencySwitcher.setImageResource(
            requireContext().resolveCurrencySwitcherIcon(state.currency)
        )
        
        adapter.setItemsWithPrivacy(state.categorySummaries, isPrivacyEnabled, state.currency)
    }


    private fun showPortfolioManagement() {
        val bottomSheet = PortfolioManagementBottomSheet()
        bottomSheet.show(childFragmentManager, "PortfolioManagement")
    }

    private fun showCurrencySwitcher() {
        val bottomSheet = CurrencyBottomSheet.newInstance(CurrencyBottomSheet.SOURCE_HOME)
        bottomSheet.show(childFragmentManager, "CurrencyBottomSheet")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}