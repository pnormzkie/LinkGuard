package com.linkguard.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class LinkGuardDatabaseTest {

    private lateinit var database: LinkGuardDatabase
    private lateinit var dao: ScanDao
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, LinkGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.scanDao()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(MIGRATION_DB_NAME)
    }

    @Test
    fun daoPersistsOrdersCountsAndDeletesScans() = runBlocking {
        val safe = entity(id = 1, url = "https://safe.example", threatLevel = "SAFE", scannedAt = 10)
        val danger = entity(id = 2, url = "https://danger.example", threatLevel = "DANGER", scannedAt = 30)
        val suspicious = entity(
            id = 3,
            url = "https://suspicious.example",
            threatLevel = "SUSPICIOUS",
            scannedAt = 20,
        )

        dao.insertScan(safe)
        dao.insertScan(danger)
        dao.insertScan(suspicious)

        assertEquals(listOf(danger, suspicious, safe), dao.getAllScans())
        assertEquals(listOf(danger, suspicious), dao.getRecentScans(2))
        assertEquals(1, dao.getSafeCount())
        assertEquals(1, dao.getDangerCount())
        assertEquals(1, dao.getSuspiciousCount())

        dao.deleteScan(suspicious)
        assertEquals(listOf(danger, safe), dao.getAllScans())

        dao.clearAll()
        assertTrue(dao.getAllScans().isEmpty())
    }

    @Test
    fun migrationOneToTwoPreservesRowsAndAddsNullableFlagGroups() = runBlocking {
        database.close()
        createVersionOneDatabase()

        database = Room.databaseBuilder(context, LinkGuardDatabase::class.java, MIGRATION_DB_NAME)
            .addMigrations(LinkGuardDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        dao = database.scanDao()

        val migrated = dao.getAllScans().single()
        assertEquals("https://legacy.example", migrated.url)
        assertEquals("DANGER", migrated.threatLevel)
        assertNull(migrated.flagGroups)
    }

    private fun createVersionOneDatabase() {
        context.deleteDatabase(MIGRATION_DB_NAME)
        val file = context.getDatabasePath(MIGRATION_DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scan_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    threatLevel TEXT NOT NULL,
                    riskScore INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    flags TEXT NOT NULL,
                    sourceApp TEXT NOT NULL,
                    senderInfo TEXT NOT NULL,
                    scannedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            legacy.execSQL(
                """
                INSERT INTO scan_history
                    (id, url, threatLevel, riskScore, category, flags, sourceApp, senderInfo, scannedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(1, "https://legacy.example", "DANGER", 90, "Legacy", "[]", "Test", "Sender", 1L),
            )
            legacy.version = 1
        }
    }

    private fun entity(
        id: Int,
        url: String,
        threatLevel: String,
        scannedAt: Long,
    ) = ScanHistoryEntity(
        id = id,
        url = url,
        threatLevel = threatLevel,
        riskScore = 50,
        category = "Test",
        flags = "[]",
        sourceApp = "Test",
        senderInfo = "Sender",
        scannedAt = scannedAt,
    )

    private companion object {
        const val MIGRATION_DB_NAME = "linkguard-migration-test.db"
    }
}
