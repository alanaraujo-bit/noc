package com.noc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PcEntity::class, ConversationEntity::class, MessageEntity::class, PresetEntity::class, PromptEntity::class, CreationEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class NocDatabase : RoomDatabase() {
    abstract fun pcs(): PcDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun presets(): PresetDao
    abstract fun prompts(): PromptDao
    abstract fun creations(): CreationDao

    companion object {
        fun build(context: Context): NocDatabase =
            Room.databaseBuilder(context, NocDatabase::class.java, "noc.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** v2: nome amigável do modelo em cada resposta. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
            }
        }

        /** v3: imagens geradas (mensagem de imagem + galeria Criações). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageJson TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `creations` (`id` TEXT NOT NULL, `conversationId` TEXT, `messageId` TEXT, `jobId` TEXT,
                       `pcId` TEXT, `prompt` TEXT NOT NULL, `finalPrompt` TEXT, `model` TEXT NOT NULL, `modelName` TEXT NOT NULL,
                       `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `steps` INTEGER, `guidance` REAL, `seed` INTEGER,
                       `format` TEXT, `quality` TEXT, `createdAt` INTEGER NOT NULL, `genMs` INTEGER, `bytes` INTEGER NOT NULL,
                       `sha256` TEXT, `path` TEXT, `thumbPath` TEXT, `favorite` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_creations_createdAt` ON `creations` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_creations_conversationId` ON `creations` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_creations_messageId` ON `creations` (`messageId`)")
            }
        }
    }
}
