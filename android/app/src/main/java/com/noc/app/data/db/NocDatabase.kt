package com.noc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PcEntity::class, ConversationEntity::class, MessageEntity::class, PresetEntity::class, PromptEntity::class],
    version = 1,
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
                .build()
    }
}
