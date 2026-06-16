package com.yusufulgen.cuzdan.ui.home
 
import com.yusufulgen.cuzdan.data.local.entity.Asset
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yusufulgen.cuzdan.databinding.ItemWalletCategoryBinding
import com.yusufulgen.cuzdan.util.formatCurrency
import java.math.BigDecimal

class WalletCategoryAdapter(
    private var items: List<WalletCategorySummary> = emptyList(),
    private val onExpandToggle: (WalletCategorySummary) -> Unit,
    private val onAssetClick: (Asset, android.view.View, android.view.View) -> Unit = { _, _, _ -> }
) : RecyclerView.Adapter<WalletCategoryAdapter.ViewHolder>() {

    private var isPrivacyEnabled: Boolean = false
    private var currency: String = "TL"

    class ViewHolder(val binding: ItemWalletCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWalletCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isExpanded = item.isExpanded
        val isNakit = item.type == com.yusufulgen.cuzdan.data.local.entity.AssetType.NAKIT

        holder.binding.apply {
            textCategoryTitle.text = item.title
            
            // Set category icon
            if (item.iconRes != 0) {
                imageCategoryIcon.setImageResource(item.iconRes)
                imageCategoryIcon.visibility = View.VISIBLE
            } else {
                imageCategoryIcon.visibility = View.GONE
            }

            // Nakit: only show title + value, no P/L
            if (isNakit) {
                textCategoryChangePerc.visibility = View.GONE
                textCategoryChangeAbs.visibility = View.GONE
                imageExpandArrow.visibility = View.GONE

                if (isPrivacyEnabled) {
                    textCategoryTotal.text = "**** ${item.currency}"
                } else {
                    textCategoryTotal.text = item.totalValue.formatCurrency(item.currency)
                }
                textCategoryTotal.visibility = View.VISIBLE
            } else {
                // Normal categories: show P/L
                textCategoryChangePerc.visibility = View.VISIBLE
                textCategoryChangeAbs.visibility = View.VISIBLE
                
                if (isPrivacyEnabled) {
                    textCategoryTotal.text = "**** ${item.currency}"
                    textCategoryChangeAbs.text = "****"
                    textCategoryChangePerc.text = "%***"
                    textCategoryChangePerc.setTextColor(holder.itemView.context.getColor(com.yusufulgen.cuzdan.R.color.text_label))
                } else {
                    val isNeutral = item.profitLossPerc.abs() < BigDecimal("0.01")
                    val isProfit = item.profitLossPerc >= BigDecimal("0.01")
                    
                    val color = when {
                        isNeutral -> com.yusufulgen.cuzdan.R.color.text_label
                        isProfit -> com.yusufulgen.cuzdan.R.color.accent_green
                        else -> com.yusufulgen.cuzdan.R.color.accent_red
                    }
                    val colorInt = holder.itemView.context.getColor(color)
                    
                    textCategoryTotal.text = item.totalValue.formatCurrency(item.currency)
                    textCategoryChangeAbs.text = item.totalProfitLoss.formatCurrency(item.currency, showSign = true)
                    textCategoryChangePerc.text = String.format("%%%+.1f", item.profitLossPerc)
                    
                    textCategoryChangeAbs.setTextColor(colorInt)
                    textCategoryChangePerc.setTextColor(colorInt)
                }
                textCategoryTotal.visibility = View.VISIBLE
                
                imageExpandArrow.visibility = View.VISIBLE
                imageExpandArrow.rotation = if (isExpanded) 180f else 0f
            }
            
            recyclerChildAssets.visibility = if (isExpanded) View.VISIBLE else View.GONE
            
            if (isExpanded) {
                val childAdapter = WalletAssetAdapter(item.assets, isPrivacyEnabled, item.currency, item.totalValue, onAssetClick)
                recyclerChildAssets.layoutManager = LinearLayoutManager(holder.itemView.context)
                recyclerChildAssets.adapter = childAdapter
            }
            
            root.setOnClickListener {
                onExpandToggle(item)
            }
        }
    }

    override fun getItemCount() = items.size

    fun setItemsWithPrivacy(newItems: List<WalletCategorySummary>, privacyEnabled: Boolean, newCurrency: String = "TL") {
        items = newItems
        isPrivacyEnabled = privacyEnabled
        currency = newCurrency
        notifyDataSetChanged()
    }
}
