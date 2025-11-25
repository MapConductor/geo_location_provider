package com.mapconductor.plugin.provider.storageservice.room

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AppDatabase for the storageservice module.
 *
 * - DB name: geolocation.db
 * - Entities: LocationSample, ExportedDay
 * - Version: 7 (v6 -> v7 made courseDeg nullable)
 */
@Database(
    entities = [
        LocationSample::class,
        ExportedDay::class
    ],
    version = 7,
    exportSchema = true
)
internal abstract class AppDatabase : RoomDatabase() {

    abstract fun locationSampleDao(): LocationSampleDao
    abstract fun exportedDayDao(): ExportedDayDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "geolocation.db"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            logDbIdentity("StorageDb", context, db)
                        }
                    })
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                INSTANCE = inst
                inst
            }
        }

        // --- v1 -> v2 --------------------------------------------------------
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Older versions are not used in production; keep migration minimal but safe
                addColumnIfMissing(db, "location_samples", "batteryPercent", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "location_samples", "isCharging", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        // --- v2 -> v3 --------------------------------------------------------
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add gnssUsed / gnssTotal / cn0 columns if needed
                addColumnIfMissing(db, "location_samples", "gnssUsed", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "location_samples", "gnssTotal", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "location_samples", "cn0", "REAL NOT NULL DEFAULT 0.0")
            }
        }

        // --- v3 -> v4: add missing basic columns before creating indexes -----
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure timeMillis and provider columns exist before we add indexes
                if (!hasColumn(db, "location_samples", "timeMillis")) {
                    db.execSQL(
                        "ALTER TABLE `location_samples` " +
                            "ADD COLUMN `timeMillis` INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!hasColumn(db, "location_samples", "provider")) {
                    db.execSQL(
                        "ALTER TABLE `location_samples` " +
                            "ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'gps'"
                    )
                }
                // Unique index is created in v4 -> v5
            }
        }

        // --- v4 -> v5: create expected Room-style UNIQUE index ----------------
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val expected = "index_location_samples_timeMillis_provider"
                val wrong1 = "idx_location_samples_time_provider"

                if (hasIndex(db, wrong1)) {
                    db.execSQL("DROP INDEX IF EXISTS `$wrong1`")
                    Log.d("DB/Migration", "dropped wrong index: $wrong1")
                }
                if (!hasIndex(db, expected)) {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `$expected` " +
                            "ON `location_samples`(`timeMillis`, `provider`)"
                    )
                    Log.d("DB/Migration", "created expected unique index: $expected")
                } else {
                    Log.d("DB/Migration", "expected unique index already exists: $expected")
                }
            }
        }

        // --- v5 -> v6: add ExportedDay (exported_days) -----------------------
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exported_days`(
                        `epochDay` INTEGER NOT NULL PRIMARY KEY,
                        `exportedLocal` INTEGER NOT NULL DEFAULT 0,
                        `uploaded` INTEGER NOT NULL DEFAULT 0,
                        `driveFileId` TEXT,
                        `lastError` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        // --- v6 -> v7: rebuild location_samples to make courseDeg nullable ----
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `location_samples_new`(
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timeMillis` INTEGER NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `accuracy` REAL NOT NULL,
                        `provider` TEXT NOT NULL,
                        `headingDeg` REAL NOT NULL,
                        `courseDeg` REAL,
                        `speedMps` REAL NOT NULL,
                        `gnssUsed` INTEGER NOT NULL,
                        `gnssTotal` INTEGER NOT NULL,
                        `cn0` REAL NOT NULL,
                        `batteryPercent` INTEGER NOT NULL,
                        `isCharging` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Copy existing data into the new table
                db.execSQL(
                    """
                    INSERT INTO `location_samples_new`(
                        `id`,`timeMillis`,`lat`,`lon`,`accuracy`,`provider`,
                        `headingDeg`,`courseDeg`,`speedMps`,
                        `gnssUsed`,`gnssTotal`,`cn0`,`batteryPercent`,`isCharging`
                    )
                    SELECT
                        `id`,`timeMillis`,`lat`,`lon`,`accuracy`,`provider`,
                        `headingDeg`,`courseDeg`,`speedMps`,
                        `gnssUsed`,`gnssTotal`,`cn0`,`batteryPercent`,`isCharging`
                    FROM `location_samples`
                    """.trimIndent()
                )

                // Replace old table with the new one
                db.execSQL("DROP TABLE `location_samples`")
                db.execSQL("ALTER TABLE `location_samples_new` RENAME TO `location_samples`")

                // Recreate the composite UNIQUE index
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_location_samples_timeMillis_provider`
                    ON `location_samples`(`timeMillis`,`provider`)
                    """.trimIndent()
                )
            }
        }

        // --------- helpers ---------
        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            type: String
        ) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $type")
                Log.d("DB/Migration", "Added column `$column` ($type) to `$table`")
            } else {
                Log.d("DB/Migration", "Column `$column` already exists in `$table` - skipped")
            }
        }

        private fun hasColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) return true
                }
            }
            return false
        }

        private fun hasIndex(db: SupportSQLiteDatabase, name: String): Boolean {
            db.query("PRAGMA index_list(`location_samples`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == name) return true
                }
            }
            return false
        }

        @Suppress("unused")
        private fun findUniqueIndexOn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): String? {
            db.query("PRAGMA index_list(`$table`)").use { list ->
                val nameIdx = list.getColumnIndex("name")
                val uniqueIdx = list.getColumnIndex("unique")
                while (list.moveToNext()) {
                    val idxName = list.getString(nameIdx)
                    val isUnique = list.getInt(uniqueIdx) == 1
                    if (!isUnique || idxName.isNullOrEmpty()) continue
                    db.query("PRAGMA index_info(`$idxName`)").use { info ->
                        var count = 0
                        var matches = false
                        val colNameIdx = info.getColumnIndex("name")
                        while (info.moveToNext()) {
                            count++
                            if (info.getString(colNameIdx) == column) matches = true
                        }
                        if (count == 1 && matches) return idxName
                    }
                }
            }
            return null
        }

        private fun logDbIdentity(tag: String, context: Context, db: SupportSQLiteDatabase) {
            try {
                db.query("PRAGMA database_list").use { c0 ->
                    while (c0.moveToNext()) {
                        val alias = c0.getString(1)
                        val file = c0.getString(2)
                        Log.d("DB/$tag", "database_list alias=$alias file=$file")
                    }
                }
                val names = buildList {
                    db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c1 ->
                        while (c1.moveToNext()) add(c1.getString(0))
                    }
                }
                Log.d("DB/$tag", "tables=$names")

                if ("location_samples" in names) {
                    db.query("SELECT COUNT(*) FROM location_samples").use { c2 ->
                        if (c2.moveToFirst()) {
                            Log.d("DB/$tag", "location_samples.count=${c2.getLong(0)}")
                        }
                    }
                }
                if ("exported_days" in names) {
                    db.query("SELECT COUNT(*) FROM exported_days").use { c3 ->
                        if (c3.moveToFirst()) {
                            Log.d("DB/$tag", "exported_days.count=${c3.getLong(0)}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("DB/$tag", "logDbIdentity error", t)
            }
        }
    }
}

