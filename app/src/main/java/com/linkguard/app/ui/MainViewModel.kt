package com.linkguard.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ScanResult as LegacyScanResult
import com.linkguard.app.data.ScanStats
import com.linkguard.app.domain.mapper.toLegacy
import com.linkguard.app.domain.model.ScanResult as DomainScanResult
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository   = ScanRepository(application)
    private val orchestrator = ScannerProvider.orchestrator

    // ─── Exposed State ────────────────────────────────────────────────────────

    private val _scans           = MutableLiveData<List<LegacyScanResult>>(emptyList())
    private val _stats           = MutableLiveData<ScanStats>()
    private val _isScanning      = MutableLiveData(false)
    private val _manualScanResult = MutableLiveData<LegacyScanResult?>()
    private val _scanError       = MutableLiveData<String?>()

    val scans:            LiveData<List<LegacyScanResult>> = _scans
    val stats:            LiveData<ScanStats>              = _stats
    val isScanning:       LiveData<Boolean>                = _isScanning
    val manualScanResult: LiveData<LegacyScanResult?>      = _manualScanResult
    val scanError:        LiveData<String?>                = _scanError

    init { loadData() }

    // ─── Public Actions ───────────────────────────────────────────────────────

    fun loadData() {
        viewModelScope.launch {
            _scans.postValue(repository.getRecentScans())
            _stats.postValue(repository.getStats())
        }
    }

    fun manualScan(url: String, sourceApp: String = "Manual") {
        if (_isScanning.value == true) return
        // Set the guard synchronously on the main thread (this is called from the UI) so a
        // rapid double-tap can't slip between the check and an async postValue.
        _isScanning.value = true
        viewModelScope.launch {
            _scanError.postValue(null)

            runCatching {
                orchestrator.scan(url)
            }.onSuccess { domainResult ->
                val legacyResult = domainResult.toLegacy(sourceApp = sourceApp, senderInfo = "You")
                repository.saveScan(legacyResult)
                _manualScanResult.postValue(legacyResult)
                loadData()
            }.onFailure { e ->
                _scanError.postValue("Scan failed: ${e.message ?: "Unknown error"}")
                _manualScanResult.postValue(null)
            }
            
            _isScanning.postValue(false)
        }
    }

    fun saveResultManually(result: LegacyScanResult) {
        viewModelScope.launch {
            repository.saveScan(result)
            loadData()
        }
    }

    fun deleteScan(result: LegacyScanResult) {
        viewModelScope.launch {
            repository.deleteScan(result)
            loadData()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            loadData()
        }
    }

    fun clearManualResult() { _manualScanResult.value = null }
    fun clearScanError()    { _scanError.value = null }
}
