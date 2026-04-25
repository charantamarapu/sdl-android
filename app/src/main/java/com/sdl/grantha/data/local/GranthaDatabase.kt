package com.sdl.grantha.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Sanskrit Digital Library.
 */
@Database(entities = [GranthaEntity::class], version = 1, exportSchema = false)
abstract class GranthaDatabase : RoomDatabase() {
    abstract fun granthaDao(): GranthaDao

    companion object {
        @Volatile
        private var INSTANCE: GranthaDatabase? = null

        fun getDatabase(context: Context): GranthaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GranthaDatabase::class.java,
                    "sdl_granthas.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
