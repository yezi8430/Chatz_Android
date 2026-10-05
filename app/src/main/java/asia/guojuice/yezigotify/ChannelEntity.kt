package asia.guojuice.yezigotify

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Chatz 频道本地缓存
 *
 * 设计要点：
 *   - 主键用服务端的 channelId（与 Gotify 的 appid 语义独立）
 *   - subscribed=true 的记录来自 /channel（已订阅）
 *   - subscribed=false 的记录有两个来源（2026-09-30 起）：
 *       1. /channel/discover —— 只是浏览过，没订阅
 *       2. /channel —— **超管**会拿到全部频道，其中未订阅的 subscribed=false
 *          （服务端刻意保留：超管要能管理和排障，只是消息推送/未读数按订阅走）
 *     ⇒ 别假设 subscribed=false 只来自 discover。抽屉只查 subscribed=1，
 *       所以这两种都不会显示在抽屉里。
 *   - unread_count 是动态数据，由 /message/unread-counts 或 WS 事件更新
 *     （2026-09-30 起服务端对未订阅频道一律返回 0）
 *   - updated_at 记录本次写入时间，调试用，不参与业务逻辑
 *
 * 离线策略：
 *   - App 启动从 DB 读，离线也可见
 *   - 有网时全量替换，保证数据新鲜
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["subscribed", "name"]),
        Index(value = ["id"]),
        // 多服务器：来源 + 远程频道 id 唯一
        // ⚠️ 索引名必须与 MIGRATION_8_9 里 CREATE INDEX 的名字逐字一致
        Index(value = ["server_id", "remote_id"], name = "index_channels_server_remote", unique = true)
    ]
)
data class ChannelEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "image")
    val image: String? = null,

    @ColumnInfo(name = "is_public")
    val isPublic: Boolean = false,

    /** 是否设了订阅密码（服务端只回「有没有」，不回密码本身） */
    @ColumnInfo(name = "password_protected")
    val passwordProtected: Boolean = false,

    @ColumnInfo(name = "creator_id")
    val creatorId: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    @ColumnInfo(name = "subscribed")
    val subscribed: Boolean = true,

    @ColumnInfo(name = "muted")
    val muted: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,

    // ==================== 多服务器来源字段 ====================
    // slot 0（旧配置迁移出的服务器）时 id 恒等于 remote_id。
    @ColumnInfo(name = "server_id")
    val serverId: String = "",

    @ColumnInfo(name = "remote_id")
    val remoteId: Int = 0
)