package com.yusufulgen.cuzdan.ui.assets

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yusufulgen.cuzdan.util.showToast
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.databinding.FragmentSymbolSearchBinding
import com.yusufulgen.cuzdan.ui.markets.MarketAdapter
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.yusufulgen.cuzdan.R
import androidx.navigation.fragment.FragmentNavigatorExtras
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset
import java.math.BigDecimal

@AndroidEntryPoint
class SymbolSearchFragment : Fragment() {

    private var _binding: FragmentSymbolSearchBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SymbolSearchViewModel by viewModels()
    private var assetType: String? = null
    private lateinit var adapter: MarketAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSymbolSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        assetType = arguments?.getString("assetType")
        val type = try { AssetType.valueOf(assetType ?: "BIST") } catch (e: Exception) { AssetType.BIST }

        if (type == AssetType.KRIPTO || type == AssetType.EMTIA) {
            binding.btnCurrencySwitcher.visibility = View.VISIBLE
            binding.btnCurrencySwitcher.setOnClickListener {
                viewModel.toggleCurrency(type)
            }
        }

        binding.btnFavorites.setOnClickListener {
            viewModel.toggleFavoritesOnly(type)
        }

        setupRecyclerView()
        setupSearch()
        observeViewModel()
        
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        
        viewModel.loadInitialSymbols(type)
        val localizedTypeName = getLocalizedAssetTypeName(type)
        binding.textTitle.text = getString(R.string.asset_title_template, localizedTypeName)

        // Set delay warning
        when (type) {
            AssetType.BIST -> {
                binding.textDelayWarning.text = getString(R.string.delay_warning_bist)
                binding.textDelayWarning.visibility = View.VISIBLE
            }
            AssetType.FON -> {
                binding.textDelayWarning.text = getString(R.string.delay_warning_fund)
                binding.textDelayWarning.visibility = View.VISIBLE
            }
            AssetType.DOVIZ, AssetType.EMTIA, AssetType.KRIPTO -> {
                binding.textDelayWarning.text = getString(R.string.delay_warning_general)
                binding.textDelayWarning.visibility = View.VISIBLE
            }
            else -> {
                binding.textDelayWarning.visibility = View.GONE
            }
        }
    }

    private fun getLocalizedAssetTypeName(type: AssetType): String {
        return getString(when(type) {
            AssetType.KRIPTO -> R.string.asset_type_crypto
            AssetType.BIST -> R.string.asset_type_stocks
            AssetType.DOVIZ -> R.string.asset_type_currency
            AssetType.EMTIA -> R.string.asset_type_commodity
            AssetType.NAKIT -> R.string.asset_type_cash
            AssetType.FON -> R.string.asset_type_fund
        })
    }

    private fun setupRecyclerView() {
        val type = try { AssetType.valueOf(assetType ?: "BIST") } catch (e: Exception) { AssetType.BIST }
        adapter = MarketAdapter(
            showChange = false,
            onItemClick = { selectedAsset, iconView, nameView ->
                
                if (type == AssetType.NAKIT && selectedAsset.symbol.startsWith("TOOL_")) {
                    when (selectedAsset.symbol) {
                        "TOOL_KASA" -> {
                            val depositSheet = com.yusufulgen.cuzdan.ui.home.DepositBottomSheet()
                            depositSheet.show(childFragmentManager, "DepositBottomSheet")
                        }
                        "TOOL_TERM_DEPOSIT" -> findNavController().navigate(R.id.action_navigation_symbol_search_to_term_deposit_calculator)
                        "TOOL_DEMAND_DEPOSIT" -> findNavController().navigate(R.id.action_navigation_symbol_search_to_demand_deposit_calculator)
                        "TOOL_BES" -> findNavController().navigate(R.id.action_navigation_symbol_search_to_bes_calculator)
                    }
                    return@MarketAdapter
                }
                try {
                    val action = SymbolSearchFragmentDirections.actionNavigationSymbolSearchToNavigationAssetDetail(
                        symbol = selectedAsset.symbol,
                        name = selectedAsset.name,
                        assetType = selectedAsset.assetType.name,
                        currency = selectedAsset.currency
                    )
                    findNavController().navigate(action)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onFavoriteClick = { asset ->
                viewModel.toggleFavorite(asset, type)
            }
        )
        binding.recyclerSymbols.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSymbols.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val type = try { AssetType.valueOf(assetType ?: "BIST") } catch (e: Exception) { AssetType.BIST }
                viewModel.search(s?.toString() ?: "", type)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeViewModel() {
        viewModel.uiState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle)
            .onEach { state ->
                binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                binding.btnCurrencySwitcher.setImageResource(if (state.currency == "TL") R.drawable.ic_tl else R.drawable.ic_usd)
                binding.btnFavorites.setImageResource(if (state.isFavoritesOnly) R.drawable.ic_star else R.drawable.ic_star_outline)
                val type = try { AssetType.valueOf(assetType ?: "BIST") } catch (e: Exception) { AssetType.BIST }
                val results = when (type) {
                    AssetType.NAKIT -> {
                        // NAKIT: Kasa + Vadeli Mevduat + Vadesiz Mevduat + BES (TRY kaldırıldı)
                        val tools = listOf(
                            MarketAsset(
                                symbol = "TOOL_KASA",
                                name = getString(R.string.cash_tool_kasa_short),
                                fullName = null,
                                currentPrice = BigDecimal.ZERO,
                                dailyChangePercentage = BigDecimal.ZERO,
                                assetType = AssetType.NAKIT,
                                currency = "TRY",
                                isFavorite = false
                            ),
                            MarketAsset(
                                symbol = "TOOL_TERM_DEPOSIT",
                                name = getString(R.string.cash_tool_term_deposit_short),
                                fullName = null,
                                currentPrice = BigDecimal.ZERO,
                                dailyChangePercentage = BigDecimal.ZERO,
                                assetType = AssetType.NAKIT,
                                currency = "TRY",
                                isFavorite = false
                            ),
                            MarketAsset(
                                symbol = "TOOL_DEMAND_DEPOSIT",
                                name = getString(R.string.cash_tool_demand_deposit_short),
                                fullName = null,
                                currentPrice = BigDecimal.ZERO,
                                dailyChangePercentage = BigDecimal.ZERO,
                                assetType = AssetType.NAKIT,
                                currency = "TRY",
                                isFavorite = false
                            ),
                            MarketAsset(
                                symbol = "TOOL_BES",
                                name = getString(R.string.cash_tool_bes_short),
                                fullName = null,
                                currentPrice = BigDecimal.ZERO,
                                dailyChangePercentage = BigDecimal.ZERO,
                                assetType = AssetType.NAKIT,
                                currency = "TRY",
                                isFavorite = false
                            )
                        )
                        tools
                    }
                    AssetType.DOVIZ -> {
                        // DOVIZ: TRY'yi en başa ekle (sabit 1 TL fiyat)
                        val tryItem = MarketAsset(
                            symbol = "TRY",
                            name = getString(R.string.currency_try),
                            fullName = null,
                            currentPrice = BigDecimal.ONE,
                            dailyChangePercentage = BigDecimal.ZERO,
                            assetType = AssetType.NAKIT, // Ana sayfa/raporlarda NAKIT altında görünecek
                            currency = "TRY",
                            isFavorite = false
                        )
                        listOf(tryItem) + state.results
                    }
                    else -> state.results
                }
                adapter.setItems(results)
                
                if (state.error != null) {
                    showToast(state.error)
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
