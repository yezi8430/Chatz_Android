package asia.guojuice.yezigotify

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 消息本地存储
 *
 * 兼容策略：
 *   - Gotify 服务端：Chatz 扩展字段全为默认值，行为不变
 *   - Chatz 服务端：字段被填充，UI 按需展示
 *
 * Chatz 扩展字段说明：
 *   - channelId    : 消息所属频道 ID
 *   - tags         : 标签列表（JSON 数组字符串）
 *   - isRead       : 是否已读（本地维护，与服务端同步）
 *   - readAt       : 已读时间戳（毫秒，null=未读）
 *   - archivedAt   : 归档时间戳（毫秒，null=未归档）
 *   - aggCount     : 聚合条数（>=1，1 表示未聚合）
 *   - aggLastAt    : 最后一次聚合的时间戳
 *   - aggChildren  : 聚合子消息列表（JSON 数组字符串）
 *
 * ⚠️ 新列全部有 DEFAULT，保证 Room 迁移时不需要 drop 表。
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["date"]),
        // 收藏筛选专用复合索引
        Index(value = ["starred", "date"]),
        // App 筛选专用复合索引
        Index(value = ["appid", "date"]),
        // Chatz 频道筛选专用
        Index(value = ["channel_id", "date"]),
        // Chatz 未读筛选专用
        Index(value = ["is_read", "date"]),
        // 多服务器：来源 + 远程 id 唯一（same server 内远程 id 唯一）
        // ⚠️ 索引名必须与 MIGRATION_8_9 里 CREATE INDEX 的名字逐字一致
        Index(value = ["server_id", "remote_id"], name = "index_messages_server_remote", unique = true),
        // 多服务器：按来源 + 时间查询 / 裁剪
        Index(value = ["server_id", "date"], name = "index_messages_server_date")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    val appid: Int,
    val title: String?,
    val message: String,
    val priority: Int,
    val date: String,
    val extras: String?,  // Gson 序列化存储
    val starred: Boolean = false,
    val receivedAt: Long = 0L,

    // ==================== Chatz 扩展字段 ====================
    @ColumnInfo(name = "channel_id")
    val channelId: Int? = null,

    @ColumnInfo(name = "tags")
    val tags: String? = null,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "read_at")
    val readAt: Long? = null,

    /**
     * 本地已读状态是否**已经和服务端对齐过**
     *
     * - false：本地点了已读但还没成功上报 → 同步时**本地赢**（补偿上报失败）
     * - true ：本地已读是"和对过账"的状态 → 同步时若服务端说未读，**服务端赢**
     *
     * 为什么需要它：同步对已存在消息走 `mergeReadState`，若只按「已读优先」，
     * 本地一旦存着旧身份的已读就永远不会被纠正 —— 表现为"客户端已读、服务端未读"。
     *
     * 旧身份的已读来源：服务端已读按 **user_id** 存（message_reads），换 token 即
     * 换身份；而本地 messages 按 `(server_id, remote_id)` 存、不区分身份，于是
     * 同一台服务器的消息在换 token 前后共用同一批本地行。详见
     * AppDatabase.MIGRATION_9_10 与 MessageDao.resetReadStateForServer。
     */
    @ColumnInfo(name = "read_synced")
    val readSynced: Boolean = true,

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    @ColumnInfo(name = "agg_count")
    val aggCount: Int = 1,

    @ColumnInfo(name = "agg_last_at")
    val aggLastAt: Long? = null,

    @ColumnInfo(name = "agg_children")
    val aggChildren: String? = null,

    // ==================== 多服务器来源字段 ====================
    // slot 0（旧配置迁移出的服务器）时 id 恒等于 remote_id，旧数据零改写。
    @ColumnInfo(name = "server_id")
    val serverId: String = "",

    @ColumnInfo(name = "remote_id")
    val remoteId: Long = 0L
)