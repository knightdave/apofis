package xyz.rycerz.apofis.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Cache of the server transcript plus the user's unsent messages. */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, PendingSendEntity::class, ActiveRunEntity::class, ReplyOutcomeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun pendingSendDao(): PendingSendDao
    abstract fun activeRunDao(): ActiveRunDao
    abstract fun replyOutcomeDao(): ReplyOutcomeDao
}
