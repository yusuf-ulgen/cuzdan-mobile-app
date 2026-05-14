package com.yusufulgen.cuzdan.ui.markets

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.LinearLayoutManager
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.data.local.entity.MarketAsset
import com.yusufulgen.cuzdan.databinding.FragmentMarketsBinding

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MarketsFragment : Fragment() {

    private var _binding: FragmentMarketsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MarketsViewModel by viewModels()
    private lateinit var adapter: MarketAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMarketsBinding.inflate(inflater, container, false)
        
        setupRecyclerView()
        setupListeners()
        observeState()
        
        // Markets sayfasını her açtığımızda varsayılan olarak "Borsa" seçili olmalı
        binding.chipBist.isChecked = true
        updateDelayWarning(AssetType.BIST)
        
        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = MarketAdapter(
            showChange = true,
            onItemClick = { asset, _, _ ->
                try {
                    val action = MarketsFragmentDirections.actionNavigationMarketsToNavigationAssetDetail(
                        symbol = asset.symbol,
                        name = asset.name,
                        assetType = asset.assetType.name,
                        currency = asset.currency
                    )
                    findNavController().navigate(action)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onFavoriteClick = { asset ->
                viewModel.toggleFavorite(asset)
            }
        )
        binding.recyclerMarkets.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMarkets.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefreshMarkets.setOnRefreshListener {
            viewModel.refreshPrices()
        }

        binding.btnFavorites.setOnClickListener {
            viewModel.toggleFavoritesOnly()
        }

        binding.btnSort.setOnClickListener {
            showSortMenu(it)
        }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.search(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupMarkets.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            val type = when (checkedId) {
                R.id.chip_all -> null
                R.id.chip_bist -> AssetType.BIST
                R.id.chip_crypto -> AssetType.KRIPTO
                R.id.chip_currency -> AssetType.DOVIZ
                R.id.chip_emtia -> AssetType.EMTIA
                R.id.chip_fon -> AssetType.FON
                else -> null
            }
            updateDelayWarning(type)
            viewModel.filterByType(type)
        }
    }

    private fun updateDelayWarning(type: AssetType?) {
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

    private var lastSortType: MarketsSortType? = null
    private var lastFilterType: AssetType? = null

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.setItems(state.filteredPrices)
                    
                    // Eğer sıralama veya filtre değiştiyse en başa kaydır
                    if (state.sortType != lastSortType || state.selectedType != lastFilterType) {
                        binding.recyclerMarkets.scrollToPosition(0)
                        lastSortType = state.sortType
                        lastFilterType = state.selectedType
                    }
                    
                    binding.swipeRefreshMarkets.isRefreshing = state.isLoading
                    binding.btnFavorites.setImageResource(if (state.isFavoritesOnly) R.drawable.ic_star else R.drawable.ic_star_outline)
                    
                    if (state.isLoading) {
                        binding.shimmerMarkets.startShimmer()
                        binding.shimmerMarkets.visibility = View.VISIBLE
                        binding.recyclerMarkets.visibility = View.GONE
                    } else {
                        binding.shimmerMarkets.stopShimmer()
                        binding.shimmerMarkets.visibility = View.GONE
                        binding.recyclerMarkets.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun showSortMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(0, 0, 0, getString(R.string.sort_name_asc))
        popup.menu.add(0, 1, 1, getString(R.string.sort_name_desc))
        popup.menu.add(0, 2, 2, getString(R.string.sort_price_asc))
        popup.menu.add(0, 3, 3, getString(R.string.sort_price_desc))
        popup.menu.add(0, 4, 4, getString(R.string.sort_change_asc))
        popup.menu.add(0, 5, 5, getString(R.string.sort_change_desc))

        popup.setOnMenuItemClickListener { item ->
            val sortType = when (item.itemId) {
                0 -> MarketsSortType.NAME_ASC
                1 -> MarketsSortType.NAME_DESC
                2 -> MarketsSortType.PRICE_ASC
                3 -> MarketsSortType.PRICE_DESC
                4 -> MarketsSortType.CHANGE_ASC
                5 -> MarketsSortType.CHANGE_DESC
                else -> MarketsSortType.NAME_ASC
            }
            viewModel.setSortType(sortType)
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
