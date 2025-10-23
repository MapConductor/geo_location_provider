package com.mapconductor.plugin.provider.geolocation.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationSample::class, ExportedDay::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun locationSampleDao(): LocationSampleDao
    // ★ 追加：定時バックアップ用の DAO を正式公開
    abstract fun exportedDayDao(): ExportedDayDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // 既存の 1→2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exported_days(
                      epochDay INTEGER NOT NULL PRIMARY KEY,
                      exportedLocal INTEGER NOT NULL DEFAULT 0,
                      uploaded INTEGER NOT NULL DEFAULT 0,
                      driveFileId TEXT,
                      lastError TEXT
                    )
                """.trimIndent())
            }
        }
        // 2→3：不足しがちな exported_days の新設を保証
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_samples ADD COLUMN headingDeg REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN courseDeg REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN speedMps REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssUsed INTEGER")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssTotal INTEGER")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssCn0Mean REAL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS exported_days (
                        epochDay INTEGER NOT NULL PRIMARY KEY,
                        exportedLocal INTEGER NOT NULL DEFAULT 0,
                        uploaded INTEGER NOT NULL DEFAULT 0,
                        driveFileId TEXT,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "geolocation.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
