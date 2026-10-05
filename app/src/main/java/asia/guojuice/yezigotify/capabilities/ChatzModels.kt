package asia.guojuice.yezigotify.capabilities

import com.google.gson.annotations.SerializedName
import asia.guojuice.yezigotify.ChannelEntity
import asia.guojuice.yezigotify.db.LocalIds

/**
 * Chatz 频道
 */
data class ChatzChannel(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("isPublic") val isPublic: Boolean = false,
    @SerializedName("creatorId") val creatorId: Int? = null,
    @SerializedName("createdAt") val createdAt: Long = 0L,
    @SerializedName("unreadCount") val unreadCount: Int = 0,
    @SerializedName("subscribed") val subscribed: Boolean = true,
    @SerializedName("muted") val muted: Boolean = false,
    /** 是否设了订阅密码（服务端只回「有没有」，绝不回密码本身） */
    @SerializedName("passwordProtected") val passwordProtected: Boolean = false,

    // ===== 多服务器来源（本地字段，不来自服务端 JSON） =====
    /** 来源服务器 id。⚠️ 与 GotifyMessage.serverId 同理：Gson 反序列化时缺字段会是
     *  运行时的 null，故声明为可空；需要字符串请用 [sourceServerId]。 */
    val serverId: String? = null,
    /** 服务端原始频道 id（`id` 是本地频道 id = makeChannelId(slot, remoteId)） */
    val remoteId: Int = 0
) {
    /** 来源服务器 id 的安全读取（可空字段 → 非空字符串） */
    val sourceServerId: String get() = serverId ?: ""
}

/**
 * Chatz 未读数统计
 * 服务端 byChannel 的 key 是字符串（JSON 对象 key 只能是 string）
 */
data class ChatzUnreadCounts(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("byChannel") val byChannel: Map<String, Int> = emptyMap()
)

/**
 * Chatz 聚合子消息（aggChildren 里的元素）
 */
data class ChatzAggChild(
    @SerializedName("message") val message: String = "",
    @SerializedName("title") val title: String? = null,
    @SerializedName("date") val date: String = "",
    @SerializedName("priority") val priority: Int = 5,
    @SerializedName("extras") val extras: Map<String, Any>? = null
)

/**
 * Chatz /auth/me 响应（当前账号）
 *
 * ⚠️ [avatar] 是**相对路径**（`/user-avatars/xxx.png`），要经
 *    [ChatzApi.absoluteUrl] 转成绝对地址才能加载 —— 和图标同一套做法。
 */
data class ChatzMe(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("username") val username: String = "",
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    /** 选填、全局唯一；没填就用不了「忘记密码」（服务端 user.email_change） */
    @SerializedName("email") val email: String? = null,
    /** ⚠️ 语义是 `role >= 1`，**不等于**全知。判断超管请用 [isSuper] */
    @SerializedName("isAdmin") val isAdmin: Boolean = false,
    /**
     * 超级管理员（role 2）。服务端 2026-09-30 随两级角色一起加的。
     * 老服务端不返回 → false，降级成按 isAdmin 显示"管理员"，不会崩。
     */
    @SerializedName("isSuper") val isSuper: Boolean = false,
    @SerializedName("createdAt") val createdAt: Long = 0L,

    // ===== 库身份：识别「服务端换了数据库」 =====
    /**
     * 库纪元串（服务端 migrate 生成，形如 `a1b2c3d4e5f6@1774000000000`）
     *
     * 为什么要它：客户端的删除对账水位线（`deleted_since_<serverId>`）是
     * **按 serverId 存的时间戳**。用户有「把整个 ./data 覆盖回服务器」的习惯，
     * 一旦恢复成更旧的备份，本地水位线就永远悬在恢复库里所有墓碑之上，
     * `deleted_at > since` 恒不成立 ⇒ 删除对账**永久失效**，幽灵消息再也清不掉。
     *
     * 纪元串对不上 ⇒ 判定换了库 ⇒ 客户端把该服务器的时间戳水位线归零重拉。
     *
     * 老服务端不返回这两个字段 → null → 客户端不做判定（保持旧行为，向后兼容）。
     */
    @SerializedName("dbEpoch") val dbEpoch: String? = null,
    /**
     * 库指纹（`序列:消息数:已读记录数`）
     *
     * [dbEpoch] 的兜底：如果恢复的备份是在「当前纪元生成之后」做的，
     * meta 里的 db_epoch 会一模一样，单靠纪元串判不出换库。
     * 指纹取的是三个「正常使用中只增不减」的计数，恢复旧备份必然变小，
     * 因此能捕获这种情况。
     */
    @SerializedName("dbMark") val dbMark: String? = null
)

/** GET /device 的一项 */
data class ChatzDevice(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    /**
     * ⚠️ **nullable**：v1.2.2 起服务端对「主密钥」那一行返回 `token: null`
     * （主密钥等同超管身份，不再随设备列表下发明文，只给 [tokenPreview] 指纹）。
     * 之前这里是非空 `String` —— 一旦服务端返回 null，Gson 用反射照写不误，
     * Kotlin 的非空约束在反序列化这一层不管用，会在后面用到时炸 NPE。
     */
    @SerializedName("token") val token: String? = null,
    /** 主密钥行的 8 位指纹（其余行为 null） */
    @SerializedName("tokenPreview") val tokenPreview: String? = null,
    /** 为 true 时 [token] 一定是 null，完整值要另外验密码才能取 */
    @SerializedName("tokenHidden") val tokenHidden: Boolean = false,
    /** 这一行是不是全局主密钥（删不掉，服务端会拦） */
    @SerializedName("isMaster") val isMaster: Boolean = false,
    /** 就是当前这个 App 正在用的凭据 —— 删了它自己就掉线了 */
    @SerializedName("isCurrent") val isCurrent: Boolean = false,
    @SerializedName("lastSeen") val lastSeen: Long? = null,
    @SerializedName("createdAt") val createdAt: Long = 0L
)

/** PATCH /user/profile 请求体（服务端要求 1~32 字符） */
data class ChatzProfileBody(
    @SerializedName("displayName") val displayName: String
)

/** PATCH /user/profile 响应 */
data class ChatzProfileResult(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("user") val user: ChatzMe? = null
)

/** PATCH /user/username 请求体（服务端要求 2~32 位，仅 a-z A-Z 0-9 _ - .） */
data class ChatzUsernameBody(
    @SerializedName("username") val username: String
)

/** PATCH /user/username 响应 */
data class ChatzUsernameResult(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("user") val user: ChatzMe? = null
)

/**
 * PATCH /user/email 请求体
 *
 * 传空串 = 清除邮箱（服务端 normalizeEmail 会把空串规整成 null）；
 * Gson 对 null 会省略字段，而服务端 `req.body.email` 取不到时不改动 ——
 * 所以要清除必须传空串，不能传 null。
 */
data class ChatzEmailBody(
    @SerializedName("email") val email: String
)

/** PATCH /user/email 响应 */
data class ChatzEmailResult(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("user") val user: ChatzMe? = null
)

/**
 * PATCH /user/password 请求体
 *
 * 只传 new_password = 改自己；[revokeDevices] 传 true 时顺带登出其它设备。
 * 客户端**不传 userId**（那是管理员重置他人用的），Gson 省略 null 字段。
 */
data class ChatzPasswordBody(
    @SerializedName("new_password") val newPassword: String,
    @SerializedName("revokeDevices") val revokeDevices: Boolean? = null
)

/** PATCH /user/password 响应：revokedDevices = 本次吊销的设备台数 */
data class ChatzPasswordResult(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("revokedDevices") val revokedDevices: Int = 0
)

/** POST /user/avatar 响应 */
data class ChatzAvatarResult(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("avatar") val avatar: String? = null
)

/** POST /device 请求体 */
data class ChatzCreateDeviceBody(
    @SerializedName("name") val name: String
)

/**
 * Chatz /message/search 响应
 */
data class ChatzSearchResult(
    @SerializedName("query") val query: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("messages") val messages: List<asia.guojuice.yezigotify.GotifyMessage> = emptyList()
)

/**
 * 已删除消息的墓碑（GET /message/deleted 的 deleted 数组元素）
 *
 * 用途：增量对账删除。GET /message 的 since 是 **id 水位线**（`WHERE id > ?`），
 * 只能表达「新增」—— 本地已推进到 id=100 时，服务端删掉 id=50 的消息，增量同步
 * 完全看不见它，那条消息就成了客户端上的"幽灵"（除非当时在线收到了 WS 的
 * messageDeleted，或者跑过一次全量同步）。
 *
 * @param id        服务端消息 id（远程 id）
 * @param channelId 服务端频道 id；无频道的 Gotify 消息为 null
 * @param deletedAt 删除时间戳（毫秒）—— **水位线用这个，不能用 id**，
 *                  因为删除的 id 无序（可能删 50、也可能删 3），拿 id 做水位线会漏
 */
data class ChatzDeletedMessage(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("channelId") val channelId: Int? = null,
    @SerializedName("deletedAt") val deletedAt: Long = 0L
)

/**
 * Chatz /message/deleted 响应
 *
 * ⚠️ 与 [PagedMessages] 一样，本类由 Gson 反序列化：缺失字段不会应用 Kotlin 默认值，
 * 而是 null / 0，所以这里的 `deleted` 声明为非空带默认值也要注意服务端一定返回它。
 */
data class ChatzDeletedResult(
    @SerializedName("deleted") val deleted: List<ChatzDeletedMessage> = emptyList(),
    @SerializedName("since") val since: Long = 0L,
    /** 回显的次级游标（同一 deleted_at 内的 id 位置） */
    @SerializedName("sinceId") val sinceId: Long = 0L,
    /**
     * 下一页游标；null / 缺失 = 已拉到底
     *
     * ⚠️ 关键：**不要自己从 `deleted.last()` 算下一页**。服务端对同刻墓碑
     * （删频道/删应用是同一条 UPDATE 打同一个时间戳）用 `(deleted_at, id)` 双游标
     * 分页，游标的定义权在服务端 —— 客户端原样回传即可。自己推导会在
     * 「一页装不下同刻墓碑」时算错，导致重复拉同一页或永久跳过剩余行。
     */
    @SerializedName("next") val next: ChatzDeletedCursor? = null
)

/** 墓碑分页游标：`(deleted_at, id)` 双键，用于同刻墓碑的分页续读 */
data class ChatzDeletedCursor(
    @SerializedName("since") val since: Long = 0L,
    @SerializedName("sinceId") val sinceId: Long = 0L
)

// ==================== ChatzChannel ↔ ChannelEntity ====================

/**
 * Chatz 响应模型 → 本地 Entity
 *
 * 用途：
 *   - /channel 拉取后写入 DB
 *   - /channel/discover 拉取后 upsert
 *   - WS channelCreated / channelUpdated 事件写入
 *
 * @param serverId 来源服务器 id（写入 server_id 列，多服务器区分）
 * @param slot     来源服务器槽位：本地频道 id = makeChannelId(slot, 远程 id)，
 *                 remote_id 列保存服务端原始 id（slot 0 时两者相等，旧数据零改写）
 * @param now updated_at 时间戳，默认取当前时间；批量写入时可统一传入一个时间
 */
fun ChatzChannel.toEntity(
    serverId: String,
    slot: Int,
    now: Long = System.currentTimeMillis()
): ChannelEntity {
    return ChannelEntity(
        id = LocalIds.makeChannelId(slot, id),
        name = name,
        description = description,
        image = image,
        isPublic = isPublic,
        creatorId = creatorId,
        createdAt = createdAt,
        unreadCount = unreadCount,
        subscribed = subscribed,
        muted = muted,
        passwordProtected = passwordProtected,
        updatedAt = now,
        serverId = serverId,
        remoteId = id
    )
}

/**
 * 本地 Entity → Chatz 响应模型（UI 层使用）
 *
 * 用途：
 *   - DrawerContent 从 DB 读 ChannelEntity，转成 ChatzChannel 展示
 *   - 复用 ChatzChannel 的数据结构，UI 层不用感知 Entity
 *
 * `id` 给 UI 用的是**本地**频道 id；出站调用服务端时用 `remoteId`。
 */
fun ChannelEntity.toChatzChannel(): ChatzChannel {
    return ChatzChannel(
        id = id,
        name = name,
        description = description,
        image = image,
        isPublic = isPublic,
        creatorId = creatorId,
        createdAt = createdAt,
        unreadCount = unreadCount,
        subscribed = subscribed,
        muted = muted,
        passwordProtected = passwordProtected,
        serverId = serverId,
        remoteId = remoteId
    )
}

// ==================== 请求体 ====================
//
// ⚠️ 为什么不用 Map<String, Any> 当 @Body：
//   Kotlin 的 Map 声明是 Map<K, out V>，所以 Map<String, Any> 编译到 JVM 后
//   方法签名会变成 Map<String, ?>（带通配符）。Retrofit 明确拒绝带通配符的
//   参数类型，会在构造请求时直接抛：
//     "Parameter type must not include a type variable or wildcard"
//   表现就是请求根本没发出去 —— 之前 markAllRead / muteChannel 就是这么静默失败的。
//   用具体 data class 可以彻底避开（顺带还有类型安全）。

/** POST /message/read-all 的请求体；channelId 为 null 时 Gson 会省略该字段（= 全部频道） */
data class ChatzReadAllBody(
    @SerializedName("channel_id") val channelId: Int? = null
)

/** PATCH /channel/:id/subscribe 的请求体 */
data class ChatzMuteBody(
    @SerializedName("muted") val muted: Boolean
)

/**
 * POST /channel/:id/subscribe 的请求体
 *
 * [password] 只在订阅「设了密码的频道」时需要；null 时 Gson 省略（公开/无密码频道）。
 */
data class ChatzSubscribeBody(
    @SerializedName("password") val password: String? = null
)

/**
 * PATCH /channel/:id 的请求体
 *
 * 字段全部可空：Gson 会省略 null 字段，服务端据此跳过未传的列（`X !== undefined`）。
 *   - 正常更新：只传 name / description / isPublic
 *   - 移除图标：只传 image = ""（命中服务端 image 分支，清成空串）
 *   - 设/清密码：password 传空串 "" = 清除、传非空 = 设置、null = 不动
 *
 * description 传空串 = 清除（服务端存成空串；展示层用 isNullOrBlank 判断，空串与 null 等价）。
 */
data class ChatzChannelUpdateBody(
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("is_public") val isPublic: Boolean? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("password") val password: String? = null
)

/**
 * POST /message 的请求体（发消息）
 *
 * 只有 message 必填；其余省略时由服务端回落：
 *   - channelId 省略 → 应用的默认频道，再没有就取第一个频道
 *   - appId     省略 → id 最小的应用
 *   - priority  省略 → 5
 */
data class ChatzSendBody(
    @SerializedName("message") val message: String,
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("appid") val appId: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("priority") val priority: Int? = null,
    /** 标签；null / 空数组时 Gson 省略，服务端默认空 */
    @SerializedName("tags") val tags: List<String>? = null,
    /**
     * 附件等附加内容；null 时 Gson 省略该字段
     *
     * ⚠️ 这里是 data class 的**字段**，不是 @Body 参数本身的类型，
     * 所以不受「@Body 不能用泛型集合」那条限制（那条只约束顶层参数）。
     */
    @SerializedName("extras") val extras: Map<String, Any>? = null
)

/**
 * POST /message 的返回
 *
 * 成功时服务端返回刚创建（或聚合进去）的整条消息对象，这里只取 id；
 * 被路由规则丢弃时返回 `{"dropped": true}`，此时并没有创建消息。
 */
data class ChatzSendResult(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("dropped") val dropped: Boolean = false
)

/**
 * POST /attachment 的返回
 *
 * ⚠️ [url] 是**相对路径**（`/attachments/xxx.png`）：绝对地址由客户端按服务器地址拼。
 * 这样换域名 / 局域网 IP 都不会把消息里存死的地址搞坏 —— 和图标同一套做法。
 */
data class ChatzUploadResult(
    @SerializedName("url") val url: String = "",
    /** 服务端清洗后的原始文件名（展示用） */
    @SerializedName("name") val name: String = "",
    @SerializedName("size") val size: Long = 0L,
    /** 服务端按魔数判定；svg 等浏览器可执行的类型会被判为 false */
    @SerializedName("isImage") val isImage: Boolean = false,
    @SerializedName("contentType") val contentType: String? = null
)