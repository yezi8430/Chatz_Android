package asia.guojuice.yezigotify

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    // ==================== 查询 ====================

    /** 某台服务器的已订阅频道列表（抽屉用），按 id 排序保持稳定 */
    @Query("SELECT * FROM channels WHERE subscribed = 1 AND server_id = :serverId ORDER BY id ASC")
    suspend fun getSubscribed(serverId: String): List<ChannelEntity>

    /** 某台服务器的已订阅频道列表（响应式，UI 可直接 collect） */
    @Query("SELECT * FROM channels WHERE subscribed = 1 AND server_id = :serverId ORDER BY id ASC")
    fun observeSubscribed(serverId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ChannelEntity?

    // ==================== 写入 ====================

    /**
     * upsert：主键冲突时整行替换
     *
     * 用途：
     *   - /channel 全量替换
     *   - /channel/discover upsert
     *   - WS channelCreated/Updated 增量更新
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: ChannelEntity)

    /**
     * 把某台服务器本地所有 subscribed=1 的标记为 subscribed=0（临时降级）
     *
     * 配合 upsertAll 完成"用服务端列表全量替换本地已订阅记录"：
     *   1. demoteAllSubscribed(serverId, now)   ← 先把这台旧的降级
     *   2. upsertAll(服务端返回的列表)          ← 真正订阅的写回 subscribed=1
     *
     * 注意：**刻意不删除 subscribed=0 的记录** —— 它是"这个频道存在、但我没订阅"
     * 这一事实的依据（新消息入库时据此过滤掉未订阅频道的消息）。
     * 删掉的话，退订过的频道一来新消息就会重新出现在列表里。
     *
     * ⚠️ 两步必须在同一个事务里，调用方用 withTransaction 包裹。
     */
    @Query(
        "UPDATE channels SET subscribed = 0, updated_at = :now " +
                "WHERE subscribed = 1 AND server_id = :serverId"
    )
    suspend fun demoteAllSubscribed(serverId: String, now: Long)

    /** 删除某台服务器的全部频道缓存（注意：不是整表清空，别跨服务器误删） */
    @Query("DELETE FROM channels WHERE server_id = :serverId")
    suspend fun deleteAll(serverId: String)

    /**
     * 按 ID 删除单个频道（WS channelDeleted 事件处理用）
     */
    @Query("DELETE FROM channels WHERE id = :channelId")
    suspend fun deleteChannelById(channelId: Int)

    // ==================== 局部更新 ====================

    @Query("UPDATE channels SET unread_count = :count, updated_at = :now WHERE id = :channelId")
    suspend fun updateUnreadCount(channelId: Int, count: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE channels SET subscribed = :subscribed, updated_at = :now WHERE id = :channelId")
    suspend fun updateSubscribed(channelId: Int, subscribed: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE channels SET muted = :muted, updated_at = :now WHERE id = :channelId")
    suspend fun updateMuted(channelId: Int, muted: Boolean, now: Long = System.currentTimeMillis())

    /**
     * 批量清零某台服务器的未读（用于 /message/read-all 后）
     *
     * ⚠️ 刻意**不带** `subscribed = 1` 条件。
     *
     * 原来带这个条件，后果是：管理员/未订阅频道在服务端点了「全部已读」后，
     * 这条 UPDATE 匹配 0 行（SQLite 不报错，静默成功），channels 表一个字节都没变，
     * 于是未读徽标不刷新，看起来"有时生效有时不生效"。
     *
     * 未读数归零不该被订阅状态挡住：既然服务端已把该服务器的消息全标已读，
     * 本地频道表里的计数就该跟着归零，否则徽标会和列表内容自相矛盾。
     */
    @Query(
        "UPDATE channels SET unread_count = 0, updated_at = :now " +
                "WHERE server_id = :serverId"
    )
    suspend fun clearAllUnread(serverId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE channels SET unread_count = 0, updated_at = :now WHERE id = :channelId")
    suspend fun clearUnread(channelId: Int, now: Long = System.currentTimeMillis())
}