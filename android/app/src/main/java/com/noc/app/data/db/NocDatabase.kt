package com.noc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PcEntity::class, ConversationEntity::class, MessageEntity::class, PresetEntity::class, PromptEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class NocDatabase : RoomDatabase() {
    abstract fun pcs(): PcDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun presets(): PresetDao
    abstract fun prompts(): PromptDao

    companion object {
        fun build(context: Context): NocDatabase =
            Room.databaseBuilder(context, NocDatabase::class.java, "noc.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: nome amigável do modelo em cada resposta. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
            }
        }
    }
}
