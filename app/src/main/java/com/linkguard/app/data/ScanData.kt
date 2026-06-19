package com.linkguard.app.data

import android.content.Context
import android.os.Parcelable
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize

// ─── Domain Models ────────────────────────────────────────────────────────────

enum class ThreatLevel { SAFE, SUSPICIOUS, DANGER }

/**
 * Flags grouped by the signal source/category ("Heuristic", "Vendors flagged", …) for the
 * collapsible verdict UI. Derived in [com.linkguard.app.domain.mapper.toLegacy] for fresh
 * scans only — intentionally NOT persisted to Room, so history-loaded results have it empty
 * and the UI falls back to the flat [ScanResult.flags] list.
 */
@Parcelize
data class FlagGroup(
    val category: String,
    val items: List<String>,
    /** True total of detections in this group. May exceed items.size when the displayed
     *  list is capped (the last item is then a "+N more" line), so the header count stays honest. */
    val count: Int = items.size
) : Parcelable

@Parcelize
data class ScanResult(
    val id: Int = 0,
    val url: String,
    val threatLevel: ThreatLevel,
    val riskScore: Int,
    val category: String,
    val flags: List<String>,
    val sourceApp: String,
    val senderInfo: String,
    val scannedAt: Long = System.currentTimeMillis(),
    val flagGroups: List<FlagGroup> = emptyList()
) : Parcelable

data class ScanStats(
    val dangerCount: Int,
    val suspiciousCount: Int,
    val safeCount: Int
)

// ─── Room Entity ──────────────────────────────────────────────────────────────

@Entity(tableName = "scan_history")
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val threatLevel: String,
    val riskScore: Int,
    val category: String,
    val flags: String,          // JSON array stored as TEXT
    val sourceApp: String,
    val senderInfo: String,
    val scannedAt: Long,
    // JSON of List<FlagGroup> for the collapsible UI. Nullable so the v1→v2 migration can add it
    // without a SQL default; pre-migration rows read back as null → flat fallback.
    val flagGroups: String? = null
)

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ScanDao {
    @Query("SELECT * FROM scan_history ORDER BY scannedAt DESC")
    suspend fun getAllScans(): List<ScanHistoryEntity>

    @Query("SELECT * FROM scan_history ORDER BY scannedAt DESC LIMIT :limit")
    suspend fun getRecentScans(limit: Int): List<ScanHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanHistoryEntity)

    @Delete
    suspend fun deleteScan(scan: ScanHistoryEntity)

    @Query("SELECT COUNT(*) FROM scan_history WHERE threatLevel = 'DANGER'")
    suspend fun getDangerCount(): Int

    @Query("SELECT COUNT(*) FROM scan_history WHERE threatLevel = 'SUSPICIOUS'")
    suspend fun getSuspiciousCount(): Int

    @Query("SELECT COUNT(*) FROM scan_history WHERE threatLevel = 'SAFE'")
    suspend fun getSafeCount(): Int

    @Query("DELETE FROM scan_history")
    suspend fun clearAll()
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [ScanHistoryEntity::class],
    version = 2,
    exportSchema = true          // Set to true for production — keeps migration audit trail
)
abstract class LinkGuardDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: LinkGuardDatabase? = null

        /** v2 adds the nullable flagGroups column (collapsible flag categories). Non-destructive
         *  ADD COLUMN; existing rows keep all data and read back null → flat flag fallback. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_history ADD COLUMN flagGroups TEXT")
            }
        }

        fun getInstance(context: Context): LinkGuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(
                context.applicationContext,
                LinkGuardDatabase::class.java,
                com.linkguard.app.util.AppConfig.DB_NAME
            )
                // DO NOT use fallbackToDestructiveMigration() in production —
                // it silently wipes user scan history on schema changes.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

// ─── Entity → Domain Mapper ───────────────────────────────────────────────────

private val gson = Gson()
private val flagListType = object : TypeToken<List<String>>() {}.type
private val flagGroupListType = object : TypeToken<List<FlagGroup>>() {}.type

fun ScanHistoryEntity.toDomain(): ScanResult {
    val flagList: List<String> = try {
        gson.fromJson(flags, flagListType) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
    // Null for pre-v2 rows (and any parse error) → empty → UI falls back to the flat list.
    val groups: List<FlagGroup> = try {
        flagGroups?.let { gson.fromJson(it, flagGroupListType) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
    return ScanResult(
        id = id,
        url = url,
        threatLevel = try {
            ThreatLevel.valueOf(threatLevel)
        } catch (_: IllegalArgumentException) {
            ThreatLevel.SAFE
        },
        riskScore = riskScore,
        category = category,
        flags = flagList,
        sourceApp = sourceApp,
        senderInfo = senderInfo,
        scannedAt = scannedAt,
        flagGroups = groups
    )
}

fun ScanResult.toEntity(): ScanHistoryEntity = ScanHistoryEntity(
    id = id,
    url = url,
    threatLevel = threatLevel.name,
    riskScore = riskScore,
    category = category,
    flags = gson.toJson(flags),
    sourceApp = sourceApp,
    senderInfo = senderInfo,
    scannedAt = scannedAt,
    flagGroups = if (flagGroups.isEmpty()) null else gson.toJson(flagGroups)
)
