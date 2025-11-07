package com.mapconductor.plugin.provider.storageservice.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LocationSample::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationSampleDao(): LocationSampleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // ★ DB名を geolocation.db → storageservice.db に変更（coreと切り離す）
        private const val DB_NAME = "storageservice.db"

        fun get(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // 安定運用に移ったらマイグレーション実装へ切替を推奨
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
