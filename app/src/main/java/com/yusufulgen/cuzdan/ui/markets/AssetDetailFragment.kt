package com.yusufulgen.cuzdan.ui.markets

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.yusufulgen.cuzdan.R
import androidx.transition.TransitionInflater
import android.content.res.ColorStateList
import com.yusufulgen.cuzdan.data.local.entity.AssetType
import com.yusufulgen.cuzdan.databinding.FragmentAssetDetailBinding
import com.yusufulgen.cuzdan.ui.assets.PriceAlertBottomSheet
import com.yusufulgen.cuzdan.util.showToast
import com.yusufulgen.cuzdan.util.HapticManager
import com.yusufulgen.cuzdan.util.formatCurrency
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.math.BigDecimal

@AndroidEntryPoint
class AssetDetailFragment : Fragment() {

    private var _binding: FragmentAssetDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AssetDetailViewModel by viewModels()
    private val args: AssetDetailFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        sharedElementEnterTransition = TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move)
        _binding = FragmentAssetDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            setupToolbar()
            setupListeners()
            observeState()
            
            if (args.assetType == "NAKIT" && args.symbol == "TRY") {
                binding.layoutCostContainer.visibility = View.GONE
                binding.textAmountLabel.text = "TL"
            }
            
            viewModel.init(args.symbol, args.name, args.assetType, args.currency)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.toast_detail_load_error)
            findNavController().navigateUp()
        }
    }

    private fun setupToolbar() {
        binding.textTitleDetail.text = if (args.assetType == "FON") args.symbol else getLocalizedAssetName(args.name)
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnDelete.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.detail_title))
                .setMessage(getString(R.string.reset_warning_message))
                .setPositiveButton(getString(R.string.dialog_confirm)) { _, _ -> viewModel.deleteAsset() }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
        }
        binding.btnAlert.setOnClickListener {
            val state = viewModel.uiState.value
            val currentType = try { AssetType.valueOf(args.assetType) } catch (e: Exception) { AssetType.BIST }
            val bottomSheet = PriceAlertBottomSheet(
                symbol = state.symbol,
                name = state.name,
                assetType = currentType,
                currentPrice = state.currentPrice,
                onAlertSet = { alert ->
                    viewModel.setPriceAlert(alert)
                    showToast(R.string.toast_alert_created)
                }
            )
            bottomSheet.show(childFragmentManager, PriceAlertBottomSheet.TAG)
        }
    }


    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            HapticManager.tap(it)
            val amountStr = binding.editAmount.text.toString()
            val costStr = binding.editCost.text.toString()
            
            if (amountStr.isEmpty()) {
                binding.editAmount.error = getString(R.string.alert_error_price)
                return@setOnClickListener
            }
            
            val amount = amountStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val cost = costStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
            
            // Maliyet zorunlu: BUY modunda ve NAKIT olmayan varlıklarda
            val isBuyMode = binding.toggleTransactionType.checkedButtonId == R.id.btnBuy
            val isNakit = args.assetType == "NAKIT"
            if (isBuyMode && !isNakit && (costStr.isEmpty() || cost <= BigDecimal.ZERO)) {
                binding.editCost.error = getString(R.string.alert_error_price)
                return@setOnClickListener
            }
            
            viewModel.saveAsset(amount, cost, args.assetType)
        }

        binding.toggleTransactionType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val type = if (checkedId == R.id.btnBuy) TransactionType.BUY else TransactionType.SELL
                viewModel.setTransactionType(type)
                
                if (type == TransactionType.SELL) {
                    binding.layoutCostContainer.visibility = View.GONE
                    binding.btnSave.text = getString(R.string.detail_sell)
                    binding.btnSave.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.accent_red, null))
                } else {
                    if (args.assetType != "NAKIT" || args.symbol != "TRY") {
                        binding.layoutCostContainer.visibility = View.VISIBLE
                    }
                    binding.btnSave.text = getString(R.string.detail_buy)
                    binding.btnSave.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.accent_violet, null))
                }
            }
        }

        binding.chartRangeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnRange1D -> viewModel.updateRange("1d")
                    R.id.btnRange1W -> viewModel.updateRange("1w")
                    R.id.btnRange1M -> viewModel.updateRange("1mo")
                    R.id.btnRange1Y -> viewModel.updateRange("1y")
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                    if (state.isSaved) {
                        HapticManager.success(requireContext())
                        showToast(R.string.toast_asset_saved)
                        findNavController().navigateUp()
                    }
                    if (state.isDeleted) {
                        HapticManager.success(requireContext())
                        showToast(R.string.toast_asset_deleted)
                        findNavController().navigateUp()
                    }
                    if (state.errorMessage != null) {
                        HapticManager.error(requireContext())
                        showToast(state.errorMessage)
                    }
                }
            }
        }
    }

    private fun updateUI(state: AssetDetailUiState) {
        binding.progressBarChart.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        if (state.isLoading) {
            binding.priceChart.alpha = 0.5f
        } else {
            binding.priceChart.alpha = 1.0f
        }
        
        binding.textCurrentPrice.text = state.currentPrice.formatCurrency(state.displayCurrency)
        binding.textPortfolioName.text = getString(R.string.detail_portfolio_prefix, state.portfolioName)
        
        // Load icon using AssetUtils
        val assetType = try {
            com.yusufulgen.cuzdan.data.local.entity.AssetType.valueOf(args.assetType)
        } catch (e: Exception) {
            com.yusufulgen.cuzdan.data.local.entity.AssetType.EMTIA // Default
        }
        val iconRes = com.yusufulgen.cuzdan.util.AssetUtils.getAssetIcon(args.symbol, assetType)
        
        if (args.assetType == "DOVIZ" || args.assetType == "NAKIT") {
            val emoji = com.yusufulgen.cuzdan.util.EmojiDrawableHelper.currencyToFlagEmoji(args.symbol)
            if (emoji != null) {
                val d = com.yusufulgen.cuzdan.util.EmojiDrawableHelper.emojiToDrawable(requireContext(), emoji, 32f)
                binding.ivAssetIconDetail.setImageDrawable(d)
                binding.ivAssetIconDetail.imageTintList = null
            } else {
                binding.ivAssetIconDetail.setImageResource(iconRes)
                val typedValueTint = android.util.TypedValue()
                if (requireContext().theme.resolveAttribute(com.yusufulgen.cuzdan.R.attr.textPrimary, typedValueTint, true)) {
                    binding.ivAssetIconDetail.imageTintList = android.content.res.ColorStateList.valueOf(typedValueTint.data)
                }
            }
        } else if (args.assetType == "EMTIA" && iconRes == com.yusufulgen.cuzdan.R.drawable.ic_commodity) {
            val emoji = com.yusufulgen.cuzdan.util.EmojiDrawableHelper.commodityToEmoji(args.symbol, args.name)
            if (emoji != null) {
                val d = com.yusufulgen.cuzdan.util.EmojiDrawableHelper.emojiToDrawable(requireContext(), emoji, 32f)
                binding.ivAssetIconDetail.setImageDrawable(d)
                binding.ivAssetIconDetail.imageTintList = null
            } else {
                binding.ivAssetIconDetail.setImageResource(iconRes)
                binding.ivAssetIconDetail.imageTintList = null
            }
        } else {
            binding.ivAssetIconDetail.setImageResource(iconRes)
            // Disable tint for colorful commodity icons
            if (args.assetType == "EMTIA") {
                binding.ivAssetIconDetail.imageTintList = null
            } else {
                val typedValueTint = android.util.TypedValue()
                if (requireContext().theme.resolveAttribute(com.yusufulgen.cuzdan.R.attr.textPrimary, typedValueTint, true)) {
                    binding.ivAssetIconDetail.imageTintList = android.content.res.ColorStateList.valueOf(typedValueTint.data)
                }
            }
        }
        
        // Show held amount
        binding.textCurrentAmountHeld.text = getString(R.string.detail_held_amount, state.currentAmount.toPlainString())
        
        val isPositive = state.dailyChangePercentage >= BigDecimal.ZERO
        val colorAttr = if (isPositive) com.yusufulgen.cuzdan.R.attr.pill_green_text else com.yusufulgen.cuzdan.R.attr.pill_red_text
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(colorAttr, typedValue, true)
        val colorInt = typedValue.data

        binding.textPriceChange.text = String.format("%%%+.2f", state.dailyChangePercentage)
        binding.textPriceChange.setTextColor(colorInt)
        
        setupChart(state.history)
    }

    private fun setupChart(history: List<Pair<Long, Double>>) {
        if (history.isEmpty()) {
            if (!viewModel.uiState.value.isLoading) {
                binding.priceChart.setNoDataText(getString(R.string.error_loading))
                binding.priceChart.invalidate()
            } else {
                binding.priceChart.setNoDataText("")
                binding.priceChart.invalidate()
            }
            return
        }

        val entries = history.mapIndexed { index, pair ->
            Entry(index.toFloat(), pair.second.toFloat())
        }

        val minVal = history.minOf { it.second }
        val maxVal = history.maxOf { it.second }
        val range = maxVal - minVal

        val accentViolet = resources.getColor(R.color.pastel_violet, null)

        val dataSet = LineDataSet(entries, "").apply {
            color = accentViolet
            lineWidth = 2.5f
            setDrawCircles(false)
            setDrawCircleHole(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            
            setDrawFilled(true)
            fillDrawable = resources.getDrawable(R.drawable.bg_chart_gradient_light, null)
            
            // Highlight styling
            highLightColor = resources.getColor(R.color.accent_gold, null)
            highlightLineWidth = 1f
            enableDashedHighlightLine(10f, 5f, 0f)
            setDrawHorizontalHighlightIndicator(true)
            setDrawVerticalHighlightIndicator(true)
        }

        binding.priceChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            
            val typedValue = android.util.TypedValue()
            val textColor = if (requireContext().theme.resolveAttribute(com.yusufulgen.cuzdan.R.attr.textPrimary, typedValue, true)) {
                typedValue.data
            } else {
                Color.WHITE
            }
            
            val dividerColor = if (requireContext().theme.resolveAttribute(com.yusufulgen.cuzdan.R.attr.divider_light, typedValue, true)) {
                typedValue.data
            } else {
                Color.parseColor("#33FFFFFF")
            }

            // Set Marker
            val mv = AssetChartMarkerView(requireContext(), history, viewModel.uiState.value.displayCurrency)
            mv.chartView = this
            marker = mv

            xAxis.apply {
                isEnabled = true
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                this.textColor = textColor
                textSize = 10f
                yOffset = 8f
                axisLineColor = dividerColor
                
                valueFormatter = object : ValueFormatter() {
                    private val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    private val dayFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
                    private val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())

                    override fun getFormattedValue(value: Float): String {
                        val index = value.toInt()
                        if (index >= 0 && index < history.size) {
                            val timestamp = history[index].first
                            val totalDiff = history.last().first - history.first().first
                            
                            return when {
                                totalDiff < 2 * 24 * 60 * 60 * 1000L -> hourFormat.format(Date(timestamp))
                                totalDiff < 365 * 24 * 60 * 60 * 1000L -> dayFormat.format(Date(timestamp))
                                else -> yearFormat.format(Date(timestamp))
                            }
                        }
                        return ""
                    }
                }
                granularity = 1f
                setLabelCount(3, false)
            }

            axisLeft.apply {
                isEnabled = true
                this.textColor = textColor
                textSize = 10f
                setDrawGridLines(true)
                gridColor = dividerColor
                axisLineColor = Color.TRANSPARENT
                setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
                xOffset = 5f
                
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return when {
                            range > 10000 -> {
                                if (value >= 1000000 || value <= -1000000) String.format("%.1fM", value / 1000000)
                                else if (value >= 1000 || value <= -1000) String.format("%.1fK", value / 1000)
                                else String.format("%.0f", value)
                            }
                            range > 100 -> String.format("%.0f", value)
                            else -> String.format("%.2f", value)
                        }
                    }
                }
            }
            
            axisRight.isEnabled = false
            
            setTouchEnabled(true)
            setPinchZoom(true)
            setScaleEnabled(true)
            setExtraOffsets(8f, 8f, 8f, 12f) // Increased offsets
            animateX(800)
            invalidate()
        }
    }

    private fun getLocalizedAssetName(name: String): String {
        return when {
            name == "Türk Lirası" || name == "Turkish Lira" -> getString(R.string.currency_try).replace(" (₺)", "")
            name == "Amerikan Doları" || name == "US Dollar" || name == "American Dollar" || name == "United States Dollar" -> getString(R.string.currency_usd).replace(" ($)", "")
            name == "Euro" -> getString(R.string.currency_eur).replace(" (€)", "")
            name == "İngiliz Sterlini" || name == "British Pound" -> getString(R.string.currency_gbp)
            name == "İsviçre Frangı" || name == "Swiss Franc" -> getString(R.string.currency_chf)
            name == "Japon Yeni" || name == "Japanese Yen" -> getString(R.string.currency_jpy)
            name == "Avustralya Doları" || name == "Australian Dollar" -> getString(R.string.currency_aud)
            name == "Kanada Doları" || name == "Canadian Dollar" -> getString(R.string.currency_cad)
            name == "Altın (Ons)" || name == "Gold (Oz)" -> getString(R.string.commodity_gold_oz)
            name == "Gram Altın" || name == "Gram Gold" -> getString(R.string.commodity_gram_gold)
            name == "Altın" || name == "Gold" -> getString(R.string.commodity_gold)
            name == "Gümüş" || name == "Silver" -> getString(R.string.commodity_silver)
            name == "Bakır" || name == "Copper" -> getString(R.string.commodity_copper)
            name == "Platin" || name == "Platinum" -> getString(R.string.commodity_platinum)
            name == "Paladyum" || name == "Palladium" -> getString(R.string.commodity_palladium)
            else -> name
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
