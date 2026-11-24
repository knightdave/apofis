package xyz.rycerz.apofis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Cache of the server transcript plus the user's unsent messages. */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, PendingSendEntity::class, ActiveRunEntity::class, ReplyOutcomeEntity::class, NoticeOutboxEntity::class, NoticeStateEntity::class, ImageBindingEntity::class],
    version = 4,
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
    abstract fun imageBindingDao(): ImageBindingDao
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
                "`runId` TEXT NOT NULL, `notice` TEXT NOT NULL, `eventId` TEXT NOT NULL, `alerted` TEXT NOT NULL, PRIMARY KEY(`sessionKey`))",
        )
    }
}

/**
 * Version 4 adds the per-chat model choice, createStarted (taken from existsOnServer: a chat created before the upgrade
 * started its create), the image references of a pending send, the steer-only flag, the two message cut markers and the
 * image bindings. Every row is kept; defaults equal the exported schema.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "SessionEntity` ADD COLUMN `model` TEXT",
            "SessionEntity` ADD COLUMN `provider` TEXT",
            "SessionEntity` ADD COLUMN `reasoning` TEXT",
            "SessionEntity` ADD COLUMN `fast` INTEGER",
            "SessionEntity` ADD COLUMN `createStarted` INTEGER NOT NULL DEFAULT 0",
            "MessageEntity` ADD COLUMN `droppedToolCalls` INTEGER NOT NULL DEFAULT 0",
            "MessageEntity` ADD COLUMN `shortened` INTEGER NOT NULL DEFAULT 0",
            "PendingSendEntity` ADD COLUMN `images` TEXT NOT NULL DEFAULT ''",
            "PendingSendEntity` ADD COLUMN `steerOnly` INTEGER NOT NULL DEFAULT 0",
        ).forEach { db.execSQL("ALTER TABLE `$it") }
        db.execSQL("UPDATE SessionEntity SET createStarted = existsOnServer")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ImageBindingEntity` (`sessionKey` TEXT NOT NULL, `messageId` INTEGER NOT NULL, `digests` TEXT NOT NULL, " +
                "PRIMARY KEY(`sessionKey`, `messageId`))",
        )
    }
}

/** Every migration, oldest first: an upgrade from any released version keeps unsent work and runs. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

/** The app's database as users get it; no destructive fallback, so a missing migration fails instead of wiping data. */
fun openAppDatabase(context: Context, name: String = "apofis.db", migrations: Array<Migration> = ALL_MIGRATIONS): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build()
