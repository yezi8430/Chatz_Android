package asia.guojuice.yezigotify

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        MessageEntity::class,
        MessageFtsEntity::class,
        ChannelEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_starred_date " +
                            "ON messages (starred, date)"
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_appid_date " +
                            "ON messages (appid, date)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_starred_date " +
                            "ON messages (starred, date)"
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_appid_date " +
                            "ON messages (appid, date)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "ALTER TABLE messages ADD COLUMN receivedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` " +
                            "USING FTS4(`title` TEXT, `message` TEXT, " +
                            "content=`messages`)"
                )
                connection.executeSQL(
                    "INSERT INTO `messages_fts`(`docid`, `title`, `message`) " +
                            "SELECT `id`, `title`, `message` FROM `messages`"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_" +
                            "messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` " +
                            "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_" +
                            "messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` " +
                            "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_" +
                            "messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` " +
                            "BEGIN INSERT INTO `messages_fts`(`docid`, `title`, `message`) " +
                            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`message`); END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_" +
                            "messages_fts_AFTER_INSERT AFTER INSERT ON `messages` " +
                            "BEGIN INSERT INTO `messages_fts`(`docid`, `title`, `message`) " +
                            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`message`); END"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL("DROP TABLE IF EXISTS `messages_fts`")
                connection.executeSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE")
                connection.executeSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE")
                connection.executeSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE")
                connection.executeSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT")

                connection.executeSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` " +
                            "USING FTS5(`title`, `message`, content=`messages`, tokenize=`trigram`)"
                )
                connection.executeSQL(
                    "INSERT INTO `messages_fts`(`rowid`, `title`, `message`) " +
                            "SELECT `id`, `title`, `message` FROM `messages`"
                )
            }
        }

        /**
         * v7 → v8：Chatz 扩展
         *
         * 1. messages 表加 8 个新列（全部 nullable 或带 DEFAULT）
         * 2. messages 表加 2 个新索引
         * 3. 新建 channels 表 + 索引
         *
         * 关于 FTS 表：
         *   v6→v7 时建立了 FTS5 表，声明 content=messages。
         *   本次新增列不影响 FTS 索引（FTS 只关心 title/message 两列），
         *   所以不需要重建 FTS。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // ===== 1. messages 表新列 =====
                connection.executeSQL("ALTER TABLE messages ADD COLUMN channel_id INTEGER")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN tags TEXT")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 0")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN read_at INTEGER")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN archived_at INTEGER")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN agg_count INTEGER NOT NULL DEFAULT 1")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN agg_last_at INTEGER")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN agg_children TEXT")

                // ===== 2. messages 表新索引 =====
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_channel_id_date " +
                            "ON messages (channel_id, date)"
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_is_read_date " +
                            "ON messages (is_read, date)"
                )

                // ===== 3. channels 表 =====
                connection.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channels` (
                        `id`            INTEGER NOT NULL,
                        `name`          TEXT NOT NULL,
                        `description`   TEXT,
                        `image`         TEXT,
                        `is_public`     INTEGER NOT NULL DEFAULT 0,
                        `creator_id`    INTEGER,
                        `created_at`    INTEGER NOT NULL DEFAULT 0,
                        `unread_count`  INTEGER NOT NULL DEFAULT 0,
                        `subscribed`    INTEGER NOT NULL DEFAULT 1,
                        `muted`         INTEGER NOT NULL DEFAULT 0,
                        `updated_at`    INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_subscribed_name " +
                            "ON channels (subscribed, name)"
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_id " +
                            "ON channels (id)"
                )
            }
        }

        /**
         * v8 → v9：多服务器来源字段
         *
         * 1. messages / channels 各加 2 列（server_id / remote_id），并把存量数据回填成
         *    slot 0 的两台 legacy 服务器（appid>0 → legacy-gotify，appid==0 → legacy-ntfy；
         *    频道全部来自协议服务器）。slot 0 时 `id == remote_id`，所以 remote_id 直接取 id，
         *    旧行 id / starred / is_read / archived_at / FTS rowid 关联全部不动。
         * 2. 建索引（名字必须与实体 @Entity(indices=...) 声明**逐字一致**）。
         * 3. 重建 FTS 索引 + 补建内容同步触发器（见下方说明）。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // ===== 1. messages 新列 + 回填 =====
                connection.executeSQL("ALTER TABLE messages ADD COLUMN server_id TEXT NOT NULL DEFAULT ''")
                connection.executeSQL("ALTER TABLE messages ADD COLUMN remote_id INTEGER NOT NULL DEFAULT 0")
                connection.executeSQL(
                    "UPDATE messages SET server_id='legacy-gotify', remote_id=id WHERE appid > 0"
                )
                connection.executeSQL(
                    "UPDATE messages SET server_id='legacy-ntfy', remote_id=id WHERE appid = 0"
                )

                // ===== 2. messages 新索引 =====
                connection.executeSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_server_remote " +
                            "ON messages (server_id, remote_id)"
                )
                connection.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_server_date " +
                            "ON messages (server_id, date)"
                )

                // ===== 3. channels 新列 + 回填（旧频道全部来自协议服务器） =====
                connection.executeSQL("ALTER TABLE channels ADD COLUMN server_id TEXT NOT NULL DEFAULT ''")
                connection.executeSQL("ALTER TABLE channels ADD COLUMN remote_id INTEGER NOT NULL DEFAULT 0")
                connection.executeSQL("UPDATE channels SET server_id='legacy-gotify', remote_id=id")

                // ===== 4. channels 新索引 =====
                connection.executeSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_channels_server_remote " +
                            "ON channels (server_id, remote_id)"
                )

                // ===== 5. FTS 重建 + 补建触发器 =====
                // 历史遗留：MIGRATION_6_7 重建 FTS5 表时把 4 个 room_fts_content_sync_* 触发器
                // DROP 掉了却没重建，导致 v7 之后新插入的消息从未进过 FTS 索引（表现为"新消息搜不到"）。
                // 这里一次性把存量数据灌进索引（补历史遗留），并把触发器补回来
                // （保证此后新消息的 insert / update / delete 都能自动同步 FTS）。
                connection.executeSQL("DELETE FROM messages_fts")
                connection.executeSQL(
                    "INSERT INTO messages_fts(rowid, title, message) SELECT id, title, message FROM messages"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE " +
                            "BEFORE UPDATE ON messages " +
                            "BEGIN DELETE FROM messages_fts WHERE rowid = OLD.rowid; END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE " +
                            "BEFORE DELETE ON messages " +
                            "BEGIN DELETE FROM messages_fts WHERE rowid = OLD.rowid; END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE " +
                            "AFTER UPDATE ON messages " +
                            "BEGIN INSERT INTO messages_fts(rowid, title, message) " +
                            "VALUES (NEW.rowid, NEW.title, NEW.message); END"
                )
                connection.executeSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT " +
                            "AFTER INSERT ON messages " +
                            "BEGIN INSERT INTO messages_fts(rowid, title, message) " +
                            "VALUES (NEW.rowid, NEW.title, NEW.message); END"
                )
            }
        }

        /**
         * v9 → v10：「已读是否已上报服务端」确认位
         *
         * 背景（本列要解决的问题）：
         *   同步时对已存在的消息走 `mergeReadState`，原策略是「**已读优先**」
         *   （任一方已读即已读）。那是为了修「本地点了已读但上报失败，之后同步被
         *   服务端未读覆盖」的问题，代价是：**只要本地和服务端的已读状态不一致，
         *   本地永远赢、且不会纠正**。
         *
         *   换 Token 恰好是制造这种不一致的高发时刻。根因（已实测定位）：
         *   服务端已读是按 **user_id** 存的（`message_reads` 表，见 GET /message 的
         *   `LEFT JOIN message_reads r ON r.message_id = m.id AND r.user_id = ?`），
         *   换 token 就是**换用户身份**；而本地 `messages` 是按 `(server_id, remote_id)`
         *   存的、**不区分身份** —— 同一台服务器换 token 前后共用同一批本地行。
         *   于是旧身份读过的消息（is_read=1）留在本地，服务端按新身份回未读，
         *   却被「已读优先」永久压住。表现就是用户报的：
         *   「换 token 后**新消息**变成已读了，但服务端看还是未读」。
         *
         *   实例（服务端实查）：users = 1 yezi / 2 alice / 3 bob，
         *   message_reads 只有 user_id 1（28 条）和 3（11 条），alice 一条都没有
         *   → alice 拉任何消息服务端都回未读，但本地挂着 yezi 时代的已读。
         *
         * 新策略：用本列区分「本地已读是待上报的意图」还是「已经和服务端对齐过的状态」
         *   - `read_synced = 0` 且本地已读 → 本地赢（上报失败的补偿，保持旧行为）
         *   - `read_synced = 1` 且本地已读、服务端未读 → **服务端赢**（服务端权威）
         *
         * 存量数据回填为 1：老数据里的已读都当作"曾经对齐过"，
         * 这样换 Token 造成的旧身份已读会被服务端纠正回来（修 bug 的正向作用）。
         * 唯一副作用：升级瞬间若正好有一条"本地点了已读、上报失败"的消息，
         * 会被服务端纠正成未读 —— 用户重新点一次即可，代价远小于旧已读永久留存。
         *
         * 配套根治：换 token 时主动作废该服务器的本地已读
         * （MessageDao.resetReadStateForServer），不等同步慢慢纠正。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "ALTER TABLE messages ADD COLUMN read_synced INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * v10 → v11：频道订阅密码
         *
         * channels 加 password_protected（只存「有没有密码」这个布尔，
         * 密码本身从不落客户端本地 —— 和服务端只回 passwordProtected 一个道理）。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.executeSQL(
                    "ALTER TABLE channels ADD COLUMN password_protected INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gotify_database"
                )
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}