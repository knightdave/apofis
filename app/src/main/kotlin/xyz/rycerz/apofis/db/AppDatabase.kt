package xyz.rycerz.apofis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Cache of the server transcript plus the user's unsent messages. */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, PendingSendEntity::class, ActiveRunEntity::class, ReplyOutcomeEntity::class, NoticeOutboxEntity::class, NoticeStateEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun pendingSendDao(): PendingSendDao
    abstract fun activeRunDao(): ActiveRunDao
    abstract fun replyOutcomeDao(): ReplyOutcomeDao
    abstract fun noticeOutboxDao(): NoticeOutboxDao
    abstract fun noticeStateDao(): NoticeStateDao
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

/** Version 3 adds the notice outbox and the per-chat notice order; nothing else changes. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `NoticeOutboxEntity` (`eventId` TEXT NOT NULL, `sessionKey` TEXT NOT NULL, `runId` TEXT NOT NULL, " +
                "`runStartedAt` INTEGER NOT NULL, `notice` TEXT NOT NULL, `generation` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`generation`, `eventId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `NoticeStateEntity` (`sessionKey` TEXT NOT NULL, `generation` INTEGER NOT NULL, `runStartedAt` INTEGER NOT NULL, " +
                "`runId` TEXT NOT NULL, `notice` TEXT NOT NULL, `eventId` TEXT NOT NULL, PRIMARY KEY(`sessionKey`))",
        )
    }
}

/** Every migration, oldest first: an upgrade from any released version keeps unsent work and runs. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/** The app's database as users get it; no destructive fallback, so a missing migration fails instead of wiping data. */
fun openAppDatabase(context: Context, name: String = "apofis.db", migrations: Array<Migration> = ALL_MIGRATIONS): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build()
