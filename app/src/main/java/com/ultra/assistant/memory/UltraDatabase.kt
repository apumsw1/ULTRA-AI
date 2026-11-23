package com.ultra.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryRecordEntity::class,
        ConversationSessionEntity::class,
        ConversationMessageEntity::class,
        AuditLogEntity::class,
        AutomationRuleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class UltraDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun automationRuleDao(): AutomationRuleDao

    companion object {
        @Volatile
        private var instance: UltraDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_sessions` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "ALTER TABLE `conversation_messages` ADD COLUMN `sessionId` TEXT NOT NULL DEFAULT 'default'"
                )
                db.execSQL(
                    "ALTER TABLE `conversation_messages` ADD COLUMN `providerLabel` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): UltraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UltraDatabase::class.java,
                    "ultra_ai_memory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
