package xyz.rycerz.apofis.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Cache of the server transcript plus the user's unsent messages. */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, PendingSendEntity::class, ActiveRunEntity::class, ReplyOutcomeEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun pendingSendDao(): PendingSendDao
    abstract fun activeRunDao(): ActiveRunDao
    abstract fun replyOutcomeDao(): ReplyOutcomeDao
}

/**
 * Version 1 stored tool results and tool call arguments uncut, and a row too large for a cursor window makes its chat
 * unreadable on every open. The schema is the same; the cached transcripts are dropped and every chat is marked stale,
 * so each is fetched again (and stored cut) when it is opened. Unsent messages, runs and drafts are kept.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM MessageEntity")
        db.execSQL("UPDATE SessionEntity SET stale = 1")
    }
}
