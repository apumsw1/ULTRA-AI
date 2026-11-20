package com.ultra.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryRecordEntity::class,
        ConversationMessageEntity::class,
        AuditLogEntity::class,
        AutomationRuleEntity::class
    ],
    version = 1,
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

        fun getInstance(context: Context): UltraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UltraDatabase::class.java,
                    "ultra_ai_memory.db"
                ).build().also { instance = it }
            }
        }
    }
}
