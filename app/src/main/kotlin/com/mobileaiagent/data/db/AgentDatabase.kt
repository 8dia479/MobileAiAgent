package com.mobileaiagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database holding tasks, events, runs, messages, the tool journal,
 * checkpoints, the model cache, projects and plugin records.
 *
 * Large binary artefacts live on the filesystem; only paths/metadata are stored
 * here (spec §11).
 */
@Database(
    entities = [
        TaskEntity::class,
        EventEntity::class,
        RunEntity::class,
        MessageEntity::class,
        JournalEntity::class,
        CheckpointEntity::class,
        ModelEntity::class,
        CapabilityOverrideEntity::class,
        ProjectEntity::class,
        PluginEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun eventDao(): EventDao
    abstract fun runDao(): RunDao
    abstract fun messageDao(): MessageDao
    abstract fun journalDao(): JournalDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun modelDao(): ModelDao
    abstract fun projectDao(): ProjectDao
    abstract fun pluginDao(): PluginDao

    companion object {
        fun build(context: Context): AgentDatabase =
            Room.databaseBuilder(context, AgentDatabase::class.java, "mobileaiagent.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}

/** Example migration placeholder for future schema bumps (kept for documentation). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Reserved: schema changes in later versions must be added here rather
        // than relying on destructive migration for user task history.
    }
}
