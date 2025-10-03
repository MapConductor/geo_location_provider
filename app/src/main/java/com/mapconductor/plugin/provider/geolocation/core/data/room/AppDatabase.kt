package com.mapconductor.plugin.provider.geolocation.core.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationSample::class, ExportedDay::class],
    version = 3, // ★ 2 -> 3
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationSampleDao(): LocationSampleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        // ▼ 追加：v1 → v2 の移行（変更なしなら no-op）
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1→v2 でスキーマ変更が無い場合は空でOK。
                // もし v2 でテーブル/列追加があった場合は、ここに ALTER/CREATE を追記してください。
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_samples ADD COLUMN headingDeg REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN courseDeg REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN speedMps REAL")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssUsed INTEGER")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssTotal INTEGER")
                db.execSQL("ALTER TABLE location_samples ADD COLUMN gnssCn0Mean REAL")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "geolocation.db"
                )
                    // ★ 既存の MIGRATION_1_2 に加えて
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}
