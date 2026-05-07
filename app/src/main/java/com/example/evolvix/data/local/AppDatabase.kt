package com.example.evolvix.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity

@TypeConverters(Converters::class)
@Database(
    entities = [
        HabitEntity::class,
        HabitCompletionEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Function to get database instance
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Build database instance
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "habit_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    .enableMultiInstanceInvalidation()
                    .build()

                INSTANCE = instance
                instance
            }
        }

        fun destroyInstance() {
            INSTANCE = null
        }
    }
}