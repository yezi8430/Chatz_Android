package asia.guojuice.yezigotify

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import kotlinx.coroutines.flow.Flow

data class AppMessageCount(
    val appid: Int,
    val count: Int
)

data class ChannelMessageCount(
    val channelId: Int,
    val count: Int
)

data class IdStarredPair(
    val id: Long,
    val starred: Boolean
)

/**
 * 同步前捞回的「本地已读状态」快照
 *
 * 为什么需要：`upsert` 是 REPLACE（整行覆盖），服务端返回的 `is_read` 会把本地已读冲掉。
 * 最容易踩到的场景是 —— 本地点了已读、但上报服务端失败（网络抖动 / 静默失败），
 * 服务端那条仍是未读，下次同步一覆盖，用户就看到「已读样式没了」。
 * 所以覆盖前先把本地状态捞出来，合并回去（见 MessageViewModel 的 mergeReadState）。
 *
 * [readSynced] 决定合并方向：未对齐（false）时本地赢，已对齐（true）时服务端赢。
 */
data class IdReadPair(
    val id: Long,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "read_at") val readAt: Long?,
    @ColumnInfo(name = "read_synced") val readSynced: Boolean
)

/**
 * 待同步的收藏变更
 */
data class PendingStarSync(
    val id: Long,
    val starred: Boolean
)

@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
@Dao
interface MessageDao {

    // ==================== 基础插入 ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    // ==================== 查询 ====================

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    fun getExistingIds(ids: List<Long>): List<Long>

    @Query("SELECT id, starred FROM messages WHERE id IN (:ids)")
    suspend fun getIdStarredPairs(ids: List<Long>): List<IdStarredPair>

    @Query("SELECT id, is_read, read_at, read_synced FROM messages WHERE id IN (:ids)")
    suspend fun getIdReadPairs(ids: List<Long>): List<IdReadPair>

    /**
     * 某台服务器已入库的最大**远程** id（增量同步的 since 水位线）
     *
     * ⚠️ 绝不能在多服务器下用全局 MAX(id)：命名空间下 id 是 slot 优先排序，
     *    会导致某台服务器的 since 被设成巨大值、再也拉不到新消息。
     */
    @Query("SELECT MAX(remote_id) FROM messages WHERE server_id = :serverId")
    fun getLatestRemoteId(serverId: String): Long?

    @Query(
        "SELECT * FROM messages ORDER BY date DESC, receivedAt DESC, id DESC LIMIT :limit"
    )
    fun getRecentMessagesSync(limit: Int): List<MessageEntity>

    /**
     * 待同步收藏变更查询
     *
     * starred（用户意图）与 archived_at（服务端确认）不一致 = 需要推送：
     *   - starred=1 且 archived_at IS NULL  → 推 archive
     *   - starred=0 且 archived_at 非空     → 推 unarchive
     */
    @Query(
        "SELECT id, starred FROM messages WHERE server_id = :serverId AND (" +
                "(starred = 1 AND archived_at IS NULL) OR " +
                "(starred = 0 AND archived_at IS NOT NULL))"
    )
    suspend fun getPendingStarSync(serverId: String): List<PendingStarSync>

    // ==================== 抽屉统计（Flow） ====================
    //
    // ⚠️ 这里的"条数"统计的都是【消息总数】，不是未读数 ——
    // 抽屉徽标按需求显示的是"这个范围一共存了多少条本地消息"。
    // 未读数只在一处用：observeUnreadCount()，喂给「未读」那个入口。

    @Query("SELECT COUNT(*) FROM messages")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE starred = 1")
    fun observeStarredCount(): Flow<Int>

    /** 某台服务器的每个 App 的消息总数（抽屉里的应用徽标用） */
    @Query("SELECT appid, COUNT(*) as count FROM messages WHERE server_id = :serverId GROUP BY appid")
    fun observeAppCountsByServer(serverId: String): Flow<List<AppMessageCount>>

    /** 每个频道的消息总数（抽屉里的频道徽标用） */
    @Query(
        "SELECT channel_id as channelId, COUNT(*) as count FROM messages " +
                "WHERE channel_id IS NOT NULL GROUP BY channel_id"
    )
    fun observeChannelMessageCounts(): Flow<List<ChannelMessageCount>>

    /**
     * 每个频道的未读数
     *
     * ⚠️ 只用来判断"这个频道有没有未读"（决定徽标要不要变淡红）。
     * 徽标上显示的数字仍然取 observeChannelMessageCounts() 的总数。
     */
    @Query(
        "SELECT channel_id as channelId, COUNT(*) as count FROM messages " +
                "WHERE channel_id IS NOT NULL AND is_read = 0 AND archived_at IS NULL " +
                "GROUP BY channel_id"
    )
    fun observeChannelUnreadCounts(): Flow<List<ChannelMessageCount>>

    /**
     * 未读总数（只统计传入的 Chatz 服务器）
     *
     * 为什么要 server 过滤：只有 Chatz 才有"已读"语义；Gotify / ntfy 的
     * is_read 恒为 0，不过滤的话会把它们的消息全算成未读。
     */
    @Query(
        "SELECT COUNT(*) FROM messages " +
                "WHERE is_read = 0 AND archived_at IS NULL AND server_id IN (:chatzServerIds)"
    )
    fun observeUnreadCount(chatzServerIds: List<String>): Flow<Int>

    // ==================== 删除 ====================

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE id = :msgId")
    suspend fun deleteById(msgId: Long)

    /**
     * 删除某台服务器的全部本地消息
     *
     * 用途：删卡 / 改地址时必须先清该 serverId 的数据，
     * 否则新服务器远程 id 会撞上残留本地 id（insert 走 IGNORE，消息被静默丢弃）。
     */
    @Query("DELETE FROM messages WHERE server_id = :serverId")
    suspend fun deleteByServer(serverId: String)

    /**
     * 删除某台服务器某个频道的全部本地消息
     *
     * 用途：退订频道时清掉该频道的消息，让列表和未读数立刻干净。
     * 数据不会真丢：重新订阅后服务端仍能同步回来。
     */
    @Query("DELETE FROM messages WHERE server_id = :serverId AND channel_id = :channelId")
    suspend fun deleteByServerAndChannel(serverId: String, channelId: Int): Int

    /**
     * 按「远程 id」删除某台服务器的消息
     *
     * 用途：ntfy 的 `message_delete` / `message_clear` 事件。
     *
     * 为什么必须按 `server_id + remote_id` 删，而不是用 `deleteById(本地id)`：
     * ntfy 事件里给的是它自己的消息 id（远程 id）。本地 id 是
     * `makeMessageId(slot, remoteId)` 的命名空间化结果，两条路都能算出来，
     * 但按 `remote_id` 查更稳 —— 它正是 `index_messages_server_remote`
     * 这个 UNIQUE 索引的列，命中索引且不依赖 slot 换算是否正确。
     *
     * 按 server 限定同样重要：不同协议/服务器的远程 id 会重号，
     * 少了这个条件会误删别的服务器的消息。
     */
    @Query("DELETE FROM messages WHERE server_id = :serverId AND remote_id IN (:remoteIds)")
    suspend fun deleteByServerAndRemoteIds(serverId: String, remoteIds: List<Long>): Int

    /**
     * ntfy 的 `message_clear`（topic 清空）事件：删掉该服务器的全部 ntfy 消息
     *
     * 判别「是 ntfy 消息」用 `remote_id > 0 AND channel_id IS NULL`
     * —— Chatz 消息带 channel_id，Gotify 消息的 remote_id 与 channel 语义不同，
     * 只有 ntfy 这条路是「无频道 + 有远程 id」。
     */
    @Query("DELETE FROM messages WHERE server_id = :serverId AND channel_id IS NULL AND remote_id > 0")
    suspend fun deleteNtfyMessagesByServer(serverId: String): Int

    /** 某台服务器里「非收藏 + 有 appid（协议服务器消息）」的本地 id，全量同步尾部清理用 */
    @Query(
        "SELECT id FROM messages " +
                "WHERE appid > 0 AND starred = 0 AND server_id = :serverId"
    )
    suspend fun getLocalGotifyUnstarredIds(serverId: String): List<Long>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    // ==================== 裁剪 ====================

    /**
     * 按「每台服务器」的上限裁剪非收藏消息
     *
     * 语义从"全库上限"变成"每台上限"（总量会变 N 倍），设置项文案要注明。
     * 不加 server_id 过滤会优先保留 slot 大的服务器的消息，
     * 把另一台历史整体删掉（S1 风险）。
     */
    @Query(
        "DELETE FROM messages WHERE starred = 0 AND server_id = :serverId AND id NOT IN " +
                "(SELECT id FROM messages WHERE starred = 0 AND server_id = :serverId " +
                "ORDER BY date DESC, receivedAt DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimToLatestForServer(serverId: String, limit: Int)

    // ==================== Paging ====================

    @Query("SELECT * FROM messages ORDER BY date DESC, receivedAt DESC, id DESC")
    fun getAllPaged(): PagingSource<Int, MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE server_id = :serverId AND appid = :appId " +
                "ORDER BY date DESC, receivedAt DESC, id DESC"
    )
    fun getPagedByAppInServer(serverId: String, appId: Int): PagingSource<Int, MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE starred = 1 " +
                "ORDER BY date DESC, receivedAt DESC, id DESC"
    )
    fun getPagedStarred(): PagingSource<Int, MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE channel_id = :channelId " +
                "ORDER BY date DESC, receivedAt DESC, id DESC"
    )
    fun getPagedByChannel(channelId: Int): PagingSource<Int, MessageEntity>

    @Query(
        "SELECT * FROM messages " +
                "WHERE is_read = 0 AND archived_at IS NULL AND server_id IN (:chatzServerIds) " +
                "ORDER BY date DESC, receivedAt DESC, id DESC"
    )
    fun getPagedUnread(chatzServerIds: List<String>): PagingSource<Int, MessageEntity>

    /**
     * 按标签筛选（点击消息卡片上的标签 chip 触发）
     *
     * tags 列存的是 JSON 数组字符串（如 ["urgent","alert"]），这里用 instr
     * 拼上引号做精确子串匹配，而不是 LIKE：
     *   - LIKE 的 % 和 _ 是通配符，标签内容里可能出现（webhook 传进来的），要转义才安全
     *   - 拼引号顺带避免了 "urge" 匹配到 "urgent" 这种半截匹配
     * instr(NULL, ...) 返回 NULL，条件不成立 → tags 为 null 的行自然被排除
     */
    @Query(
        "SELECT * FROM messages WHERE instr(tags, '\"' || :tag || '\"') > 0 " +
                "ORDER BY date DESC, receivedAt DESC, id DESC"
    )
    fun getPagedByTag(tag: String): PagingSource<Int, MessageEntity>

    // ==================== 收藏 ====================

    @Query("UPDATE messages SET starred = :starred WHERE id = :msgId")
    suspend fun updateStarred(msgId: Long, starred: Boolean)

    // ==================== Chatz 已读/未读 ====================
    //
    // read_synced 的两种取值对应两条来源：
    //   = 0 → 本地用户操作（意图），还没得到服务端确认 → 同步时本地赢
    //   = 1 → 服务端已确认（WS 事件 / 同步对账）      → 同步时服务端赢
    // 详见 AppDatabase.MIGRATION_9_10 与 MessageViewModel.mergeReadState。

    /** 本地点「已读」：先记为待上报（read_synced=0），上报成功后再由 markReadConfirmed 收口 */
    @Query("UPDATE messages SET is_read = 1, read_at = :readAt, read_synced = 0 WHERE id = :msgId")
    suspend fun markRead(msgId: Long, readAt: Long)

    /** 本地点「未读」：同样待上报（服务端未读态是删除 message_reads 行） */
    @Query("UPDATE messages SET is_read = 0, read_at = NULL, read_synced = 0 WHERE id = :msgId")
    suspend fun markUnread(msgId: Long)

    /**
     * 服务端确认的已读（WS messageRead 事件、同步对账）
     *
     * read_synced = 1：这条状态来自服务端，同步时应当以服务端为准。
     */
    @Query("UPDATE messages SET is_read = 1, read_at = :readAt, read_synced = 1 WHERE id = :msgId")
    suspend fun markReadConfirmed(msgId: Long, readAt: Long)

    /** 服务端确认的未读（WS messageUnread 事件） */
    @Query("UPDATE messages SET is_read = 0, read_at = NULL, read_synced = 1 WHERE id = :msgId")
    suspend fun markUnreadConfirmed(msgId: Long)

    /** 本地已读上报成功 / 失败后收口：标记为已对齐（失败时保持 0，下次同步重试） */
    @Query("UPDATE messages SET read_synced = 1 WHERE id = :msgId")
    suspend fun markReadSynced(msgId: Long)

    /**
     * 换 token / 换服务器时**作废本地已读**（限该服务器）
     *
     * 为什么必须作废：服务端的已读是按 **user_id** 存的（`message_reads` 表，
     * 见服务端 GET /message 的 `LEFT JOIN message_reads r ON ... AND r.user_id = ?`），
     * 而换 token 就是**换用户身份** —— 旧身份的已读对当前身份毫无意义。
     *
     * 但本地 `messages` 是按 `(server_id, remote_id)` 存的、**不区分身份**，
     * 同一台服务器在换 token 前后共用同一批本地行。若不清理：
     *   旧身份读过的消息（is_read=1）会一直挂在本地，
     *   服务端说未读也纠正不回来 —— 表现就是用户报的
     *   「换 token 后**新消息**变成已读了，但服务端看还是未读」。
     *
     * 置为 is_read=0 / read_at=NULL / read_synced=1：
     *   - 立即显示未读（不再骗人）
     *   - read_synced=1 表示「以服务端为准」，随后的全量同步会用服务端真实状态覆盖
     *
     * ⚠️ 不动 starred：收藏是消息属性，与身份无关，不能因换号丢失。
     */
    @Query(
        "UPDATE messages SET is_read = 0, read_at = NULL, read_synced = 1 " +
                "WHERE server_id = :serverId"
    )
    suspend fun resetReadStateForServer(serverId: String)

    /**
     * 待上报的已读变更（限该服务器）
     *
     * 「本地已读但还没和服务端对账」= 需要补报。
     * 与收藏那套 getPendingStarSync 同一思路：用状态差推导待办，不另建队列表。
     * ⚠️ 标未读（is_read=0 且 read_synced=0）也要重发，否则"点了未读但断网"会丢。
     */
    @Query(
        "SELECT id, is_read, read_at, read_synced FROM messages " +
                "WHERE server_id = :serverId AND read_synced = 0"
    )
    suspend fun getPendingReadSync(serverId: String): List<IdReadPair>

    /**
     * 按服务器限定的"全部已读"
     *
     * 「所有消息」的批量已读必须**按服务器分别执行**：
     * 非 Chatz 服务器的消息 is_read 恒为 0（Gotify 没有已读概念），
     * 用不带 server_id 的全局 UPDATE 会把它们一并标成已读，
     * 之后再也不可能被 Chatz 的已读事件修正（S3 风险）。
     * channelId 为 null 表示该服务器的所有频道。
     */
    @Query(
        "UPDATE messages SET is_read = 1, read_at = :readAt, read_synced = 0 " +
                "WHERE archived_at IS NULL " +
                "AND server_id = :serverId " +
                "AND (:channelId IS NULL OR channel_id = :channelId)"
    )
    suspend fun markAllReadForServer(serverId: String, readAt: Long, channelId: Int?): Int

    /**
     * 服务端确认的批量已读（WS messagesReadAll 事件）
     *
     * read_synced = 1：状态来自服务端，后续同步以服务端为准。
     */
    @Query(
        "UPDATE messages SET is_read = 1, read_at = :readAt, read_synced = 1 " +
                "WHERE archived_at IS NULL " +
                "AND server_id = :serverId " +
                "AND (:channelId IS NULL OR channel_id = :channelId)"
    )
    suspend fun markAllReadConfirmedForServer(
        serverId: String,
        readAt: Long,
        channelId: Int?
    ): Int

    // ==================== Chatz 归档 ====================

    @Query("UPDATE messages SET archived_at = :archivedAt WHERE id = :msgId")
    suspend fun archive(msgId: Long, archivedAt: Long)

    @Query("UPDATE messages SET archived_at = NULL WHERE id = :msgId")
    suspend fun unarchive(msgId: Long)

    // ==================== 搜索 ====================

    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts fts ON m.id = fts.rowid
        WHERE messages_fts MATCH :matchExpr
        ORDER BY m.date DESC, m.receivedAt DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(matchExpr: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE title LIKE :query OR message LIKE :query " +
                "ORDER BY date DESC, receivedAt DESC, id DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<MessageEntity>

    // ==================== 索引定位 ====================

    @Query(
        "SELECT COUNT(*) FROM messages WHERE " +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)"
    )
    suspend fun countNewerThan(date: String, receivedAt: Long, id: Long): Int

    /**
     * 「应用筛选」下的索引定位（按服务器限定）
     *
     * 过滤条件必须和 getPagedByAppInServer() 完全一致，否则跳转到指定消息时索引会算偏
     * （多服务器下同名 appid 会同时存在于多台服务器，不做 server 过滤会错位）。
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE (" +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)" +
                ") AND server_id = :serverId AND appid = :appId"
    )
    suspend fun countNewerThanForAppInServer(
        date: String,
        receivedAt: Long,
        id: Long,
        serverId: String,
        appId: Int
    ): Int

    @Query(
        "SELECT COUNT(*) FROM messages WHERE (" +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)" +
                ") AND starred = 1"
    )
    suspend fun countNewerThanForStarred(date: String, receivedAt: Long, id: Long): Int

    /**
     * 「未读」筛选下的索引定位
     *
     * 过滤条件必须和 getPagedUnread() 完全一致，否则跳转到指定消息时索引会算偏
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE (" +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)" +
                ") AND is_read = 0 AND archived_at IS NULL AND server_id IN (:chatzServerIds)"
    )
    suspend fun countNewerThanForUnread(
        date: String,
        receivedAt: Long,
        id: Long,
        chatzServerIds: List<String>
    ): Int

    /**
     * 「标签筛选」下的索引定位
     *
     * 过滤条件必须和 getPagedByTag() 完全一致，否则跳转到指定消息时索引会算偏
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE (" +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)" +
                ") AND instr(tags, '\"' || :tag || '\"') > 0"
    )
    suspend fun countNewerThanForTag(date: String, receivedAt: Long, id: Long, tag: String): Int

    @Query(
        "SELECT COUNT(*) FROM messages WHERE (" +
                "date > :date OR " +
                "(date = :date AND receivedAt > :receivedAt) OR " +
                "(date = :date AND receivedAt = :receivedAt AND id > :id)" +
                ") AND channel_id = :channelId"
    )
    suspend fun countNewerThanForChannel(
        date: String,
        receivedAt: Long,
        id: Long,
        channelId: Int
    ): Int
}