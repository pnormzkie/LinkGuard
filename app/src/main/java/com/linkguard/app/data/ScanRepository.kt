package com.linkguard.app.data

import android.content.Context
import com.linkguard.app.util.AppConfig

class ScanRepository(context: Context) {

    private val dao = LinkGuardDatabase.getInstance(context).scanDao()

    suspend fun getRecentScans(limit: Int = AppConfig.SCAN_HISTORY_LIMIT): List<ScanResult> =
        dao.getRecentScans(limit).map { it.toDomain() }

    suspend fun getAllScans(): List<ScanResult> =
        dao.getAllScans().map { it.toDomain() }

    suspend fun saveScan(result: ScanResult) =
        dao.insertScan(result.toEntity())

    suspend fun deleteScan(result: ScanResult) =
        dao.deleteScan(result.toEntity())

    suspend fun getStats(): ScanStats = ScanStats(
        dangerCount = dao.getDangerCount(),
        suspiciousCount = dao.getSuspiciousCount(),
        safeCount = dao.getSafeCount()
    )

    suspend fun clearHistory() = dao.clearAll()
}
