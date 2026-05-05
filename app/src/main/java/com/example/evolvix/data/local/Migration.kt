package com.example.evolvix.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from database version 3 to 4
 * Changes:
 * - Adds colorScheme column to habits table
 * - Sets default color scheme to GREEN for existing habits
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add colorScheme column with default value GREEN
        // Using TEXT type for enum storage
        // NOT NULL constraint ensures data integrity
        database.execSQL("""
            ALTER TABLE habits 
            ADD COLUMN colorScheme TEXT NOT NULL 
            DEFAULT 'GREEN'
        """)
    }
}