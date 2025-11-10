package com.mapconductor.plugin.provider.storageservice.room

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * storageservice の統合 AppDatabase。
 * - DB 名: geolocation.db
 * - エンティティ: LocationSample, ExportedDay
 * - バージョン: 6（v5 -> v6 で exported_days を追加）
 */
@Database(
    entities = [
        LocationSample::class,
        ExportedDay::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

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
                        MIGRATION_5_6
                    )
                    .build()
                INSTANCE = inst
                inst
            }
        }

        // --- v1 -> v2（現状 no-op） ---
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op
            }
        }

        // --- v2 -> v3：headingDeg を存在チェック付きで追加 ---
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "location_samples", "headingDeg", "REAL")
            }
        }

        // --- v3 -> v4：旧ユニーク索引(timeMillis 単独)を整理 ---
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val oldIdx = findUniqueIndexOnSingleColumn(db, "location_samples", "timeMillis")
                if (oldIdx != null) {
                    db.execSQL("DROP INDEX IF EXISTS `$oldIdx`")
                    Log.d("DB/Migration", "dropped old unique index: $oldIdx")
                }
                // 複合 UNIQUE 作成は v4->v5 で
            }
        }

        // --- v4 -> v5：Room 期待名で複合 UNIQUE を整備 ---
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
                    Log.d("DB/Migration", "expected index already exists: $expected")
                }
            }
        }

        // --- v5 -> v6：ExportedDay（exported_days）を追加
        // ＊デフォルト値・インデックスは付けない（現在の @Entity に合わせる）
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exported_days`(
                        `epochDay` INTEGER NOT NULL,
                        `exportedLocal` INTEGER NOT NULL,
                        `uploaded` INTEGER NOT NULL,
                        `driveFileId` TEXT,
                        `lastError` TEXT,
                        PRIMARY KEY(`epochDay`)
                    )
                    """.trimIndent()
                )
                // インデックスは作らない（@Entity 側で未定義のため）
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
                Log.d("DB/Migration", "Column `$column` already exists in `$table` – skipped")
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
                    if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
                }
            }
            return false
        }

        private fun hasIndex(db: SupportSQLiteDatabase, indexName: String): Boolean {
            db.query("PRAGMA index_list(`location_samples`)").use { list ->
                val nameIdx = list.getColumnIndex("name")
                while (list.moveToNext()) {
                    if (indexName == list.getString(nameIdx)) return true
                }
            }
            return false
        }

        private fun findUniqueIndexOnSingleColumn(
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
