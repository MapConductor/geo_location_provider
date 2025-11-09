package com.mapconductor.plugin.provider.geolocation.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

//@Database(
//    entities = [ExportedDay::class], // ★ LocationSample は含めない
//    version = 1,
//    exportSchema = true
//)
//abstract class AppDatabase : RoomDatabase() {
//    abstract fun exportedDayDao(): ExportedDayDao
//
//    companion object {
//        @Volatile private var INSTANCE: AppDatabase? = null
//
//        fun get(context: Context): AppDatabase =
//            INSTANCE ?: synchronized(this) {
//                INSTANCE ?: Room.databaseBuilder(
//                    context.applicationContext,
//                    AppDatabase::class.java,
//                    // ★ datamanager 用の別DB名に分離
//                    "datamanager.db"
//                )
//                    .fallbackToDestructiveMigration()
//                    .build()
//                    .also { INSTANCE = it }
//            }
//    }
//}

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ExportedDay::class], // ここに ExportedDayDao の対象を列挙
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exportedDayDao(): ExportedDayDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "datamanager.db"  // ★ ExportedDay 用は別 DB 名に固定（被らせない）
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            logDbIdentity("GeoDb", context, db)
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = inst
                inst
            }
        }

        private fun logDbIdentity(tag: String, context: Context, db: SupportSQLiteDatabase) {
            try {
                val c0 = db.query("PRAGMA database_list")
                while (c0.moveToNext()) {
                    val alias = c0.getString(1)
                    val file = c0.getString(2)
                    Log.d("DB/$tag", "database_list alias=$alias file=$file")
                }
                c0.close()

                val c1 = db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                val names = buildList {
                    while (c1.moveToNext()) add(c1.getString(0))
                }
                c1.close()
                Log.d("DB/$tag", "tables=$names")

                if (names.contains("exported_days")) {
                    val c2 = db.query("SELECT COUNT(*) FROM exported_days")
                    if (c2.moveToFirst()) {
                        Log.d("DB/$tag", "exported_days.count=${c2.getLong(0)}")
                    }
                    c2.close()
                }
            } catch (t: Throwable) {
                Log.e("DB/$tag", "logDbIdentity error", t)
            }
        }
    }
}
