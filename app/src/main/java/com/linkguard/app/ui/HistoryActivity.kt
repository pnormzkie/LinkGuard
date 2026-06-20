package com.linkguard.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.linkguard.app.R
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: ScanHistoryAdapter
    private var activeFilter: String? = null
    private val headerFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get initial filter if passed from MainActivity
        activeFilter = intent.getStringExtra("FILTER_TYPE")

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        setupButtons()
        setupFilterTabs()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ScanHistoryAdapter(
            onClick = { scan ->
                startActivity(ScanDetailActivity.newIntent(this, scan))
            },
            onLongClick = { scan ->
                showDeleteConfirmation(scan)
            }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }

    private fun showDeleteConfirmation(scan: ScanResult) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_scan)
            .setMessage(R.string.delete_scan_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteScan(scan) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearAll.setOnClickListener {
            if (viewModel.scans.value.isNullOrEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle(R.string.clear_all_history_title)
                .setMessage(R.string.clear_all_history_confirm)
                .setPositiveButton(R.string.clear_all_action) { _, _ -> viewModel.clearHistory() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupFilterTabs() {
        val tabs = listOf(
            binding.tabAll to null,
            binding.tabDanger to ThreatLevel.DANGER.name,
            binding.tabSuspicious to ThreatLevel.SUSPICIOUS.name,
            binding.tabSafe to ThreatLevel.SAFE.name
        )

        fun updateTabStyles(selected: View) {
            tabs.forEach { (tab, _) ->
                if (tab == selected) {
                    (tab as TextView).setTextColor(ContextCompat.getColor(this, R.color.e_text))
                    tab.setBackgroundResource(R.drawable.bg_e_tab_active)
                } else {
                    (tab as TextView).setTextColor(ContextCompat.getColor(this, R.color.e_muted))
                    tab.setBackgroundResource(R.drawable.bg_e_tab)
                }
            }
        }

        // Initialize styles based on activeFilter
        val initialTab = when(activeFilter) {
            ThreatLevel.DANGER.name -> binding.tabDanger
            ThreatLevel.SUSPICIOUS.name -> binding.tabSuspicious
            ThreatLevel.SAFE.name -> binding.tabSafe
            else -> binding.tabAll
        }
        updateTabStyles(initialTab)

        tabs.forEach { (tab, filter) ->
            tab.setOnClickListener {
                activeFilter = filter
                updateTabStyles(tab)
                // When a tab is clicked, we filter the current list and update the adapter
                viewModel.scans.value?.let { updateList(it) }
            }
        }
        
        // Use the container IDs directly from the binding
        binding.containerDanger.setOnClickListener {
            activeFilter = ThreatLevel.DANGER.name
            updateTabStyles(binding.tabDanger)
            viewModel.scans.value?.let { updateList(it) }
        }
        
        binding.containerSuspicious.setOnClickListener {
            activeFilter = ThreatLevel.SUSPICIOUS.name
            updateTabStyles(binding.tabSuspicious)
            viewModel.scans.value?.let { updateList(it) }
        }
        
        binding.containerSafe.setOnClickListener {
            activeFilter = ThreatLevel.SAFE.name
            updateTabStyles(binding.tabSafe)
            viewModel.scans.value?.let { updateList(it) }
        }
    }

    private fun observeViewModel() {
        viewModel.scans.observe(this) { scans ->
            updateList(scans)
        }

        viewModel.stats.observe(this) { stats ->
            binding.tvThreatCount.text = stats.dangerCount.toString()
            binding.tvSuspiciousCount.text = stats.suspiciousCount.toString()
            binding.tvSafeCount.text = stats.safeCount.toString()
        }
    }

    private fun updateList(scans: List<ScanResult>) {
        val filteredScans = if (activeFilter != null) {
            scans.filter { it.threatLevel.name == activeFilter }
        } else {
            scans
        }
        
        // --- TRANSFORM LIST TO INCLUDE HEADERS DYNAMICALLY ---
        val items = mutableListOf<HistoryItem>()
        if (filteredScans.isNotEmpty()) {
            val grouped = filteredScans.groupBy { headerFormat.format(Date(it.scannedAt)) }
            grouped.forEach { (date, scansInDate) ->
                items.add(HistoryItem.Header(date))
                scansInDate.forEach { items.add(HistoryItem.Scan(it)) }
            }
        }
        
        adapter.submitList(items)
        val isEmpty = filteredScans.isEmpty()
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.btnClearAll.visibility = if (scans.isEmpty()) View.GONE else View.VISIBLE
        
        if (isEmpty && activeFilter != null) {
            binding.tvEmpty.text = getString(R.string.no_items_for_category)
        } else {
            binding.tvEmpty.text = getString(R.string.no_history_found)
        }
    }
}
