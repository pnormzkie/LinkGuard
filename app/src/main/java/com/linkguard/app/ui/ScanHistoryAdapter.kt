package com.linkguard.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.linkguard.app.R
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ItemScanBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class HistoryItem {
    data class Header(val date: String) : HistoryItem()
    data class Scan(val result: ScanResult) : HistoryItem()
}

class ScanHistoryAdapter(
    private val onClick: (ScanResult) -> Unit,
    private val onLongClick: (ScanResult) -> Unit
) : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return when {
                    oldItem is HistoryItem.Header && newItem is HistoryItem.Header -> oldItem.date == newItem.date
                    oldItem is HistoryItem.Scan && newItem is HistoryItem.Scan -> oldItem.result.id == newItem.result.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HistoryItem.Header -> TYPE_HEADER
            is HistoryItem.Scan -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_date_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ItemViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is HeaderViewHolder && item is HistoryItem.Header) {
            holder.tvDate.text = item.date.uppercase()
        } else if (holder is ItemViewHolder && item is HistoryItem.Scan) {
            holder.bind(item.result)
        }
    }

    inner class ItemViewHolder(
        private val binding: ItemScanBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(scan: ScanResult) {
            val ctx = binding.root.context
            binding.tvUrl.text = scan.url.take(45).let { if (scan.url.length > 45) "$it…" else it }
            binding.tvMeta.text = ctx.getString(
                R.string.scan_meta_format, scan.sourceApp, dateFormat.format(Date(scan.scannedAt))
            )
            binding.tvScore.text = "${scan.riskScore}%"
            binding.tvCategory.text = scan.category

            val (label, colorRes) = when (scan.threatLevel) {
                ThreatLevel.DANGER     -> ctx.getString(R.string.status_badge_danger)     to R.color.e_red
                ThreatLevel.SUSPICIOUS -> ctx.getString(R.string.status_badge_suspicious) to R.color.e_amber
                ThreatLevel.SAFE       -> ctx.getString(R.string.status_badge_safe)       to R.color.e_green
            }

            val color = ContextCompat.getColor(ctx, colorRes)
            val tint = ColorStateList.valueOf(color)
            binding.tvStatus.text = label
            // Option E: solid colour pill + score badge with white text; the bar carries the colour.
            binding.tvStatus.backgroundTintList = tint
            binding.tvScore.backgroundTintList = tint
            binding.threatIndicator.backgroundTintList = tint

            binding.card.setOnClickListener { onClick(scan) }
            binding.card.setOnLongClickListener {
                onLongClick(scan)
                true
            }
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
    }
}
