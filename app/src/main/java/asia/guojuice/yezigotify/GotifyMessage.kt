package asia.guojuice.yezigotify

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import asia.guojuice.yezigotify.capabilities.ChatzAggChild
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.db.LocalIds

/**
 * Gotify / Chatz 消息数据模型
 *
 * 兼容策略：
 *   - Gotify 原生字段：id / appid / message / title / priority / date / extras
 *   - Chatz 扩展字段：channel_id / tags / isRead / readAt / archivedAt /
 *                    aggCount / aggLastAt / aggChildren
 *     旧 Gotify 服务端不返回这些字段 → Gson 填默认值 → 行为不变
 *
 * Chatz 扩展字段全部入库（MessageEntity 里有对应列），
 * 从 DB 读出时会被正确还原，离线状态下也能看到已读/归档/聚合状态。
 */
data class GotifyMessage(
    @SerializedName("id") val id: Long,
    @SerializedName("appid") val appid: Int,
    @SerializedName("message") val message: String,
    @SerializedName("title") val title: String?,
    @SerializedName("priority") val priority: Int,
    @SerializedName("date") val date: String,
    @SerializedName("extras") val extras: Map<String, Any>? = null,
    val starred: Boolean = false,
    val receivedAt: Long = 0L,

    // ===== Chatz 扩展字段 =====
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("tags") val tags: List<String>? = null,
    @SerializedName("isRead") val isRead: Boolean = false,
    @SerializedName("readAt") val readAt: Long? = null,
    @SerializedName("archivedAt") val archivedAt: Long? = null,
    @SerializedName("aggCount") val aggCount: Int = 1,
    @SerializedName("aggLastAt") val aggLastAt: Long? = null,
    @SerializedName("aggChildren") val aggChildren: List<ChatzAggChild>? = null,
    /**
     * 服务端要求这条消息**不响铃**（Chatz 路由规则的 set_silent，如内置的 night-silent 模板）。
     *
     * 只在投递时有效，**不入库** —— 它描述的是"这一次推送要不要响"，不是消息本身的属性。
     * 旧服务端不下发这个字段，Gson 不会给它赋值，默认 false ⇒ 行为与以前完全一致。
     */
    @SerializedName("silent") val silent: Boolean = false,

    // ===== 多服务器来源（本地字段，不来自服务端） =====
    /**
     * 来源服务器 id
     *
     * ⚠️ 声明为可空是刻意的：本类由 Gson 从服务端 JSON 反序列化，
     * 缺少该字段时 Gson **不会**应用 Kotlin 默认值，运行时就是 null。
     * 若声明成非空 String，编译器会相信类型、跳过判空，一旦读到就 NPE。
     * 需要字符串时请用 [sourceServerId]。
     */
    val serverId: String? = null,
    /** 服务端原始消息 id（本地 id = makeMessageId(slot, remoteId)） */
    val remoteId: Long = 0L
) {
    /** 便捷判断：是否被聚合过 */
    val isAggregated: Boolean get() = aggCount > 1

    /**
     * 该消息在服务端的原始 id
     *
     * - 从 API / WS 直接反序列化的消息：remoteId 未设置（0），此时 `id` 就是服务端原始 id
     * - 从本地 DB / 广播还原的消息：`id` 是本地 id，`remoteId` 是服务端原始 id
     */
    val sourceRemoteId: Long get() = if (remoteId != 0L) remoteId else id

    /**
     * 来源服务器 id 的安全读取（可空字段 → 非空字符串）
     *
     * 从网络解析出来的消息请先 `copy(serverId = ..., remoteId = ...)` 显式赋值
     * （GotifyService.handleNewMessage 就是这么做的）；确实需要读原值时走这个属性。
     */
    val sourceServerId: String get() = serverId ?: ""
}

/**
 * Gotify 分页消息响应
 */
data class PagedMessages(
    @SerializedName("paging") val paging: PagingInfo,
    @SerializedName("messages") val messages: List<GotifyMessage>
)

data class PagingInfo(
    @SerializedName("size") val size: Int,
    @SerializedName("limit") val limit: Int,
    @SerializedName("next") val next: String?,
    @SerializedName("since") val since: Long
)

data class GotifyApp(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    /**
     * Chatz：该应用的默认频道（服务端 /application 返回的 channelId）
     *
     * Gotify 的应用没有这个概念，字段会是 null。
     * 用途：发消息时推导该用哪个应用的身份（见 MessageViewModel.deriveSendAppId）。
     */
    @SerializedName("channelId") val channelId: Int? = null
)

// ==================== Entity ↔ 内存模型 ====================

/**
 * Entity → 内存模型
 *
 * Chatz 扩展字段从 DB 读取：
 *   - channelId  : 直接映射
 *   - tags       : JSON 字符串 → List<String>
 *   - isRead     : 直接映射
 *   - readAt     : 直接映射
 *   - archivedAt : 直接映射
 *   - aggCount   : 直接映射
 *   - aggLastAt  : 直接映射
 *   - aggChildren: JSON 字符串 → List<ChatzAggChild>
 */
fun MessageEntity.toGotifyMessage(): GotifyMessage {
    val extrasMap: Map<String, Any>? = if (extras.isNullOrEmpty()) {
        null
    } else {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        runCatching { Gson().fromJson<Map<String, Any>>(extras, type) }.getOrNull()
    }

    val tagsList: List<String>? = if (tags.isNullOrEmpty()) {
        null
    } else {
        val type = object : TypeToken<List<String>>() {}.type
        runCatching { Gson().fromJson<List<String>>(tags, type) }.getOrNull()
    }

    val aggChildrenList: List<ChatzAggChild>? = if (aggChildren.isNullOrEmpty()) {
        null
    } else {
        val type = object : TypeToken<List<ChatzAggChild>>() {}.type
        runCatching { Gson().fromJson<List<ChatzAggChild>>(aggChildren, type) }.getOrNull()
    }

    return GotifyMessage(
        id = id,
        appid = appid,
        title = title,
        message = message,
        priority = priority,
        date = date,
        extras = extrasMap,
        starred = starred,
        receivedAt = receivedAt,
        // Chatz 扩展字段
        channelId = channelId,
        tags = tagsList,
        isRead = isRead,
        readAt = readAt,
        archivedAt = archivedAt,
        aggCount = aggCount,
        aggLastAt = aggLastAt,
        aggChildren = aggChildrenList,
        // 多服务器来源
        serverId = serverId,
        remoteId = remoteId
    )
}

/**
 * 内存模型 → Entity
 *
 * Chatz 扩展字段序列化：
 *   - tags       : List<String> → JSON 字符串
 *   - aggChildren: List<ChatzAggChild> → JSON 字符串
 *   - 其余直接映射
 *
 * @param server 来源服务器：用它的 id / slot 补全 server_id，
 *               并把消息 id 命名空间化成**本地 id**（slot 0 时等于原 id）。
 *               写库一律用本地 id。
 */
fun GotifyMessage.toEntity(
    server: ServerConfig,
    receivedAt: Long = this.receivedAt
): MessageEntity {
    val tagsJson = if (tags.isNullOrEmpty()) {
        null
    } else {
        runCatching { Gson().toJson(tags) }.getOrNull()
    }

    val aggChildrenJson = if (aggChildren.isNullOrEmpty()) {
        null
    } else {
        runCatching { Gson().toJson(aggChildren) }.getOrNull()
    }

    val remote = sourceRemoteId

    return MessageEntity(
        id = LocalIds.makeMessageId(server.slot, remote),
        appid = appid,
        title = title,
        message = message,
        priority = priority,
        date = date,
        extras = if (extras != null) Gson().toJson(extras) else null,
        starred = starred,
        receivedAt = receivedAt,
        // Chatz 扩展字段
        // channel_id 入库时也做命名空间化：它与 channels.id 是同一套本地 id。
        // （appid 刻意保持服务端原始值，见方案 §2）
        channelId = channelId?.let { LocalIds.makeChannelId(server.slot, it) },
        tags = tagsJson,
        isRead = isRead,
        readAt = readAt,
        archivedAt = archivedAt,
        aggCount = aggCount,
        aggLastAt = aggLastAt,
        aggChildren = aggChildrenJson,
        // 多服务器来源
        serverId = server.id,
        remoteId = remote
    )
}