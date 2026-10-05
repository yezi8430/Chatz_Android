package asia.guojuice.yezigotify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.map
import asia.guojuice.yezigotify.capabilities.ChatzApi
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.capabilities.ChatzMe
import asia.guojuice.yezigotify.capabilities.ChatzSendBody
import asia.guojuice.yezigotify.capabilities.ChatzSendResult
import asia.guojuice.yezigotify.capabilities.ChannelSubscribeResult
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.capabilities.toChatzChannel
import asia.guojuice.yezigotify.capabilities.toEntity as chatzChannelToEntity
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.connection.ConnectionState
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.widget.RecentMessagesWidget
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抽屉消息数统计
 *
 * ⚠️ 徽标语义（按需求）：
 *   - 所有消息 / 频道 / 应用 / ntfy 的徽标显示的是【消息总数】
 *   - 只有「未读」那个入口的徽标用 unread（未读数）
 * 所以 total / byApp / byChannel 都是计数，不是未读数。
 */
data class DrawerCounts(
    /** 本地消息总数（「所有消息」徽标） */
    val total: Int = 0,
    /** 收藏数（「收藏」徽标） */
    val starred: Int = 0,
    /** 未读总数（「未读」徽标） */
    val unread: Int = 0,
    /** 每个 App 的消息总数（含 ntfy 的 appid=0） */
    val byApp: Map<Int, Int> = emptyMap(),
    /** 每个频道的消息总数 */
    val byChannel: Map<Int, Int> = emptyMap(),
    /**
     * 有未读消息的频道 id
     *
     * 只用来让抽屉里的频道徽标变淡红（数字仍显示 byChannel 的总数）。
     */
    val channelsWithUnread: Set<Int> = emptySet(),
    /** 每台服务器的消息总数（key = serverId，抽屉分组头徽标用） */
    val byServer: Map<String, Int> = emptyMap()
) {
    companion object {
        val EMPTY = DrawerCounts()
    }
}

/**
 * drawerCounts 组装过程中的中间态
 *
 * 多服务器后需要 6 路数据（总数 / 收藏 / 频道计数 / 频道未读 / 未读总数 / 每服务器应用计数），
 * 超过 combine 的 5 路上限，所以拆成两段 combine（先 5 路，再并第 6 路）。
 */
private data class PartialDrawerCounts(
    val total: Int,
    val starred: Int,
    val unread: Int,
    val byChannel: Map<Int, Int>,
    val channelsWithUnread: Set<Int>
)

/**
 * 已上传的附件（UI 的"待发送"态）
 *
 * [url] 是**绝对地址**：服务端存的是 `/attachments/xxx.png` 这样的相对路径，
 * 而客户端的图片链路只认 http 开头的 URL（见 utils.MessageText），
 * 所以补全这一步放在客户端做 —— 和图标同一套做法，换域名/局域网 IP 都不会存坏。
 */
data class UploadedAttachment(
    val url: String,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val contentType: String?
)

/**
 * 一次发送的草稿（正文 + 附件 + 标题 / 优先级 / 标签）
 *
 * 整体提在 UI 层持有，发送时一次性交给 [MessageViewModel.sendChannelMessage]，
 * 免得参数越加越多。
 */
data class OutgoingMessage(
    val text: String = "",
    val attachment: UploadedAttachment? = null,
    val title: String? = null,
    /** null = 用服务端默认值（5） */
    val priority: Int? = null,
    val tags: List<String> = emptyList(),
    /** 回复的原消息（**远程** id；出站用，见 sendChannelMessage）；null = 不是回复 */
    val replyToRemoteId: Long? = null,
    /** 回复的原消息摘要（随消息一起发给服务端，别的设备才看得到引用） */
    val replyToText: String? = null
) {
    /** 这条是不是回复 */
    val isReply: Boolean get() = replyToText != null || replyToRemoteId != null
}

/** 附件上传结果：失败原因要分开，UI 才能给不同的提示 */
sealed class AttachmentUploadResult {
    data class Ok(val attachment: UploadedAttachment) : AttachmentUploadResult()

    /** 超过服务端上限（本地先拦下来，免得白读一遍大文件） */
    object TooLarge : AttachmentUploadResult()

    /** 读不出来：没有权限、文件已被删 */
    object Unreadable : AttachmentUploadResult()

    /** 网络失败 / 非 Chatz 服务器 / 未配置 */
    object Failed : AttachmentUploadResult()
}

/** content:// 里能问到的元信息（size 读不到时是 -1） */
private data class LocalFileMeta(val name: String, val size: Long, val mime: String)

@OptIn(ExperimentalCoroutinesApi::class)
class MessageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PagingDebug"
        const val FILTER_ALL = -1
        const val FILTER_STARRED = -2
        const val FILTER_UNREAD = -3

        /**
         * 服务端推来「账号信息变了」时，本 ViewModel 二次广播的 action
         *
         * 只有「个人信息」弹窗会监听它（弹窗开着才重拉 /auth/me，关着就没有开销）。
         * 走广播而不是 LiveData，是因为账号信息不属于消息库、ViewModel 没有它的
         * 持久化状态；而且要解耦到「谁在显示谁去拉」。
         */
        const val ACTION_USER_UPDATED = "asia.guojuice.yezigotify.USER_UPDATED"

        private const val SEARCH_LIMIT = 200
        private const val FTS_MIN_TOKEN_LENGTH = 3

        /**
         * 重新订阅后补拉历史的最大页数（每页 50 条，即最多 500 条）
         *
         * 补拉的目的是"订阅完马上能看见消息"，不是同步全部历史；
         * 更旧的由全量同步兜底，避免订阅一个几万条的频道时后台打上千个请求。
         */
        private const val BACKFILL_MAX_PAGES = 10

        /**
         * 单个附件的上限
         *
         * 必须和服务端保持同步（index.js 里 `/attachment` 的 express.raw limit = 20mb）。
         * 放在客户端只是为了在**读进内存之前**拦掉超大文件，避免白读一遍甚至 OOM。
         */
        private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024

        /**
         * 删除对账单轮最多翻几页
         *
         * 删频道 / 删应用是**一条 UPDATE 打同一个 Date.now()**，整批墓碑
         * `deleted_at` 完全相等 —— 服务端用 `(deleted_at, id)` 双游标分页，
         * 客户端必须翻完所有页才能收干净。
         *
         * 20 轮 × 500 条 = 10000 条，足以覆盖删一个大频道；同时刻意**不设太大**：
         *   - 服务端全局限流 600 请求/分钟（按 IP）。同步是串行遍历多台服务器的，
         *     若每台都翻 50 轮，3 台就是 150 个请求，叠加其它请求会逼近上限触发 429。
         *   - 没翻完的部分留到下次同步继续（水位线已推进到本批末尾，不会漏），
         *     所以轮次小只是「分几次收完」，不会丢数据。
         */
        private const val MAX_TOMBSTONE_ROUNDS = 20

        /**
         * [syncAllServers] 的 onComplete 参数：本次调用被跳过（已有同步在进行）
         *
         * 调用方收到它时不应该弹"已是最新消息"（那是误报），只需收起刷新指示器。
         */
        const val SYNC_SKIPPED = -4
    }

    private val database = AppDatabase.getDatabase(application)
    private val dao = database.messageDao()
    private val channelDao = database.channelDao()

    /**
     * 正在跑的同步任务
     *
     * 用来做**同步重入保护**。同步的触发点很多（onResume 两个分支、连上后的状态回调、
     * 自动全量检查、下拉刷新、长按整屏），它们可能在几乎同一瞬间各自发起一次 ——
     * 实测返回主界面时 1.3 秒内会叠 3 次（2 次全量 + 1 次增量）。
     *
     * 除了白白浪费流量/电量，并发**全量**同步还会共用同一套「清理本地多余消息」逻辑：
     * 任何一次提前收尾（分页上限 / 网络中断）时它手里的 serverIds 都是不完整的，
     * 就可能把另一次刚插进去的消息删掉。所以同一时刻只允许一次。
     */
    private var syncJob: Job? = null

    /**
     * 旧配置迁移出的协议服务器
     *
     * 仅作为「未知 serverId 时的兜底」与 slot 0 语义的参照；
     * 多服务器数据一律以消息 / 频道自带的 serverId 为准。
     */
    private val legacyServer: ServerConfig = ServerConfigStore.legacyProtocolServer(application)

    /**
     * 拥有 Chatz「已读」语义的服务器 id 列表（未读查询只针对它们）
     *
     * 由「已启用的协议服务器 ∩ 该服务器被探测为 Chatz」得出。
     * 能力探测结果不是 Flow，所以在探测完成 / 同步 / 刷新时主动 [refreshChatzServerIds]。
     */
    private val chatzServerIdsFlow: MutableStateFlow<List<String>> =
        MutableStateFlow(currentChatzServerIds())

    private fun currentChatzServerIds(): List<String> =
        ServerConfigStore.enabledProtocolServers(getApplication())
            .filter { ServerCapabilitiesHolder.isChatz(it.id) }
            .map { it.id }

    /**
     * 需要读**频道元信息**（图标 / 名称）的服务器 id 列表，**含已停用的**
     *
     * 为什么比 [chatzServerIdsFlow] 宽：停用只是"不再连 WebSocket"，**不删内容** ——
     * 那台的消息仍然留在列表里，消息卡片左上角的频道图标/名称还是要能查到。
     * 而 AppIconCache 那种内存缓存重启就没了，频道不一样：图标 URL 早就以绝对地址
     * 存在 `channels.image` 里，只要**读 DB 的范围**包含这台就行，不需要任何网络请求。
     *
     * 不放进未读那套（[chatzServerIdsFlow]）的原因：未读数是"已读状态"的语义，
     * 停用的服务器不该再往未读徽标里计数。
     *
     * 抽屉也不会因此多出内容：MainScreen 是按"已启用服务器分组"与频道求交集的。
     */
    private val channelMetaServerIdsFlow: MutableStateFlow<List<String>> =
        MutableStateFlow(currentChannelMetaServerIds())

    private fun currentChannelMetaServerIds(): List<String> =
        ServerConfigStore.all(getApplication())
            .filter { it.isProtocolServer && it.isUsable }
            .filter { ServerCapabilitiesHolder.isChatz(it.id) }
            .map { it.id }

    /**
     * 启用状态 / 能力探测变化后重新计算 Chatz 服务器集合
     *
     * 两个 flow 一起刷：未读那套只看已启用，频道元信息那套含已停用。
     */
    fun refreshChatzServerIds() {
        val ids = currentChatzServerIds()
        if (chatzServerIdsFlow.value != ids) chatzServerIdsFlow.value = ids

        val metaIds = currentChannelMetaServerIds()
        if (channelMetaServerIdsFlow.value != metaIds) channelMetaServerIdsFlow.value = metaIds
    }

    /** 按 serverId 找来源配置，找不到就回落到 legacy 协议服务器 */
    private fun serverOf(serverId: String?): ServerConfig =
        if (serverId.isNullOrEmpty()) legacyServer
        else ServerConfigStore.byId(getApplication(), serverId) ?: legacyServer

    /** Chatz API 工厂：url/token 齐备且该服务器探测为 Chatz 才返回，否则 null */
    private fun chatzApi(serverId: String): ChatzApi? {
        val server = serverOf(serverId)
        if (server.url.isBlank() || server.token.isBlank()) return null
        if (!ServerCapabilitiesHolder.isChatz(serverId)) return null
        return ChatzApi.getInstance(server.url, server.token)
    }

    /** Gotify API 工厂（协议服务器） */
    private fun gotifyApi(serverId: String): GotifyApi? {
        val server = serverOf(serverId)
        if (server.url.isBlank() || server.token.isBlank()) return null
        return GotifyApi.getInstance(server.url, server.token)
    }

    /** 频道出站 id：优先用起飞时带出来的 remoteId，缺失时按本地 id 反解（slot 0 时相等） */
    private fun remoteChannelIdOf(channel: ChatzChannel): Int =
        if (channel.remoteId != 0) channel.remoteId else LocalIds.remoteChannelIdOf(channel.id)

    /**
     * 正在补拉中的频道
     *
     * key = "$serverId|$本地channelId"：订阅成功后本机会同时收到 WS 的
     * subscriptionChanged 事件，两条路径都会触发补拉，用这个集合保证同一频道只拉一次。
     */
    private val backfillingChannels: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val prefs: SharedPreferences =
        application.getSharedPreferences("gotify", Context.MODE_PRIVATE)

    private val currentAppIdFlow = MutableStateFlow(FILTER_ALL)
    private val currentChannelIdFlow = MutableStateFlow<Int?>(null)

    /**
     * 当前标签筛选（null = 未筛选）
     *
     * 点消息卡片上的标签 chip 时设置，是"跨频道、跨应用"的维度，
     * 所以它和 appId / channelId 筛选互斥（设置一方会清掉另一方）。
     */
    private val currentTagFlow = MutableStateFlow<String?>(null)

    /** 供列表顶部的筛选横幅显示 */
    val currentTag: StateFlow<String?> = currentTagFlow.asStateFlow()

    /**
     * 强制刷新信号（计数器，每次自增代表"请重拉列表"）
     *
     * 为什么需要它：Paging 的 [PagingData] 只在**结果集发生变化**时才会重绘。
     * 而「服务端点了全部已读」这个场景，改的主要是**屏幕上看不见的**那些消息
     * （服务端是全库/全频道标记，本地只加载了最近 30 条）。
     * 当前可见的那些本来就已读 ⇒ 重拉后数据一模一样 ⇒ diff 为空 ⇒ 界面纹丝不动。
     *
     * 表现就是用户报的：「有时生效、有时不生效」，而且过一会儿来了新消息
     * 才"突然"全部变成已读（实际是那时 Paging 才重建）。
     *
     * 所以对这类"数据变了但当前页看不出变化"的操作，必须显式喊一声。
     * 由 UI 侧 collect 后调 pagedItems.refresh()。
     */
    private val listRefreshTickFlow = MutableStateFlow(0L)

    /** UI 侧的 collect 目标；值变化即代表需要 pagedItems.refresh() */
    val listRefreshTick: StateFlow<Long> = listRefreshTickFlow.asStateFlow()

    /**
     * 每台 Chatz 服务器的当前登录账号（按 serverId），只用于「频道管理」权限判断。
     *
     * 加载时机：`refreshChannelsFor`（频道同步，onResume / 抽屉打开时都会走）。
     * 缓存不到（还没同步完 / 拉取失败）时 `canManageChannel` 返回 false ⇒ 隐藏「管理频道」入口，
     * 与网页端 currentUser 未加载时返回 false 的口径一致。
     */
    private val currentUserByServer = MutableStateFlow<Map<String, ChatzMe>>(emptyMap())

    val messagesFlow: Flow<PagingData<GotifyMessage>> = combine(
        currentAppIdFlow,
        currentChannelIdFlow,
        currentTagFlow
    ) { appId, channelId, tag -> Triple(appId, channelId, tag) }
        .distinctUntilChanged()
        .flatMapLatest { (appId, channelId, tag) ->
            Pager(
                config = PagingConfig(
                    pageSize = 30,
                    enablePlaceholders = false,
                    maxSize = 500,
                    prefetchDistance = 10
                ),
                pagingSourceFactory = { createPagingSource(appId, channelId, tag) }
            ).flow.map { pagingData ->
                pagingData.map { entity -> entity.toGotifyMessage() }
            }
        }
        .cachedIn(viewModelScope)

    /**
     * 未读总数（只统计 Chatz 服务器）
     *
     * Flow 需要按 server 列表参数化，所以用 flatMapLatest 包一层。
     * ⚠️ 列表为空（没有 Chatz 服务器）时不能查库：空列表会生成非法的 `IN ()` 查询。
     */
    private val unreadCountFlow: Flow<Int> =
        chatzServerIdsFlow.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(0) else dao.observeUnreadCount(ids)
        }

    /**
     * 每台服务器各自的应用计数（抽屉按服务器分组用）
     *
     * 随启用配置变化重建；配置列表本身由 ServerConfigStore.observe 驱动。
     * 注意：只有 isUsable 的卡才有连接，也才可能有消息，据此过滤避免多余的查询。
     */
    private val serverAppCountsFlow: Flow<List<Pair<ServerConfig, List<AppMessageCount>>>> =
        ServerConfigStore.observe(getApplication()).flatMapLatest { configs ->
            val usable = configs.filter { it.enabled && it.isUsable }
            if (usable.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(usable.map { cfg ->
                    dao.observeAppCountsByServer(cfg.id).map { cfg to it }
                }) { it.toList() }
            }
        }

    val drawerCounts: Flow<DrawerCounts> = combine(
        dao.observeTotalCount(),
        dao.observeStarredCount(),
        dao.observeChannelMessageCounts(),
        dao.observeChannelUnreadCounts(),
        unreadCountFlow
    ) { total, starred, chCounts, chUnread, unread ->
        PartialDrawerCounts(
            total = total,
            starred = starred,
            unread = unread,
            byChannel = chCounts.associate { it.channelId to it.count },
            channelsWithUnread = chUnread.map { it.channelId }.toSet()
        )
    }
        // combine 最多 5 路，第 6 路（每服务器应用计数）单独并进来
        .combine(serverAppCountsFlow) { partial, appCounts ->
            // 合成 filter id：slot 0 时等于原始 appid（与改造前一致）
            val byApp = LinkedHashMap<Int, Int>()
            val byServer = LinkedHashMap<String, Int>()
            for ((cfg, list) in appCounts) {
                var sum = 0
                for (c in list) {
                    val filterId = LocalIds.makeAppFilterId(cfg.slot, c.appid)
                    byApp[filterId] = (byApp[filterId] ?: 0) + c.count
                    sum += c.count
                }
                byServer[cfg.id] = sum
            }
            DrawerCounts(
                total = partial.total,
                starred = partial.starred,
                unread = partial.unread,
                byApp = byApp,
                byChannel = partial.byChannel,
                channelsWithUnread = partial.channelsWithUnread,
                byServer = byServer
            )
        }
        .distinctUntilChanged()

    /**
     * 频道元信息（每个 ChatzChannel 自带 serverId / remoteId）
     *
     * 覆盖面比"已启用的 Chatz 服务器"更宽 —— 用 [channelMetaServerIdsFlow]，
     * **包含已停用的卡**：停用不删内容，那台的消息仍在列表里，消息卡片左上角的
     * 频道图标/名称还得能查到（图标 URL 早就以绝对地址存在 channels.image 里，
     * 这里只是把"读 DB 的范围"放宽，不发任何网络请求）。
     *
     * 抽屉不会因此多出条目：MainScreen 只把频道挂到"已启用服务器"的分组下。
     * 单服务器时与改造前一致（slot 0 时远程 id == 本地 id）。
     */
    val channelsFlow: Flow<List<ChatzChannel>> =
        channelMetaServerIdsFlow.flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { channelDao.observeSubscribed(it) }) { lists ->
                    lists.flatMap { list -> list.map { it.toChatzChannel() } }
                }
            }
        }

    // ==================== 筛选 ====================

    fun setCurrentAppId(appId: Int) {
        if (currentAppIdFlow.value == appId &&
            currentChannelIdFlow.value == null &&
            currentTagFlow.value == null
        ) return
        AppLog.d(TAG, "📄 切换 appId: ${currentAppIdFlow.value} → $appId")
        currentAppIdFlow.value = appId
        currentChannelIdFlow.value = null
        currentTagFlow.value = null
    }

    fun setCurrentChannelId(channelId: Int?) {
        if (currentChannelIdFlow.value == channelId && currentTagFlow.value == null) return
        AppLog.d(TAG, "📄 切换 channelId: ${currentChannelIdFlow.value} → $channelId")
        currentChannelIdFlow.value = channelId
        currentTagFlow.value = null
        if (channelId != null) {
            currentAppIdFlow.value = FILTER_ALL
        }
    }

    /**
     * 按标签筛选（点击消息卡片上的标签 chip）
     *
     * 标签是跨频道、跨应用的维度，所以设置它会清掉 app/频道筛选；
     * 反过来（setCurrentAppId / setCurrentChannelId）也会清掉标签。
     * 传 null 表示取消筛选（列表顶部的横幅上有清除入口）。
     */
    fun setCurrentTag(tag: String?) {
        if (currentTagFlow.value == tag) return
        AppLog.d(TAG, "📄 切换标签: ${currentTagFlow.value} → $tag")
        currentTagFlow.value = tag
        if (tag != null) {
            currentAppIdFlow.value = FILTER_ALL
            currentChannelIdFlow.value = null
        }
    }

    private fun createPagingSource(
        appId: Int,
        channelId: Int?,
        tag: String?
    ): PagingSource<Int, MessageEntity> {
        return when {
            // 标签筛选优先级最高：它是"跨频道/跨应用"的视角
            tag != null -> dao.getPagedByTag(tag)
            channelId != null -> dao.getPagedByChannel(channelId)
            appId == FILTER_ALL -> dao.getAllPaged()
            appId == FILTER_STARRED -> dao.getPagedStarred()
            appId == FILTER_UNREAD -> {
                // ⚠️ 空列表会生成非法的 IN ()，没有 Chatz 服务器时直接给空数据源
                val ids = chatzServerIdsFlow.value
                if (ids.isEmpty()) EmptyPagingSource() else dao.getPagedUnread(ids)
            }
            // appId 是「合成筛选 id」：先还原出所属服务器，再还原成服务端原始 appid 查库
            else -> {
                val slot = LocalIds.slotOfAppFilter(appId)
                val sid = ServerConfigStore.bySlot(getApplication(), slot)?.id ?: legacyServer.id
                dao.getPagedByAppInServer(sid, LocalIds.appIdOf(appId))
            }
        }
    }

    /** 「未读」筛选下没有 Chatz 服务器时用的空数据源 */
    private class EmptyPagingSource : PagingSource<Int, MessageEntity>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, MessageEntity>): Int? = null
    }

    // ==================== 插入 / 裁剪 ====================

    fun addMessage(msg: GotifyMessage): Job {
        return viewModelScope.launch {
            val server = serverOf(msg.serverId)
            withContext(Dispatchers.IO) {
                val entity = msg.toEntity(server)
                if (!shouldKeepMessage(entity.channelId)) {
                    AppLog.d(TAG, "⏭️ 跳过未订阅频道的消息: id=${entity.id}, channel=${entity.channelId}")
                    return@withContext
                }
                upsertPreservingStarred(entity)
                trimDatabaseIfNeeded(server.id)
            }
        }
    }

    /**
     * 这条消息该不该留在本地
     *
     * 规则：
     *   - 没有 channel_id 的（Gotify 原生消息）→ 保留
     *   - 本地不认识这个频道 → 保留（首次同步尚未跑完，宁可留着也别误丢）
     *   - 频道存在但未订阅 → 丢弃
     *
     * 为什么需要：管理员（尤其超管）能看到全部频道，服务端一旦把某频道的消息
     * 推过来，不过滤的话"我没订阅的频道"的新消息就会出现在列表里。
     *
     * ⚠️ 2026-09-30 起服务端**已经**把消息推送改成按订阅了（超管不例外），
     *    所以这层现在是双保险：老服务端（未升级）仍会全量推，靠它兜住；
     *    新服务端根本不会推，它是空操作。别因为"服务端已经过滤"就删掉它。
     *
     * @param channelId 本地频道 id（跨进程边界已转换）
     */
    private suspend fun shouldKeepMessage(channelId: Int?): Boolean {
        if (channelId == null) return true
        return withContext(Dispatchers.IO) {
            val channel = channelDao.getById(channelId) ?: return@withContext true
            channel.subscribed
        }
    }

    /**
     * upsert 保留本地 starred
     *
     * 新消息：
     *   - 服务端返回 archivedAt != null → 初始 starred = true
     * 已存在：
     *   - 保留本地 starred（用户操作优先）
     *   - 用服务端最新数据覆盖其它字段（含 archived_at）
     */
    private suspend fun upsertPreservingStarred(entity: MessageEntity) {
        val existing = dao.getMessageById(entity.id)
        if (existing == null) {
            val initial = if (entity.archivedAt != null) {
                entity.copy(starred = true)
            } else {
                entity
            }
            dao.insert(initial)
        } else {
            val merged = entity.copy(starred = existing.starred)
            dao.upsert(merged)
        }
    }

    private suspend fun trimDatabaseIfNeeded(serverId: String) {
        val limit = prefs.getInt("db_retention_limit", -1)
        if (limit > 0) {
            dao.trimToLatestForServer(serverId, limit)
        }
    }

    // ==================== 收藏（双向同步） ====================

    /**
     * 收藏/取消收藏
     *
     * Chatz 模式：
     *   1. 本地立即更新 starred（乐观 UI，用户马上看到星标变化）
     *   2. 推服务端
     *   3. 成功才写 archived_at（= 已同步）
     *   4. 失败保留 starred，下次 syncPendingStarChangesInternal 自动重试
     *
     * Gotify 模式：只更新本地 starred
     */
    fun toggleStar(msg: GotifyMessage, starred: Boolean): Job {
        return viewModelScope.launch {
            val server = serverOf(msg.sourceServerId)
            if (ServerCapabilitiesHolder.isChatz(server.id)) {
                // 1. 本地立即更新 starred
                withContext(Dispatchers.IO) { dao.updateStarred(msg.id, starred) }

                // 2. 推服务端（出站用远程 id）
                val ok = pushStarToServer(server.id, msg.id, starred)

                // 3. 成功才更新 archived_at
                if (ok) {
                    withContext(Dispatchers.IO) {
                        if (starred) {
                            dao.archive(msg.id, System.currentTimeMillis())
                        } else {
                            dao.unarchive(msg.id)
                        }
                    }
                    AppLog.d(TAG, "⭐ 收藏已同步: id=${msg.id}, starred=$starred")
                } else {
                    AppLog.w(TAG, "⭐ 服务端同步失败，待重试: id=${msg.id}, starred=$starred")
                }
            } else {
                withContext(Dispatchers.IO) { dao.updateStarred(msg.id, starred) }
                AppLog.d(TAG, "⭐ 更新收藏（本地）: id=${msg.id}, starred=$starred")
            }
        }
    }

    /**
     * 推送单条收藏变更到服务端（serverId 决定走哪台服务器的 API）
     */
    private suspend fun pushStarToServer(serverId: String, msgId: Long, starred: Boolean): Boolean {
        val api = chatzApi(serverId) ?: return false
        return try {
            // 出站用服务端原始 id（slot 0 时与本地 id 相等）
            val remoteId = LocalIds.remoteIdOf(msgId)
            if (starred) api.archiveMessage(remoteId) else api.unarchiveMessage(remoteId)
        } catch (e: Exception) {
            AppLog.e(TAG, "推送收藏失败: ${e.message}")
            false
        }
    }

    /**
     * 批量同步待推送的收藏变更
     *
     * 触发时机：[syncAllServers] 每次同步完成后（收藏失败时本地保留 starred，
     * 靠这里自动重试）。
     */
    private suspend fun syncPendingStarChangesInternal() {
        val servers = ServerConfigStore.enabledProtocolServers(getApplication())
            .filter { ServerCapabilitiesHolder.isChatz(it.id) && chatzApi(it.id) != null }
        if (servers.isEmpty()) return

        val now = System.currentTimeMillis()
        var okCount = 0
        for (server in servers) {
            val api = chatzApi(server.id) ?: continue
            // 只取该服务器的待同步收藏（getPendingStarSync 已按 server_id 过滤）
            val pending = withContext(Dispatchers.IO) { dao.getPendingStarSync(server.id) }
            if (pending.isEmpty()) continue
            AppLog.d(TAG, "⭐ [${server.id}] 待同步收藏变更: ${pending.size} 条")

            for (item in pending) {
                val ok = try {
                    // 出站用服务端原始 id
                    val remoteId = LocalIds.remoteIdOf(item.id)
                    if (item.starred) api.archiveMessage(remoteId)
                    else api.unarchiveMessage(remoteId)
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    okCount++
                    // 成功后更新本地 archived_at（表示已同步）
                    withContext(Dispatchers.IO) {
                        if (item.starred) {
                            dao.archive(item.id, now)
                        } else {
                            dao.unarchive(item.id)
                        }
                    }
                } else {
                    AppLog.w(TAG, "⭐ 同步失败: id=${item.id}, starred=${item.starred}")
                    break
                }
            }
        }
        AppLog.d(TAG, "⭐ 收藏同步完成: 成功 $okCount 条")
    }

    // ==================== Chatz 状态操作 ====================

    /**
     * 标记已读
     *
     * 顺序：本地先落（带 read_synced=0，代表"待上报"）→ 再上报服务端。
     * 上报成功就把 read_synced 置 1（已对齐）；失败则**保持 0**，
     * 由 [syncPendingReadChangesInternal] 在下次同步时补报（不会丢）。
     */
    fun markRead(msg: GotifyMessage) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { dao.markRead(msg.id, now) }
            val ok = callChatz(serverOf(msg.sourceServerId).id) { it.markRead(msg.sourceRemoteId) }
            if (ok) {
                withContext(Dispatchers.IO) { dao.markReadSynced(msg.id) }
            } else {
                AppLog.w(TAG, "📖 已读上报失败，留待下次同步补报: id=${msg.id}")
            }
        }
    }

    fun markUnread(msg: GotifyMessage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.markUnread(msg.id) }
            val ok = callChatz(serverOf(msg.sourceServerId).id) { it.markUnread(msg.sourceRemoteId) }
            if (ok) {
                withContext(Dispatchers.IO) { dao.markReadSynced(msg.id) }
            } else {
                AppLog.w(TAG, "📖 未读上报失败，留待下次同步补报: id=${msg.id}")
            }
        }
    }

    /**
     * 补报"本地已改、但没成功告诉服务端"的已读变更
     *
     * 与收藏的 [syncPendingStarChangesInternal] 同一套机制：靠状态差
     * （read_synced=0）推导待办，不另建队列表。每次同步完成后调用。
     *
     * 逐条推送、遇错 break（同收藏那套）：网络不通时不必把几百条都试一遍。
     */
    private suspend fun syncPendingReadChangesInternal() {
        val servers = ServerConfigStore.enabledProtocolServers(getApplication())
            .filter { ServerCapabilitiesHolder.isChatz(it.id) && chatzApi(it.id) != null }
        if (servers.isEmpty()) return

        var okCount = 0
        for (server in servers) {
            val api = chatzApi(server.id) ?: continue
            val pending = withContext(Dispatchers.IO) { dao.getPendingReadSync(server.id) }
            if (pending.isEmpty()) continue
            AppLog.d(TAG, "📖 [${server.id}] 待补报已读变更: ${pending.size} 条")

            for (item in pending) {
                // 出站用服务端原始 id（slot 0 时与本地 id 相等）
                val remoteId = LocalIds.remoteIdOf(item.id)
                val ok = try {
                    if (item.isRead) api.markRead(remoteId) else api.markUnread(remoteId)
                } catch (e: Exception) {
                    AppLog.e(TAG, "📖 补报异常: ${e.message}")
                    false
                }
                if (ok) {
                    okCount++
                    withContext(Dispatchers.IO) { dao.markReadSynced(item.id) }
                } else {
                    AppLog.w(TAG, "📖 补报失败: id=${item.id}, isRead=${item.isRead}")
                    break
                }
            }
        }
        if (okCount > 0) AppLog.d(TAG, "📖 已读补报完成: $okCount 条")
    }

    /**
     * 全部已读
     *
     * @param channel 指定频道（取自抽屉/长按菜单的 ChatzChannel，自带 serverId），
     *                null = 「所有消息」：对所有 Chatz 服务器分别调用
     */
    fun markAllRead(channel: ChatzChannel? = null) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (channel != null) {
                val server = serverOf(channel.serverId)
                withContext(Dispatchers.IO) {
                    dao.markAllReadForServer(server.id, now, channel.id)
                    channelDao.clearUnread(channel.id, now)
                }
                // 出站用服务端原始频道 id（slot 0 时与本地 id 相等）
                callChatz(server.id) { it.markAllRead(remoteChannelIdOf(channel)) }
            } else {
                val servers = ServerConfigStore.enabledProtocolServers(getApplication())
                    .filter { ServerCapabilitiesHolder.isChatz(it.id) }
                withContext(Dispatchers.IO) {
                    // 逐台更新：不能用全局 markAllRead，否则会把非 Chatz 服务器
                    // （is_read 恒为 0）的消息一并标成已读
                    servers.forEach {
                        dao.markAllReadForServer(it.id, now, null)
                        channelDao.clearAllUnread(it.id, now)
                    }
                }
                for (server in servers) {
                    callChatz(server.id) { it.markAllRead(null) }
                }
            }
            // 本地自己点的「全部已读」同样要显式刷新列表。
            //
            // 理由和服务端推来的 messagesReadAll 一样：被标记的主要是**屏幕外**的消息，
            // 当前可见的那几条本来就已读 ⇒ 重拉后结果集不变 ⇒ Paging 不重绘。
            // 不喊这一声，用户会觉得"点了没反应"（虽然数据其实已经写进去了）。
            listRefreshTickFlow.value = listRefreshTickFlow.value + 1
        }
    }

    /**
     * 调一台服务器的 Chatz API（结果透出来供 UI 判断成败）
     *
     * @return false 表示：非 Chatz 服务端 / 未配置服务器 / 请求异常或非 2xx
     */
    private suspend fun callChatz(serverId: String, block: suspend (ChatzApi) -> Boolean): Boolean {
        val api = chatzApi(serverId) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                block(api)
            } catch (e: Exception) {
                AppLog.e(TAG, "Chatz 调用失败: ${e.message}")
                false
            }
        }
    }

    // ==================== 同步 ====================

    /**
     * 同步所有已启用的协议服务器（**串行**遍历，避免 N 台同时全量拉取造成突刺）
     *
     * NTFY 跳过（无历史 API）。每台各自用 dao.getLatestRemoteId(serverId) 作水位线。
     * onComplete 收到所有服务器新增条数之和；全部失败且没有新增时返回 -1。
     */
    fun syncAllServers(
        forceFullSync: Boolean = false,
        onComplete: (Int) -> Unit = {}
    ) {
        val appContext = getApplication<Application>()

        // 同一时刻只允许一次同步（原因见 syncJob 的注释）
        if (syncJob?.isActive == true) {
            AppLog.d(TAG, "⏭️ 已有同步在进行，跳过本次（forceFullSync=$forceFullSync）")
            onComplete(SYNC_SKIPPED)
            return
        }

        syncJob = viewModelScope.launch {
            // onComplete 必须**恰好调用一次**：MainActivity 靠它收起下拉指示器，
            // 漏调会让指示器一直转圈，多调会让它误报"已是最新消息"。
            var reported = false
            fun report(count: Int) {
                if (reported) return
                reported = true
                onComplete(count)
            }

            try {
                refreshChatzServerIds()
                val servers = ServerConfigStore.enabledProtocolServers(appContext)
                    .filter { it.url.isNotBlank() && it.token.isNotBlank() }
                AppLog.d(TAG, "🔄 开始同步 ${servers.size} 台服务器, forceFullSync=$forceFullSync")
                if (servers.isEmpty()) {
                    report(0)
                    return@launch
                }
                var total = 0
                var anyFailed = false
                for (server in servers) {
                    try {
                        val newCount = if (ServerCapabilitiesHolder.isChatz(server.id)) {
                            syncFromChatz(server, forceFullSync)
                        } else {
                            syncFromGotify(server, forceFullSync)
                        }
                        total += newCount
                        // 所有同步路径都是 dao.insert/insertAll 直插，不走 addMessage，
                        // 所以"数据保留上限"要在这里统一按服务器补一次
                        trimDatabaseIfNeeded(server.id)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "❌ 同步失败（${server.id}）: ${e.message}", e)
                        anyFailed = true
                    }
                }
                refreshChatzServerIds()
                RecentMessagesWidget.notifyWidgetDataChanged(appContext)
                // ★ 同步完成后推送本地待同步收藏
                syncPendingStarChangesInternal()
                // ★ 同步完成后补报本地待同步的已读变更（与收藏同理）
                syncPendingReadChangesInternal()
                report(if (anyFailed && total == 0) -1 else total)
            } catch (e: Exception) {
                AppLog.e(TAG, "❌ 同步异常: ${e.message}", e)
                report(0)
            } finally {
                // 兜底：即使 body 抛异常也要保证回调被调用（report 内部去重，不会重复回调）
                report(0)
            }
        }
    }

    // ==================== Gotify 同步 ====================

    private suspend fun syncFromGotify(
        server: ServerConfig,
        forceFullSync: Boolean
    ): Int {
        val api = gotifyApi(server.id) ?: return 0

        if (!forceFullSync) {
            // 水位线用「该服务器的远程 id」，绝不能是全局 MAX(id)
            val latestRemoteId = withContext(Dispatchers.IO) {
                dao.getLatestRemoteId(server.id) ?: 0L
            }
            AppLog.d(TAG, "📥 [Gotify] 增量同步，本地最新远程ID=$latestRemoteId")
            val result = withContext(Dispatchers.IO) {
                api.getMessages(50, since = latestRemoteId)
            }
            val newCount = if (result != null && result.messages.isNotEmpty()) {
                // 服务端消息的 id 是远程 id，比较/入库前统一转成本地 id
                val localIds = result.messages.map { LocalIds.makeMessageId(server.slot, it.id) }
                val existingIds = withContext(Dispatchers.IO) {
                    dao.getExistingIds(localIds)
                }
                val count = localIds.size - existingIds.size
                val entities = result.messages.map { it.toEntity(server) }
                withContext(Dispatchers.IO) { dao.insertAll(entities) }
                AppLog.d(TAG, "📥 [Gotify] 增量同步完成，新增 $count 条")
                count
            } else {
                0
            }
            return newCount
        }

        AppLog.d(TAG, "📥 [Gotify] 全量同步开始...")
        val serverIds = mutableSetOf<Long>()
        var sinceId = 0L
        var totalInserted = 0
        var newCount = 0
        var hasMore = true
        var networkFailed = false

        while (hasMore) {
            val result = withContext(Dispatchers.IO) {
                api.getMessages(50, since = sinceId)
            }
            if (result == null) {
                networkFailed = true
                AppLog.w(TAG, "⚠️ [Gotify] 拉取失败，服务器不可达")
                break
            }
            if (result.messages.isEmpty()) break

            val localIds = result.messages.map { LocalIds.makeMessageId(server.slot, it.id) }
            val existingIds = withContext(Dispatchers.IO) {
                dao.getExistingIds(localIds)
            }
            newCount += (localIds.size - existingIds.size)
            val entities = result.messages.map { it.toEntity(server) }
            withContext(Dispatchers.IO) { dao.insertAll(entities) }
            serverIds.addAll(localIds)
            totalInserted += result.messages.size

            if (result.messages.size < 50) {
                hasMore = false
            } else {
                // 分页游标是服务端游标，必须用远程 id
                sinceId = result.messages.last().id
            }
        }

        withContext(Dispatchers.IO) {
            if (networkFailed) {
                AppLog.w(TAG, "⚠️ [Gotify] 网络失败，跳过本地清理")
            } else if (serverIds.isEmpty()) {
                // serverIds 为空 = 本次全量没拉到任何消息，若照常清理会把该服务器本地
                // 历史整体删掉。宁可留着，也不要因一次空结果误删（S1 风险）。
                AppLog.w(TAG, "⚠️ [Gotify] 本次全量未拉到消息，跳过本地清理")
            } else {
                // 只清理【这台服务器】的本地多余消息；serverIds 也只含该服务器的本地 id。
                // 不加 server 过滤会跨服务器误删（S1 风险）。
                val localIds = dao.getLocalGotifyUnstarredIds(server.id)
                val toDelete = localIds.filter { it !in serverIds }
                var deletedCount = 0
                toDelete.chunked(500).forEach { chunk ->
                    deletedCount += dao.deleteByIds(chunk)
                }
                AppLog.d(TAG, "🗑️ [Gotify] 清理 $deletedCount 条本地多余消息")
            }
        }

        AppLog.d(TAG, "✅ [Gotify] 全量同步完成，共 $totalInserted 条，新增 $newCount 条")
        return newCount
    }

    // ==================== Chatz 同步 ====================

    private suspend fun syncFromChatz(server: ServerConfig, forceFullSync: Boolean): Int {
        val api = chatzApi(server.id) ?: return 0

        syncChannels(api, server)

        // 删除对账必须在**拉消息之前**做，顺序不能反。
        //
        // 为什么：服务端删掉消息只是打墓碑，id 不会回收（AUTOINCREMENT），
        // 所以重新同步不会把它拉回来 —— 但仍有两类场景必须靠这个顺序兜住：
        //   ① 客户端本地这条的 remote_id 还在，若不先删，随后插入的新消息里
        //      如果恰好有同 remote_id（换库/回滚恢复造成的小概率）会撞 UNIQUE 索引
        //      (server_id, remote_id)，INSERT ... OR IGNORE 会**静默丢掉**新消息；
        //   ② 「先删后插」让本地在任何瞬间都不存在「墓碑消息 + 命中它的新消息」
        //      并存的状态，省掉一个不可能测到的边界。
        // 代价是本轮墓碑先于消息出现一瞬，UI 上无感。
        if (!forceFullSync) {
            syncDeletedTombstones(api, server)
        }

        val newCount = if (forceFullSync) {
            syncMessagesFullChatz(api, server)
        } else {
            syncMessagesIncrementalChatz(api, server)
        }

        syncUnreadCounts(api, server)

        return newCount
    }

    /**
     * 增量对账删除（墓碑）
     *
     * 游标是**双键**，都按 serverId 存在 prefs 里：
     *   - `deleted_since_<serverId>`    —— 主游标，deleted_at 时间戳
     *   - `deleted_since_id_<serverId>` —— 次级游标，同一时间戳内的 id 位置
     *
     * ⚠️ 为什么必须有次级游标，且必须**持久化**：
     *
     *   删频道 / 删应用是**一条 UPDATE 打同一个 Date.now()**，整批墓碑
     *   `deleted_at` 完全相等。只靠时间戳，一旦「同刻墓碑超过一页」，第一页
     *   之后水位线就推到了这批的最大时间戳，而剩余同刻行的 `deleted_at` 与之
     *   **完全相等** ⇒ `deleted_at > since` 恒不成立 ⇒ 剩余行**永久跳过**。
     *
     *   实测：1200 条同刻墓碑，单游标只能拿到 503 条，**漏 700 条**。
     *
     *   次级游标还必须落盘，不能只活在内存里 —— 否则本轮没拉完就退出
     *   （轮次上限 / 网络失败）时，下次同步只带时间戳回来，同刻剩余行依然
     *   够不着。落盘后下次能从**中断处精确续读**，不重不漏。
     *
     *   两个游标同进同退：只有在「不在同刻中间」（id 游标 = 0）时时间戳才推进。
     *
     * 三个必须注意的点：
     *   1. 请求失败（返回 null）时**绝不能推进游标**，否则那一段删除被永久跳过；
     *   2. 游标只推进到「本批实际处理完的最后一条」，绝不推进到「当前时间」
     *      —— 服务端可能还有没拉完的墓碑；
     *   3. 服务端查询是 `deleted_at > since OR (deleted_at = since AND id > sinceId)`，
     *      客户端把游标设成「本页最后一条的 (deleted_at, id)」正好接上，不重不漏。
     *
     * 只删本地消息，不动频道 / 收藏台账：
     *   - 收藏（starred）的那条如果被服务端删了，本来就不该继续显示 ——
     *     服务端 `DELETE` 对同频道所有人可见，这是共享状态的破坏性操作。
     */
    private suspend fun syncDeletedTombstones(api: ChatzApi, server: ServerConfig) {
        val key = "deleted_since_${server.id}"
        val idKey = "deleted_since_id_${server.id}"
        var since = prefs.getLong(key, 0L)
        var sinceId = prefs.getLong(idKey, 0L)

        // ── 先确认「还是同一个库」 ──
        //
        // 水位线（since）是**时间戳**，只有在同一条数据时间线内才有意义。
        // 用户有把整个 ./data 覆盖回服务器的习惯；一旦恢复成更旧的备份，
        // 本地 since 会永远大于恢复库里所有墓碑的 deleted_at ⇒ `> since`
        // 恒不成立 ⇒ 删除对账**永久失效**，幽灵消息再也清不掉。
        //
        // 判定依据来自 /auth/me 的 dbEpoch（库纪元）+ dbMark（库指纹）：
        //   - 首次见（本地没存）→ 只记录，不动水位线
        //   - 与本地存的不一致  → 判定换了库 → 水位线归零，从头重拉全部墓碑
        //   - 老服务端不返回    → null，跳过判定（向后兼容，保持旧行为）
        val epochKey = "db_epoch_${server.id}"
        val markKey = "db_mark_${server.id}"
        val identity = api.getMe()
        if (identity != null && identity.dbEpoch != null) {
            val savedEpoch = prefs.getString(epochKey, null)
            val savedMark = prefs.getString(markKey, null)
            when {
                savedEpoch == null -> {
                    // 首次：只登记，不动水位线
                    prefs.edit()
                        .putString(epochKey, identity.dbEpoch)
                        .putString(markKey, identity.dbMark)
                        .apply()
                    AppLog.d(TAG, "🆔 [Chatz] 首次登记库身份 epoch=${identity.dbEpoch}")
                }
                savedEpoch != identity.dbEpoch || savedMark != identity.dbMark -> {
                    // 换库 / 回滚：把水位线归零，本轮的 since 也用 0
                    prefs.edit()
                        .putString(epochKey, identity.dbEpoch)
                        .putString(markKey, identity.dbMark)
                        .putLong(key, 0L)
                        .putLong(idKey, 0L)
                        .apply()
                    since = 0L
                    sinceId = 0L
                    AppLog.w(
                        TAG,
                        "🆔 [Chatz] 库身份变化，删除对账游标归零重拉 " +
                            "epoch: $savedEpoch -> ${identity.dbEpoch}, " +
                            "mark: $savedMark -> ${identity.dbMark}"
                    )
                }
            }
        }

        // ── 循环拉取，直到服务端说「没有下一页」 ──
        //
        // ⚠️ 为什么必须循环，不能只拉一页：
        //   删**频道**或删**应用**是一条 `UPDATE ... SET deleted_at = ?` 打
        //   **同一个** Date.now()，整批（可能上万条）的 deleted_at 完全相等。
        //   第一页返回 500 条后，若只用时间戳水位线推进，剩下那些同刻墓碑的
        //   deleted_at 与之**完全相等**，`deleted_at > since` 恒不成立
        //   ⇒ 剩余行永久跳过，变成清不掉的幽灵。（实测 1200 条同刻墓碑，
        //      单游标只拿到 503 条，漏 700 条。）
        //
        //   所以游标是**双键** `(deleted_at, id)`：服务端返回 `next`，
        //   客户端原样回传。不自己从 `deleted.last()` 推导 —— 游标的定义权在
        //   服务端，客户端自己算会在「一页装不下同刻墓碑」时算错。
        //
        // ⚠️ 游标每轮都要**落盘**，不能只活在内存里：
        //   本轮可能因轮次上限 / 网络失败提前退出，下次同步必须能从**中断处**
        //   精确续读。只存时间戳的话，同刻剩余行下次依然够不着 —— 那正是
        //   这个 bug 的根源。
        var totalDeleted = 0
        var totalRows = 0
        var round = 0
        var exhausted = false

        while (round < MAX_TOMBSTONE_ROUNDS) {
            round++
            val result = api.getDeletedMessages(
                since = if (since > 0L) since else null,
                sinceId = if (sinceId > 0L) sinceId else null
            )
            // null = 请求失败/超时。游标保持不动，下次同步重试这一段。
            if (result == null) {
                AppLog.w(TAG, "⚠️ [Chatz] 删除对账拉取失败，保留游标 since=$since/$sinceId")
                return
            }
            if (result.deleted.isEmpty()) {
                exhausted = true
                break
            }

            // 远程 id → 本地 id（命名空间化）
            val localIds = result.deleted.map { LocalIds.makeMessageId(server.slot, it.id) }

            totalDeleted += withContext(Dispatchers.IO) {
                var n = 0
                // 分批删，避免 IN 参数过多（沿用全量同步里的 500 一批）
                localIds.chunked(500).forEach { chunk -> n += dao.deleteByIds(chunk) }
                n
            }
            totalRows += result.deleted.size

            // ── 推进并持久化游标 ──
            // 优先用服务端给的 next（它定义了「下一页从哪开始」）；
            // 老服务端不返回 next 时退回「自己算最后一条」的兼容路径。
            val lastRow = result.deleted.last()
            val nextSince = result.next?.since ?: lastRow.deletedAt
            val nextId = result.next?.sinceId ?: lastRow.id
            prefs.edit()
                .putLong(key, nextSince)
                .putLong(idKey, nextId)
                .apply()
            since = nextSince
            sinceId = nextId

            // 服务端没给 next ⇒ 已经拉到底
            if (result.next == null) {
                exhausted = true
                break
            }
        }

        if (!exhausted) {
            AppLog.w(
                TAG,
                "⚠️ [Chatz] 删除对账达到轮次上限 $MAX_TOMBSTONE_ROUNDS，游标已落盘 " +
                    "($since/$sinceId)，下次同步从此处续读"
            )
        }
        if (totalRows == 0) return

        AppLog.d(
            TAG,
            "🗑️ [Chatz] 删除对账：服务端墓碑 $totalRows 条（$round 轮），" +
                    "本地实际清理 $totalDeleted 条，游标 → $since/$sinceId" +
                    if (exhausted) "" else "（未拉完，下次续读）"
        )
    }

    private suspend fun syncChannels(api: ChatzApi, server: ServerConfig) {
        try {
            val channels = api.getChannels()
            if (channels.isEmpty()) {
                AppLog.d(TAG, "📥 [Chatz] /channel 返回空，跳过替换")
                return
            }

            val base = normalizeBaseUrl(server.url)
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                channelDao.demoteAllSubscribed(server.id, now)
                channelDao.upsertAll(
                    channels.map { ch ->
                        // 只把图片解析成绝对地址，其余字段（含 id 命名空间化 + serverId/remoteId）
                        // 统一交给 ChatzChannel.toEntity
                        ch.copy(image = resolveImageUrl(base, ch.image))
                            .chatzChannelToEntity(server.id, server.slot, now)
                    }
                )
                // 刻意不清理 subscribed=0 的记录：
                //   它们是"这个频道存在、但我没订阅"这一事实的依据
                //   （新消息入库时据此过滤），也是发现页的数据来源。
                //   抽屉只查 subscribed=1，不受影响。
            }
            AppLog.d(TAG, "📥 [Chatz] 频道同步完成，${channels.size} 个")
        } catch (e: Exception) {
            AppLog.e(TAG, "📥 [Chatz] 频道同步失败: ${e.message}")
        }
    }

    private fun resolveImageUrl(base: String, image: String?): String? {
        if (image.isNullOrBlank()) return null
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        if (base.isEmpty()) return image
        return if (image.startsWith("/")) "$base$image" else "$base/$image"
    }

    private fun normalizeBaseUrl(url: String): String {
        var s = url.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return s
    }

    private suspend fun syncUnreadCounts(api: ChatzApi, server: ServerConfig) {
        try {
            val counts = api.getUnreadCounts() ?: return
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                channelDao.clearAllUnread(server.id, now)
                for ((chIdStr, count) in counts.byChannel) {
                    val remoteChId = chIdStr.toIntOrNull() ?: continue
                    // 未读数落在本地频道 id 上
                    channelDao.updateUnreadCount(
                        LocalIds.makeChannelId(server.slot, remoteChId), count, now
                    )
                }
            }
            AppLog.d(TAG, "📥 [Chatz] 未读同步完成，total=${counts.total}")
        } catch (e: Exception) {
            AppLog.e(TAG, "📥 [Chatz] 未读同步失败: ${e.message}")
        }
    }

    /**
     * Chatz 增量同步
     *
     * archived="all"：必须拉已归档消息，否则收藏 tab 会空
     */
    private suspend fun syncMessagesIncrementalChatz(api: ChatzApi, server: ServerConfig): Int {
        // 水位线用「该服务器的远程 id」（发给 API 的 since 就是远程 id）
        val latestRemoteId = withContext(Dispatchers.IO) {
            dao.getLatestRemoteId(server.id) ?: 0L
        }
        AppLog.d(TAG, "📥 [Chatz] 增量同步，本地最新远程ID=$latestRemoteId")

        val result = withContext(Dispatchers.IO) {
            api.getMessages(limit = 50, since = latestRemoteId, archived = "all")
        } ?: return 0

        if (result.messages.isEmpty()) return 0

        // 只保留「已订阅频道 + 无频道的 Gotify 消息」：
        // 管理员的 /message 返回全部频道的消息，不过滤的话退订过的频道会被同步拉回来。
        // 普通用户的 /message 本就只返回已订阅频道，这层过滤是空操作。
        // ⚠️ msg.channelId 是服务端原始频道 id，所以比较集合也用 remoteId
        val subscribedRemoteChannels = withContext(Dispatchers.IO) {
            channelDao.getSubscribed(server.id).map { it.remoteId }.toHashSet()
        }
        val messages = result.messages.filter { msg ->
            msg.channelId == null || subscribedRemoteChannels.contains(msg.channelId)
        }
        if (messages.isEmpty()) return 0

        val localIds = messages.map { LocalIds.makeMessageId(server.slot, it.id) }
        val existingIds = withContext(Dispatchers.IO) {
            dao.getExistingIds(localIds)
        }
        val existingSet = existingIds.toHashSet()
        val newCount = localIds.size - existingIds.size

        val starredMap: Map<Long, Boolean> = withContext(Dispatchers.IO) {
            if (existingIds.isEmpty()) emptyMap()
            else dao.getIdStarredPairs(existingIds).associate { it.id to it.starred }
        }
        val readMap: Map<Long, IdReadPair> = withContext(Dispatchers.IO) {
            if (existingIds.isEmpty()) emptyMap()
            else dao.getIdReadPairs(existingIds).associate { it.id to it }
        }

        withContext(Dispatchers.IO) {
            for (msg in messages) {
                val entity = msg.toEntity(server)
                if (existingSet.contains(entity.id)) {
                    // starred 与已读都是本地状态，不能被服务端覆盖（见 mergeReadState）
                    dao.upsert(
                        mergeReadState(entity, readMap[entity.id])
                            .copy(starred = starredMap[entity.id] == true)
                    )
                } else {
                    val initial = if (entity.archivedAt != null) {
                        entity.copy(starred = true)
                    } else {
                        entity
                    }
                    dao.insert(initial)
                }
            }
        }

        AppLog.d(TAG, "📥 [Chatz] 增量同步完成，新增 $newCount 条")
        return newCount
    }

    /**
     * Chatz 全量同步
     *
     * @param channelId 只补拉某个频道（重新订阅后用），**本地**频道 id。传入时：
     *   - 只请求该频道（发给 API 的是远程 id）的消息，且不再做订阅过滤
     *     （调用方已确认刚订阅成功，也避免与并发的 syncChannels 抢订阅状态导致消息被误过滤）
     *   - 跳过尾部的"清理本地多余消息"——那时 serverIds 只含该频道的 id，
     *     跑清理会把其它频道的本地消息全删掉
     */
    private suspend fun syncMessagesFullChatz(
        api: ChatzApi,
        server: ServerConfig,
        channelId: Int? = null
    ): Int {
        AppLog.d(TAG, "📥 [Chatz] 全量同步开始...")
        val serverIds = mutableSetOf<Long>()
        var sinceId = 0L
        var newCount = 0
        var hasMore = true
        var networkFailed = false
        var lastPageSize = -1

        // msg.channelId 是远程 id；发给 API 的 channel 参数同样用远程 id
        val remoteChannelId = channelId?.let { LocalIds.remoteChannelIdOf(it) }

        // 只入库「已订阅频道 + 无频道的 Gotify 消息」的远程 id 集合（见增量同步里的说明）
        val subscribedRemoteChannels = if (channelId == null) {
            withContext(Dispatchers.IO) {
                channelDao.getSubscribed(server.id).map { it.remoteId }.toHashSet()
            }
        } else {
            emptySet()
        }

        // 单频道补拉只是"订阅后让列表不空"，不是同步全部历史，限制最多翻 BACKFILL_MAX_PAGES 页
        val maxPages = if (channelId != null) BACKFILL_MAX_PAGES else Int.MAX_VALUE
        var pageCount = 0

        while (hasMore && pageCount < maxPages) {
            pageCount++
            val result = withContext(Dispatchers.IO) {
                api.getMessages(
                    limit = 50,
                    since = sinceId,
                    channelId = remoteChannelId,
                    archived = "all"
                )
            }
            if (result == null) {
                networkFailed = true
                AppLog.w(TAG, "⚠️ [Chatz] 拉取失败，服务器不可达")
                break
            }
            // 服务端不做「未订阅频道」过滤（管理员拿到的是全频道），
            // 所以过滤之后的 messages 可能比 result.messages 小；分页游标必须
            // 基于原始列表的 last().id 推进，否则会原地打转。这里加一道兜底：
            // 服务端返回了非空、但游标没动 → 强制退出，避免死循环拉爆接口。
            val nextSinceId = result.messages.last().id
            if (nextSinceId <= sinceId) {
                AppLog.w(TAG, "⚠️ [Chatz] 分页游标未推进（since=$sinceId），提前结束全量同步")
                break
            }
            lastPageSize = result.messages.size
            if (result.messages.isEmpty()) break

            // 分页游标必须基于原始列表；入库只取已订阅频道的消息。
            // 单频道补拉时请求本身就带了 channel 参数，只需再按 id 兜一次底
            val messages = if (remoteChannelId != null) {
                result.messages.filter { it.channelId == remoteChannelId }
            } else {
                result.messages.filter { msg ->
                    msg.channelId == null || subscribedRemoteChannels.contains(msg.channelId)
                }
            }

            // messages 可能为空（本页全来自未订阅频道），此时不能调用 dao.getExistingIds()：
            // 空列表会生成非法的 IN () 查询
            if (messages.isNotEmpty()) {
                val localIds = messages.map { LocalIds.makeMessageId(server.slot, it.id) }
                val existingIds = withContext(Dispatchers.IO) {
                    dao.getExistingIds(localIds)
                }
                val existingSet = existingIds.toHashSet()
                newCount += (localIds.size - existingIds.size)

                val starredMap: Map<Long, Boolean> = withContext(Dispatchers.IO) {
                    if (existingIds.isEmpty()) emptyMap()
                    else dao.getIdStarredPairs(existingIds).associate { it.id to it.starred }
                }
                val readMap: Map<Long, IdReadPair> = withContext(Dispatchers.IO) {
                    if (existingIds.isEmpty()) emptyMap()
                    else dao.getIdReadPairs(existingIds).associate { it.id to it }
                }

                withContext(Dispatchers.IO) {
                    for (msg in messages) {
                        val entity = msg.toEntity(server)
                        if (existingSet.contains(entity.id)) {
                            // 与增量同步同理：本地已读状态不能被服务端的未读覆盖
                            dao.upsert(
                                mergeReadState(entity, readMap[entity.id])
                                    .copy(starred = starredMap[entity.id] == true)
                            )
                        } else {
                            val initial = if (entity.archivedAt != null) {
                                entity.copy(starred = true)
                            } else {
                                entity
                            }
                            dao.insert(initial)
                        }
                    }
                }
                serverIds.addAll(localIds)
            }

            // 判满必须用**原始页大小**：过滤后可能不足 50，但服务端还有下一页。
            // 用过滤后的 messages.size 会让翻页提前终止、历史静默缺失。
            if (result.messages.size < 50) {
                hasMore = false
            } else {
                // 分页游标是服务端游标，必须用远程 id（已在页首校验是严格前进的）
                sinceId = nextSinceId
            }
        }

        // 单频道补拉时绝不能跑清理：serverIds 只含该频道的 id，
        // 会把其它频道的本地消息全部当成"服务端已没有"删掉
        if (channelId == null) {
            withContext(Dispatchers.IO) {
                if (networkFailed) {
                    AppLog.w(TAG, "⚠️ [Chatz] 网络失败，跳过本地清理")
                } else if (serverIds.isEmpty()) {
                    // 空结果不清理，避免把该服务器本地历史整体删掉（S1 风险）
                    AppLog.w(TAG, "⚠️ [Chatz] 本次全量未拉到消息，跳过本地清理")
                } else if (lastPageSize == 50) {
                    // ⚠️ 只有「翻到最后一页（不满 50）」才说明服务端消息已经全在
                    // serverIds 里，这时清理才是安全的。若是因为游标打转、
                    // maxPages（单频道补拉）或任何中途 break 提前结束，
                    // serverIds 是残缺的，此时清理 = 把没拉到的历史当成"服务端已删"
                    // 删光，属于数据丢失。宁可留幽灵消息，也绝不清空本地历史。
                    AppLog.w(
                        TAG,
                        "⚠️ [Chatz] 全量同步未翻完（末页满 50），跳过本地清理以免误删本地历史"
                    )
                } else {
                    // 只清理【这台服务器】的本地多余消息（S1 风险）
                    val localIds = dao.getLocalGotifyUnstarredIds(server.id)
                    val toDelete = localIds.filter { it !in serverIds }
                    var deletedCount = 0
                    toDelete.chunked(500).forEach { chunk ->
                        deletedCount += dao.deleteByIds(chunk)
                    }
                    AppLog.d(TAG, "🗑️ [Chatz] 清理 $deletedCount 条本地多余消息")
                }
            }
        }

        AppLog.d(TAG, "✅ [Chatz] 全量同步完成，新增 $newCount 条")
        return newCount
    }

    /**
     * 重新拉取某台服务器的频道与未读数（WS channelCreated/Updated、订阅成功后调用）
     */
    private fun refreshChannels(serverId: String) {
        viewModelScope.launch { refreshChannelsFor(serverId) }
    }

    /**
     * 主动重拉**所有** Chatz 服务器的频道列表（含名称 / 图标等元信息）
     *
     * 为什么需要这条兜底通道：WS 的 channelCreated / channelUpdated 只有在 App 连着且
     * 在前台时才到得了，而「从后台切回前台」和「打开抽屉」这两条高频路径原先都**不带**
     * 频道同步 —— 表现就是服务端新建或改了频道，App 里非要杀进程重启才看得到。
     * 频道图标同理：它不存在内存缓存里，而是以绝对地址存在 `channels.image`，
     * 跟着同一份记录走，所以修好频道刷新，图标也就跟着对了。
     *
     * `channelsFlow` 是 Room 的响应式查询，这里写完 DB 它会自动推给 UI，无需手动通知。
     * 网络不可用时 syncChannels 内部会 catch，不影响其它路径。
     */
    fun refreshChannelList() {
        viewModelScope.launch {
            refreshChatzServerIds()
            val serverIds = channelMetaServerIdsFlow.value
            if (serverIds.isEmpty()) return@launch
            AppLog.d(TAG, "🔄 主动刷新频道列表，共 ${serverIds.size} 台服务器")
            for (id in serverIds) refreshChannelsFor(id)
        }
    }

    /**
     * 服务端数据覆盖本地前，把**本地的已读状态**并回去
     *
     * 背景：对已存在的消息走的是 `dao.upsert`（REPLACE，整行覆盖），服务端返回的
     * `is_read` 会直接冲掉本地状态。
     *
     * 合并方向由 [IdReadPair.readSynced] 决定，**不再是简单的"已读优先"**：
     *
     *   1. 本地未对齐（read_synced = false）→ **本地赢**
     *      这是"本地点了已读、但上报服务端失败"的补偿：服务端那条仍是未读，
     *      若被覆盖，用户就会看到「已读样式没了」。待上报队列会在下次同步补报。
     *
     *   2. 本地已对齐（read_synced = true）→ **服务端赢**
     *      本地这个已读状态曾经和服务端对过账，服务端现在说未读，说明**别处
     *      （网页端 / 其它设备）把它标回了未读**，必须跟随。
     *
     * 为什么必须区分（踩过的坑）：原实现一刀切「任一方已读即已读」，于是只要本地
     * 存着**旧身份的已读**，就永久压住服务端、且永远不会被纠正 —— 表现就是用户报的
     * 「换 token 后**新消息**变成已读了，但服务端看还是未读」。
     *
     * 旧身份的已读从哪来（已定位）：服务端已读按 **user_id** 存在 message_reads
     * （服务端 GET /message 的 `LEFT JOIN message_reads r ON ... AND r.user_id = ?`），
     * 而本地 messages 按 `(server_id, remote_id)` 存、**不区分身份** ——
     * 同一台服务器换 token 前后共用同一批本地行。旧 token 身份读过的消息
     * （is_read=1）就这么留在了本地。（实例：服务端 users=1 yezi/2 alice/3 bob，
     * message_reads 只有 user_id 1 和 3，alice 一条都没有 → alice 拉任何消息
     * 服务端都回未读，但本地挂着 yezi 的已读。）
     *
     * 数据层面的根治就是本函数这套「已对齐则服务端赢」的裁决 —— 旧身份留下的
     * 已读会被服务端按 `read_synced = 1` 权威纠正回来，不需要额外的本地清理。
     * （曾经还配了一个 `resetReadStateForServer` 主动清零的补丁，已因「误伤同身份
     * 换凭据」于 2026-09-29 停用，见 [resetReadStateForTokenChange]。）
     * 本函数只负责「同步对账」这一环的裁决方向。
     *
     * 另注：本地「标未读」未对齐时（is_read=false, readSynced=false）同样按本地赢，
     * 否则刚点的未读会被服务端旧的已读状态立刻覆盖回去。
     */
    private fun mergeReadState(entity: MessageEntity, local: IdReadPair?): MessageEntity {
        if (local == null) return entity

        // 未对齐 = 本地有待上报的意图，本地赢（已读/未读都算）
        if (!local.readSynced) {
            return entity.copy(
                isRead = local.isRead,
                readAt = if (local.isRead) local.readAt else null,
                readSynced = false
            )
        }

        // 已对齐：服务端说了算，同时标记为已对齐（避免下次再走未对齐分支）
        return entity.copy(
            isRead = entity.isRead,
            readAt = if (entity.isRead) entity.readAt else null,
            readSynced = true
        )
    }

    private suspend fun refreshChannelsFor(serverId: String) {
        val api = chatzApi(serverId) ?: return
        refreshChatzServerIds()
        val server = serverOf(serverId)
        // 顺带缓存当前账号身份：抽屉长按频道时判「管理频道」入口要不要显示
        loadCurrentUser(api, server.id)
        syncChannels(api, server)
        syncUnreadCounts(api, server)
    }

    /**
     * 拉取并缓存某台服务器的当前账号（`/auth/me`），供 [canManageChannel] 判权限
     */
    private suspend fun loadCurrentUser(api: ChatzApi, serverId: String) {
        val me = withContext(Dispatchers.IO) { api.getMe() }
        if (me != null) {
            currentUserByServer.value = currentUserByServer.value + (serverId to me)
        }
    }

    /**
     * 当前账号能不能管理这个频道（改名 / 描述 / 公开性 / 图标 / 删除）
     *
     * 口径与服务端一致：`isSuper || creator_id === userId`。
     * 缓存还没就绪（频道同步没跑完 / 拉取失败）时返回 false —— 宁可隐藏入口，
     * 也不要给出「点了必 403」的菜单项。
     */
    fun canManageChannel(channel: ChatzChannel): Boolean {
        val me = currentUserByServer.value[channel.sourceServerId] ?: return false
        return me.isSuper || channel.creatorId == me.id
    }

    // ==================== 频道订阅 ====================

    /**
     * 订阅频道
     *
     * 顺序：本地置为已订阅（抽屉立刻可见）→ 拉 /channel 对账 → 回调 UI
     *       → 最后在后台补拉该频道的历史消息
     *
     * 补拉放在 onDone 之后：它有可能是几十个分页请求，
     * 不能让发现页那行一直卡在"处理中"。
     */
    fun subscribeChannel(
        channel: ChatzChannel,
        password: String? = null,
        onDone: (ChannelSubscribeResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val server = serverOf(channel.serverId)
            val api = chatzApi(server.id)
            // channel.id 是本地 id；出站用远程 id
            val result = if (api == null) ChannelSubscribeResult.Failed
            else withContext(Dispatchers.IO) {
                api.subscribeChannel(remoteChannelIdOf(channel), password)
            }
            if (result is ChannelSubscribeResult.Success) {
                withContext(Dispatchers.IO) { channelDao.updateSubscribed(channel.id, true) }
                refreshChannels(server.id)
                backfillChannelMessages(server.id, channel.id)
            }
            onDone(result)
        }
    }

    /**
     * 重新订阅后补拉该频道的历史消息
     *
     * 必须补的原因：退订时把该频道的本地消息删掉了，而增量同步用的是
     * since=本地最大消息ID，补不回这段"低于水位线"的空缺
     * （这也是为什么重新订阅后要手动刷新才看得到消息）。
     *
     * 两个触发点（本机订阅成功、别的设备订阅的 WS 事件）会并发调用，
     * 用 backfillingChannels 保证同一频道只拉一次。
     */
    private suspend fun backfillChannelMessages(serverId: String, channelId: Int) {
        val api = chatzApi(serverId) ?: return

        // 入参 channelId 是本地频道 id（全局唯一）；去重 key 带上 serverId 便于排查
        val key = "$serverId|$channelId"
        if (!backfillingChannels.add(key)) {
            AppLog.d(TAG, "⏭️ 该频道正在补拉，跳过重复请求: $key")
            return
        }
        try {
            val server = serverOf(serverId)
            withContext(Dispatchers.IO) {
                runCatching {
                    // syncMessagesFullChatz 内部会把本地 id 转成远程 id 再发请求
                    syncMessagesFullChatz(api, server, channelId)
                }.onFailure {
                    AppLog.e(TAG, "补拉频道消息失败: ${it.message}")
                }
            }
            // 补拉不走 syncAllServers，所以保留上限要在这里单独补一次
            trimDatabaseIfNeeded(server.id)
        } finally {
            backfillingChannels.remove(key)
        }
    }

    /**
     * 退订频道
     *
     * 成功后做本地清理（见 clearChannelLocal）。
     */
    fun unsubscribeChannel(channel: ChatzChannel, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val server = serverOf(channel.serverId)
            val ok = callChatz(server.id) { it.unsubscribeChannel(remoteChannelIdOf(channel)) }
            if (ok) {
                withContext(Dispatchers.IO) { clearChannelLocal(server.id, channel.id) }
            }
            onDone(ok)
        }
    }

    /**
     * 退订后的本地清理
     *
     *   - 频道标记为未订阅 → 抽屉的 observeSubscribed() 立刻把它移出列表
     *   - 删掉该频道的本地消息 → 主列表和未读数立刻干净（重新订阅会从服务端同步回来，不会真丢）
     *
     * 这里刻意不调 refreshChannels()：
     *   本地状态就是权威的，服务端的确认由 WS subscriptionChanged 事件异步补齐；
     *   多拉一次 /channel 反而会和本地写入竞争，把刚设的未订阅覆盖回已订阅。
     */
    private suspend fun clearChannelLocal(serverId: String, channelId: Int) {
        channelDao.updateSubscribed(channelId, false)
        // 按 server 限定删除，避免跨服务器误删（channelId 本身已命名空间化，这里是双保险）
        dao.deleteByServerAndChannel(serverId, channelId)
    }

    /**
     * 静音 / 取消静音
     */
    fun setChannelMuted(channel: ChatzChannel, muted: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val server = serverOf(channel.serverId)
            val ok = callChatz(server.id) { it.muteChannel(remoteChannelIdOf(channel), muted) }
            if (ok) {
                withContext(Dispatchers.IO) { channelDao.updateMuted(channel.id, muted) }
            }
            onDone(ok)
        }
    }

    /**
     * 向频道发消息（Chatz）
     *
     * 发送成功后**不本地插入**：服务端会把这条消息经 WS 广播回来（和其他设备收到的新消息
     * 走同一条路径），本地插入会和服务端的回声抢同一行。作为补偿，WS 没连上时会在发送后
     * 补一次同步（见下），免得用户以为"没发出去"。
     *
     * 正文由 [buildMessageBody] 拼：带附件时会在正文末尾追加一段 markdown 链接
     * （服务端自带的 WebUI 只认正文里的 markdown，不吃 extras）。
     *
     * @param draft 正文 / 附件 / 标题 / 优先级 / 标签
     * @param onDone null = 请求失败（网络 / 非 Chatz / 未配置）；否则是服务端结果，
     *   其中 [ChatzSendResult.dropped] 为 true 表示被路由规则丢弃、并没有创建消息
     */
    fun sendChannelMessage(
        channel: ChatzChannel,
        draft: OutgoingMessage,
        onDone: (ChatzSendResult?) -> Unit = {}
    ) {
        val body = buildMessageBody(draft.text, draft.attachment)
        if (body.isEmpty()) {
            onDone(null)
            return
        }
        val server = serverOf(channel.serverId)
        // channel.id 是本地 id；出站用远程 id
        val remoteChannelId = remoteChannelIdOf(channel)
        viewModelScope.launch {
            // 先登记"这是我刚发的"（**必须在请求发出之前**）：服务端会把这条经 WS
            // 广播回来，收到时按它跳过"给自己弹通知"
            SelfSentMessages.mark(server.id, channel.id, body)

            val result = chatzApi(server.id)?.let { api ->
                withContext(Dispatchers.IO) {
                    api.sendMessage(
                        ChatzSendBody(
                            message = body,
                            channelId = remoteChannelId,
                            appId = deriveSendAppId(api, server.id, remoteChannelId),
                            title = draft.title?.takeIf { it.isNotBlank() },
                            priority = draft.priority,
                            tags = draft.tags.takeIf { it.isNotEmpty() },
                            extras = sendExtras(draft)
                        )
                    )
                }
            }
            AppLog.d(
                TAG,
                "发送消息到 [${server.id}] 频道 $remoteChannelId: " +
                        (if (result == null) "失败" else "ok(id=${result.id}, dropped=${result.dropped})")
            )

            // 入库靠服务端 WS 回声。这条服务器的长连接没连上时补一次同步，
            // 否则消息要等下次回到前台才出现，用户会以为发送失败。
            // syncAllServers 自带重入保护，正在同步时它会自己跳过。
            if (result != null && !result.dropped &&
                GotifyService.serverStates[server.id] != ConnectionState.CONNECTED
            ) {
                AppLog.d(TAG, "[${server.id}] WS 未连接，发送后补一次同步")
                syncAllServers(forceFullSync = false)
            }

            onDone(result)
        }
    }

    /**
     * 推导发消息用的 appid
     *
     * 优先「默认频道 == 目标频道」的应用，没有就用列表里的第一个（与服务端
     * 「取 id 最小的应用」的回落一致）。
     *
     * 本地缓存（AppIconCache）还没加载完时退回服务端的 /application：否则会不传 appid，
     * 由服务端按「id 最小的应用」署名，自己发的消息卡片上应用图标/名字就串了。
     */
    private suspend fun deriveSendAppId(
        api: ChatzApi,
        serverId: String,
        remoteChannelId: Int
    ): Int? {
        val apps = AppIconCache.getAppList(serverId) ?: api.getApplications()
        return apps.firstOrNull { it.channelId == remoteChannelId }?.id
            ?: apps.firstOrNull()?.id
    }

    /**
     * 正在进行的附件上传
     *
     * 只有一个：新上传会取消旧的。留着它是为了能**取消** ——
     * 20MB 走移动网络时会等很久，取消后协程被取消、Retrofit 的挂起调用随之取消，
     * 底层 OkHttp 请求也就断了。
     */
    private var uploadJob: Job? = null

    /**
     * 上传一个附件（图片或任意文件）
     *
     * 只做"读出来 → 传上去 → 给出可用的绝对地址"，不碰消息内容：
     * 上传成功后由 UI 记下这个附件（输入框上方出现预览条），用户确认（可配文字）后
     * 再调 [sendChannelMessage]，正文里那段链接由它统一拼。
     *
     * @param serverId 必须和消息发往的那台一致 —— 附件是存在那台服务器上的
     */
    fun uploadAttachment(
        serverId: String,
        uri: Uri,
        onDone: (AttachmentUploadResult) -> Unit
    ) {
        val server = serverOf(serverId)
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { queryLocalFile(uri) }

            // 先按声明大小拦一次：20MB 的文件读进内存还好，2GB 的视频会直接 OOM
            if (meta.size > MAX_ATTACHMENT_BYTES) {
                AppLog.w(TAG, "附件过大，已拦下: ${meta.name} (${meta.size}B)")
                onDone(AttachmentUploadResult.TooLarge)
                return@launch
            }

            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                AppLog.e(TAG, "附件读取失败: $uri")
                onDone(AttachmentUploadResult.Unreadable)
                return@launch
            }
            // 声明大小读不到时（有些 provider 不给），按实际字节数再判一次
            if (bytes.size.toLong() > MAX_ATTACHMENT_BYTES) {
                onDone(AttachmentUploadResult.TooLarge)
                return@launch
            }

            val result = chatzApi(server.id)?.let { api ->
                withContext(Dispatchers.IO) { api.uploadAttachment(meta.name, bytes, meta.mime) }
            }
            if (result == null) {
                AppLog.e(TAG, "附件上传失败: ${meta.name} (${bytes.size}B)")
                onDone(AttachmentUploadResult.Failed)
                return@launch
            }

            AppLog.d(TAG, "附件上传成功: ${result.url} (${result.size}B, image=${result.isImage})")
            onDone(
                AttachmentUploadResult.Ok(
                    UploadedAttachment(
                        url = absoluteUrlOf(server, result.url),
                        name = result.name.ifBlank { meta.name },
                        size = result.size,
                        isImage = result.isImage,
                        contentType = result.contentType
                    )
                )
            )
        }
    }

    /**
     * 消息正文
     *
     * 附件**必须以 markdown 链接的形式出现在正文里**：服务端自带的 WebUI 只在正文上跑
     * marked（普通卡片根本不读 extras，只有聚合子消息才读），正文里没有 `![](url)`
     * 它就只会把裸 URL 显示成一行链接。
     * 而本 App 的卡片反过来 —— 图片从 extras 取（utils.MessageText），
     * 文件从 extras.attachment 取，并且会把这段 markdown 从展示文本里剥掉。
     * 于是同一条消息两边各取所需，正文里那段链接只有 WebUI 会看见。
     *
     * 用户只写字时正文就是文字本身；只发附件时正文就是那段链接（服务端要求 message 非空）。
     */
    private fun buildMessageBody(text: String, attachment: UploadedAttachment?): String {
        val caption = text.trim()
        if (attachment == null) return caption
        // 服务端把文件名清洗成 [\w.\-中文]，不会带 ] ( 这些会破坏 markdown 语法的字符
        val md = if (attachment.isImage) {
            "![](${attachment.url})"
        } else {
            "[${attachment.name}](${attachment.url})"
        }
        return if (caption.isEmpty()) md else "$caption\n\n$md"
    }

    /**
     * 取消正在进行的附件上传
     *
     * ⚠️ 被取消的那次上传**不会**再回调 onDone（协程直接结束），所以调用方要自己把
     *    "上传中"的界面状态收回去，别等回调。
     */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
    }

    /**
     * 一次发送的 extras（附件 + 引用，两者可同时存在）
     *
     * 引用这两个 key 与通知栏快捷回复（ReplyReceiver.replyExtras）刻意保持一致，
     * 这样两条路发出的回复在两端渲染出来的效果完全相同。
     *
     * ⚠️ 服务端还会往这个对象里**覆盖写入** `sender`（发送者快照），所以这里不要用
     *    任何会与它冲突的 key。
     */
    private fun sendExtras(draft: OutgoingMessage): Map<String, Any>? {
        val extras = mutableMapOf<String, Any>()
        draft.attachment?.let { extras.putAll(attachmentExtras(it)) }
        if (draft.isReply) {
            draft.replyToRemoteId?.let { extras["reply_to"] = it }
            draft.replyToText?.takeIf { it.isNotBlank() }?.let { extras["reply_to_text"] = it }
        }
        return extras.takeIf { it.isNotEmpty() }
    }

    /**
     * 附件的 extras
     *
     * 图片走 Gotify 既有的约定（`extras.image` + `client::display`）—— 本 App 的卡片和
     * 通知大图都从这几个字段取图（服务端的 WebUI 不吃 extras，它只看正文里的 markdown）；
     * 其他文件走自定义的 `extras.attachment`，由客户端的附件卡片渲染。
     * 两者互斥 —— 图片不再额外挂 attachment，避免卡片上出现"图 + 文件"两份。
     */
    private fun attachmentExtras(a: UploadedAttachment): Map<String, Any> =
        if (a.isImage) {
            mapOf(
                "image" to a.url,
                "client::display" to mapOf(
                    "contentType" to (a.contentType ?: "image/*"),
                    "url" to a.url
                )
            )
        } else {
            mapOf(
                "attachment" to mapOf(
                    "url" to a.url,
                    "name" to a.name,
                    "size" to a.size
                )
            )
        }

    /**
     * 相对路径 → 可直接放进 extras 的绝对地址
     *
     * 和图标同一套做法（AppIconCache.resolveIconUrl）：服务端只存相对路径，
     * 绝对地址在客户端拼，这样换域名 / 局域网 IP 都不会把消息里存死的地址搞坏。
     */
    private fun absoluteUrlOf(server: ServerConfig, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        var base = server.url.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    /**
     * 从 content:// 读展示名、声明的字节数、MIME
     *
     * 展示名只用于两处：服务端取扩展名（图片以魔数为准）、客户端做附件卡片的标题。
     */
    private fun queryLocalFile(uri: Uri): LocalFileMeta {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        var size = -1L

        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                        c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }.onFailure { AppLog.w(TAG, "读取附件元信息失败: ${it.message}") }

        return LocalFileMeta(
            name = name,
            size = size,
            mime = resolver.getType(uri) ?: "application/octet-stream"
        )
    }

    /**
     * 频道发现页数据
     *
     * 数据来源是 /channel/discover 与 /channel 的并集（按 id 去重）：
     *   - discover  → 全部公开频道 + 真实订阅状态（subscribed/muted/unreadCount）
     *   - /channel  → 普通用户拿到已订阅频道、管理员拿到全部频道（含私有），
     *                 subscribed 同样是服务端真实值
     * 只调 discover 会让"管理员未订阅的私有频道"没有任何入口，
     * 一旦退订就在 App 里彻底消失，所以这里必须取并集。
     *
     * 结果原样 upsert 进本地，未订阅的频道就以 subscribed=false 存下来。
     *
     * 图片字段要像 syncChannels 一样解析成绝对地址，否则发现页图标加载不出来。
     *
     * @return 按 id 排序、图片 URL 已解析的列表；非 Chatz / 未配置 / 两个接口都失败时返回空列表
     */
    suspend fun loadDiscoverChannels(serverId: String = "", q: String? = null): List<ChatzChannel> {
        // 未指定服务器时取第一台 Chatz 协议服务器（发现页是"某台服务器"的频道浏览）
        val sid = if (serverId.isBlank()) {
            ServerConfigStore.enabledProtocolServers(getApplication())
                .firstOrNull { ServerCapabilitiesHolder.isChatz(it.id) }?.id ?: legacyServer.id
        } else {
            serverId
        }
        val api = chatzApi(sid) ?: return emptyList()
        val server = serverOf(sid)

        val base = normalizeBaseUrl(server.url)

        // 搜索结果只来自 /channel/discover（搜公开频道）；没搜索时两个来源合并去重：
        //   /channel/discover → 全部公开频道 + 真实订阅状态
        //   /channel          → 普通用户返回已订阅频道；管理员返回全部频道（含私有）
        // 只调 discover 的话，"管理员未订阅的私有频道"在 App 里就完全没有入口了，
        // 退订之后再也找不回来。两个来源的 subscribed 现在都是服务端真实值，后者覆盖前者即可。
        //
        // ⚠️ 不要再 sortedBy{id}：服务端 discover 已按「创建者优先 + id」排好序，
        //    客户端重排会把「自己创建的排前面」抹掉。保留服务端顺序即可。
        val resolving = if (!q.isNullOrBlank()) {
            val discoverList = withContext(Dispatchers.IO) { api.discoverChannels(q) }
            discoverList
        } else {
            val (subscribedList, discoverList) = withContext(Dispatchers.IO) {
                api.getChannels() to api.discoverChannels()
            }
            val merged = LinkedHashMap<Int, ChatzChannel>()
            for (ch in discoverList) merged[ch.id] = ch
            for (ch in subscribedList) merged[ch.id] = ch
            merged.values.toList()
        }

        if (resolving.isEmpty()) return emptyList()

        // 带上来源身份；id 命名空间化交给 toEntity（返回给 UI 的仍是本地 id）
        val resolved = resolving
            .map { it.copy(image = resolveImageUrl(base, it.image), serverId = server.id, remoteId = it.id) }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            channelDao.upsertAll(
                resolved.map { ch -> ch.chatzChannelToEntity(server.id, server.slot, now) }
            )
        }
        return resolved
    }

    // ==================== Chatz WebSocket 事件 ====================

    /**
     * 处理 Chatz WebSocket 事件
     *
     * 广播只带原始 JSON + 来源 serverId（事件体里的 id 仍是**服务端原始值**），
     * 这里一次性解析出 server，再传给各 handler 按 server.slot 命名空间化成本地 id。
     * 传空 serverId（旧广播 / 兜底）时回落到 legacy 协议服务器（slot 0）。
     */
    fun handleChatzWsEvent(rawJson: String, serverId: String? = null) {
        viewModelScope.launch {
            try {
                val server = serverOf(serverId)
                val obj = JsonParser.parseString(rawJson).asJsonObject
                val event = obj.get("event")?.asString ?: return@launch
                when (event) {
                    "messageAggregated" -> handleAggregatedEvent(obj, server)
                    "messageDeleted" -> handleDeletedEvent(obj, server)
                    "messageRead" -> handleReadEvent(obj, server)
                    "messageUnread" -> handleUnreadEvent(obj, server)
                    "messagesReadAll" -> handleReadAllEvent(obj, server)
                    "messageArchived" -> handleArchivedEvent(obj, server)
                    "messageUnarchived" -> handleUnarchivedEvent(obj, server)
                    "channelCreated", "channelUpdated" -> handleChannelUpsertEvent(obj, server)
                    "channelDeleted" -> handleChannelDeletedEvent(obj, server)
                    "subscriptionChanged" -> handleSubscriptionChangedEvent(obj, server)
                    "userUpdated" -> handleUserUpdatedEvent(server)
                    else -> AppLog.d(TAG, "⚠️ 未处理的 Chatz 事件: $event")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleChatzWsEvent 失败: ${e.message}")
            }
        }
    }

    private suspend fun handleAggregatedEvent(obj: JsonObject, server: ServerConfig) {
        val msgObj = obj.getAsJsonObject("message") ?: return
        val msg = Gson().fromJson(msgObj, GotifyMessage::class.java)
        // 约定：messageAggregated 里的 message 对象不在这里手工转换 id，
        // 由 toEntity(server) 统一派生本地 id（避免双重位移）
        val entity = msg.toEntity(server)
        withContext(Dispatchers.IO) {
            val existing = dao.getMessageById(entity.id)
            if (existing == null) {
                upsertPreservingStarred(entity)
            } else {
                // ★ 折叠是「同一条消息内容变大」，**不是新消息**：
                //   服务端广播体里没有 isRead / readAt / archivedAt
                //   （只有 REST 列表用的 messages.js:rowToMessage 才带这些），
                //   而 dao.upsert 是 @Insert(REPLACE) —— 全列覆盖。
                //   直接写会把已读、归档、收藏、read_synced 和本地 receivedAt 全冲掉，
                //   表现为「刚看过的那条又变未读」。所以本地状态一律沿用。
                dao.upsert(
                    entity.copy(
                        starred = existing.starred,
                        isRead = existing.isRead,
                        readAt = existing.readAt,
                        readSynced = existing.readSynced,
                        archivedAt = existing.archivedAt,
                        receivedAt = existing.receivedAt
                    )
                )
            }
        }
        AppLog.d(TAG, "📨 聚合更新: id=${msg.id}, aggCount=${msg.aggCount}")
    }

    private suspend fun handleDeletedEvent(obj: JsonObject, server: ServerConfig) {
        val remoteId = obj.get("id")?.asLong ?: return
        val id = LocalIds.makeMessageId(server.slot, remoteId)
        withContext(Dispatchers.IO) { dao.deleteById(id) }
        AppLog.d(TAG, "📨 消息删除: id=$id (remoteId=$remoteId)")
    }

    private suspend fun handleReadEvent(obj: JsonObject, server: ServerConfig) {
        val remoteId = obj.get("messageId")?.asLong ?: return
        val messageId = LocalIds.makeMessageId(server.slot, remoteId)
        val readAt = obj.get("readAt")?.asLong ?: System.currentTimeMillis()
        // 服务端事件 ⇒ 已对账（read_synced=1），之后以服务端为准，本地不再覆盖
        withContext(Dispatchers.IO) { dao.markReadConfirmed(messageId, readAt) }
        AppLog.d(TAG, "📨 已读: id=$messageId")
    }

    private suspend fun handleUnreadEvent(obj: JsonObject, server: ServerConfig) {
        val remoteId = obj.get("messageId")?.asLong ?: return
        val messageId = LocalIds.makeMessageId(server.slot, remoteId)
        // 服务端事件 ⇒ 已对账（read_synced=1）
        withContext(Dispatchers.IO) { dao.markUnreadConfirmed(messageId) }
        AppLog.d(TAG, "📨 未读: id=$messageId")
    }

    private suspend fun handleReadAllEvent(obj: JsonObject, server: ServerConfig) {
        val readAt = obj.get("readAt")?.asLong ?: System.currentTimeMillis()
        val remoteChannelId: Int? = if (obj.has("channelId") && !obj.get("channelId").isJsonNull) {
            obj.get("channelId").asInt
        } else null
        val channelId = remoteChannelId?.let { LocalIds.makeChannelId(server.slot, it) }
        val affected = withContext(Dispatchers.IO) {
            // 事件来自某一台服务器 → 只更新那一台的消息
            // 服务端事件 ⇒ 已对账（read_synced=1）
            val n = dao.markAllReadConfirmedForServer(server.id, readAt, channelId)
            if (channelId == null) {
                channelDao.clearAllUnread(server.id, readAt)
            } else {
                channelDao.clearUnread(channelId, readAt)
            }
            n
        }
        // 只要真的改动了行，就显式喊一次刷新。
        //
        // 不能只靠 Room 的 InvalidationTracker：服务端的「全部已读」是**全库**标记，
        // 而本地只加载了最近 30 条 —— 当前可见的那些往往本来就已读，
        // 重拉后结果集不变，Paging 认为"没变化"就不重绘（见 listRefreshTick 的注释）。
        if (affected > 0) {
            listRefreshTickFlow.value = listRefreshTickFlow.value + 1
        }
        AppLog.d(TAG, "📨 批量已读: channelId=$channelId，影响 $affected 行")
    }

    /**
     * 服务端归档 → 本地同时更新 archived_at 和 starred
     * starred 是 UI 真相，必须跟着变
     */
    private suspend fun handleArchivedEvent(obj: JsonObject, server: ServerConfig) {
        val remoteId = obj.get("messageId")?.asLong ?: return
        val messageId = LocalIds.makeMessageId(server.slot, remoteId)
        val archivedAt = obj.get("archivedAt")?.asLong ?: System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            dao.archive(messageId, archivedAt)
            dao.updateStarred(messageId, true)
        }
        AppLog.d(TAG, "📨 收藏: id=$messageId")
    }

    private suspend fun handleUnarchivedEvent(obj: JsonObject, server: ServerConfig) {
        val remoteId = obj.get("messageId")?.asLong ?: return
        val messageId = LocalIds.makeMessageId(server.slot, remoteId)
        withContext(Dispatchers.IO) {
            dao.unarchive(messageId)
            dao.updateStarred(messageId, false)
        }
        AppLog.d(TAG, "📨 取消收藏: id=$messageId")
    }

    private suspend fun handleChannelUpsertEvent(obj: JsonObject, server: ServerConfig) {
        val chObj = obj.getAsJsonObject("channel") ?: return
        val channel = Gson().fromJson(chObj, ChatzChannel::class.java)
        val base = normalizeBaseUrl(server.url)
        val now = System.currentTimeMillis()
        val localChannelId = LocalIds.makeChannelId(server.slot, channel.id)
        withContext(Dispatchers.IO) {
            // channelCreated / channelUpdated 是「广播给所有人」的事件，只带频道元信息，
            // 里面的订阅状态不可信：
            //   - channelCreated 里 subscribed 被服务端写死为 true
            //   - channelUpdated 里根本没有这个字段，Gson 会填成 ChatzChannel 的默认值 true
            // 用事件里的值 → 别人的新频道会凭空出现在你的抽屉里。
            // 订阅状态的权威来源只有 /channel（见 syncChannels），这里保留本地已有的值；
            // 本地没有这条记录时按"未订阅"落库，如果其实订了，下一次同步会纠正过来。
            val local = channelDao.getById(localChannelId)
            channelDao.upsert(
                ChannelEntity(
                    id = localChannelId,
                    name = channel.name,
                    description = channel.description,
                    image = resolveImageUrl(base, channel.image),
                    isPublic = channel.isPublic,
                    creatorId = channel.creatorId,
                    createdAt = channel.createdAt,
                    passwordProtected = channel.passwordProtected,
                    unreadCount = local?.unreadCount ?: channel.unreadCount,
                    subscribed = local?.subscribed ?: false,
                    muted = local?.muted ?: false,
                    updatedAt = now,
                    serverId = server.id,
                    remoteId = channel.id
                )
            )
        }
        AppLog.d(TAG, "📨 频道更新: ${channel.id} ${channel.name}")

        // 上面只落了频道元信息，订阅状态是谁订谁不订、事件里说不清，
        // 所以再拉一次 /channel 拿权威状态：
        //   - 自己建的频道（服务端自动订阅了创建者）→ 这里变成已订阅，抽屉出现
        //   - 别人建的频道 → /channel 里没有它 → 保持未订阅，抽屉不出现
        refreshChannels(server.id)
    }

    private suspend fun handleChannelDeletedEvent(obj: JsonObject, server: ServerConfig) {
        val remoteChannelId = obj.get("channelId")?.asInt ?: return
        val channelId = LocalIds.makeChannelId(server.slot, remoteChannelId)
        withContext(Dispatchers.IO) {
            channelDao.deleteChannelById(channelId)
        }
        if (currentChannelIdFlow.value == channelId) {
            currentChannelIdFlow.value = null
        }
        AppLog.d(TAG, "📨 频道删除: id=$channelId")
    }

    private suspend fun handleSubscriptionChangedEvent(obj: JsonObject, server: ServerConfig) {
        val remoteChannelId = obj.get("channelId")?.asInt ?: return
        val channelId = LocalIds.makeChannelId(server.slot, remoteChannelId)
        val action = obj.get("action")?.asString ?: return
        val muted = obj.get("muted")?.asBoolean ?: false

        // 用 when 精确匹配，不要用 if/else 兜底：
        // 未知 action 落到 else 会误触发 clearChannelLocal（删消息 + 退订）
        when (action) {
            "subscribed" -> {
                withContext(Dispatchers.IO) { channelDao.updateSubscribed(channelId, true) }
                // 别的设备订阅了频道 → 这边抽屉马上会多出这个频道，但本地没有它的历史消息，
                // 而增量同步的水位线在那些消息之后、永远补不回来，所以这里补拉一次。
                // 本机自己订阅时 subscribeChannel 也会调，靠 backfillingChannels 去重。
                backfillChannelMessages(server.id, channelId)
            }

            "unsubscribed" -> {
                // 与本地退订走同一套清理，避免别的设备退订后这边残留消息
                withContext(Dispatchers.IO) { clearChannelLocal(server.id, channelId) }
            }

            "muted" -> {
                // 别的设备改的静音状态同步到本地（通知闸口读的就是这个字段）
                withContext(Dispatchers.IO) { channelDao.updateMuted(channelId, muted) }
            }

            else -> AppLog.d(TAG, "⚠️ 未知的订阅动作，已忽略: $action")
        }

        AppLog.d(TAG, "📨 订阅变化: channelId=$channelId, action=$action, muted=$muted")
    }

    /**
     * 服务端推「你的账号信息变了」（头像 / 昵称 / 用户名 / 邮箱 / 密码）
     *
     * 典型场景：网页端换了头像，客户端这边也该跟着变，不该等到下次开弹窗才看到。
     *
     * 这里**只广播一个信号**，不解析具体字段 —— 收到就发一条 "账号信息已变更" 的
     * 本地广播，让正在显示的「个人信息」弹窗自己重拉 /auth/me。
     *
     * 为什么不在这里直接改 UI 状态：
     *   账号信息不属于消息库，本 ViewModel 没有它的持久化位置；
     *   而弹窗是 Compose 自己 remember 的状态。
     *   用广播解耦，只有弹窗开着时才真的产生一次网络请求。
     */
    private suspend fun handleUserUpdatedEvent(server: ServerConfig) {
        AppLog.d(TAG, "👤 账号信息已变更，通知界面刷新（serverId=${server.id}）")
        // 用 Application 发广播（ViewModel 没有自己的 sendBroadcast）。
        // setPackage 限定应用内投递，避免被同设备其它 App 收到。
        getApplication<Application>().sendBroadcast(
            Intent(ACTION_USER_UPDATED).setPackage(getApplication<Application>().packageName)
        )
    }

    // ==================== 删除 / 恢复 / 清空 ====================

    fun deleteLocal(msg: GotifyMessage): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) { dao.deleteById(msg.id) }
            AppLog.d(TAG, "🗑️ 本地删除 ID: ${msg.id}")
            RecentMessagesWidget.notifyWidgetDataChanged(getApplication())
        }
    }

    fun restoreMessage(msg: GotifyMessage): Job {
        return viewModelScope.launch {
            // 从 DB 还原的消息带 serverId / remoteId，按来源还原即可
            val server = serverOf(msg.serverId)
            withContext(Dispatchers.IO) { dao.insert(msg.toEntity(server)) }
            AppLog.d(TAG, "↩️ 恢复 ID: ${msg.id}")
            RecentMessagesWidget.notifyWidgetDataChanged(getApplication())
        }
    }

    /**
     * 从服务端删除一条消息
     *
     * serverId / remoteId / 是否 ntfy 全部取自入参消息自带的来源信息，
     * 不再依赖全局单源配置（S1-3：出站必须用远程 id）。
     */
    fun deleteFromServer(msg: GotifyMessage) {
        viewModelScope.launch {
            try {
                val server = serverOf(msg.sourceServerId)
                // ntfy 没有删除接口，协议服务器才删
                if (server.isNtfy) return@launch
                if (server.url.isBlank() || server.token.isBlank()) return@launch

                val remoteId = LocalIds.remoteIdOf(msg.id)
                val success = if (ServerCapabilitiesHolder.isChatz(server.id)) {
                    ChatzApi.getInstance(server.url, server.token).deleteMessage(remoteId)
                } else {
                    GotifyApi.getInstance(server.url, server.token).deleteMessage(remoteId)
                }
                if (success) {
                    AppLog.d(TAG, "☁️ 服务器删除成功 ID: ${msg.id} (remoteId=$remoteId)")
                } else {
                    AppLog.e(TAG, "☁️ 服务器删除失败")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "❌ 服务器删除异常: ${e.message}", e)
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            // 「清空」是用户主动行为，保留全局语义
            withContext(Dispatchers.IO) { dao.deleteAll() }
            AppLog.d(TAG, "🧹 清空")
            RecentMessagesWidget.notifyWidgetDataChanged(getApplication())
        }
    }

    /**
     * 换服务器（某台的地址变了）时清掉 **该台** 的本地数据
     *
     * 绝不能再用全局 deleteAll()：配置一台新服务器就清空全部历史（S1 风险）。
     * 同 serverId 改 token 不清（与旧行为一致）。
     */
    fun clearAllForServerSwitch(serverId: String): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteByServer(serverId)
                channelDao.deleteAll(serverId)
            }
            AppLog.d(TAG, "🧹 切换服务器，清空本地消息与频道（serverId=$serverId）")
            RecentMessagesWidget.notifyWidgetDataChanged(getApplication())
        }
    }

    /**
     * 换 token（同 serverId、凭据变了）时作废该服务器的本地已读状态
     *
     * ⚠️ **已停用（2026-09-29）**：不再有任何调用者，保留仅为可回滚。
     *
     * 停用原因：这个函数把「token 字符串变了」当成「身份变了」，无条件把该服务器
     * 全部消息的 is_read 清 0。但服务端的运维动作（应用 Token 批量改格式、
     * 重新部署、Keystore 抖动导致配置短暂读空后回写）同样会让快照 token 与
     * 当前值不同，而身份并没变 —— 此时本地已读被无辜清空，用户看到的现象是
     * 「覆盖安装后历史消息全部变未读」。
     *
     * 而它想解决的问题（跨身份串读：本地已读属于旧身份、压住服务端真实状态）
     * 已经被 read_synced 机制根治，见 [AppDatabase.MIGRATION_9_10] 与
     * [MessageDao.IdReadPair] 的说明 —— 服务端返回的状态会按 `read_synced = 1`
     * 权威覆盖本地，无需先在本地粗暴清零。
     *
     * 如果将来真的要按身份作废，正确做法是先调 /auth/me 比对 userId，
     * 身份一致就只更新 token、不动已读（`token 变了` ≠ `身份变了`）。
     *
     * 与 [clearAllForServerSwitch] 的区别（历史设计）：
     *   - 换服务器（URL 变）→ 清消息与频道（那边两者是不同服务器，数据无意义）
     *   - 换 token（URL 不变）→ 只作废已读，消息/频道/收藏全部保留
     */
    @Deprecated("已停用：会误伤「同身份换凭据」场景，改用服务端权威对账（read_synced）")
    fun resetReadStateForTokenChange(serverId: String): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.resetReadStateForServer(serverId) }
            AppLog.d(TAG, "🔑 更换 token，已读状态已作废、待服务端对账（serverId=$serverId）")
        }
    }

    // ==================== 搜索 ====================

    suspend fun searchMessages(query: String): List<GotifyMessage> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 远程搜索：遍历**所有** Chatz 服务器，结果合并去重（按本地 id）
        // 只要有一台服务器搜索成功，就返回远程结果，不再落到本地 FTS（与改造前一致）
        val chatzServers = ServerConfigStore.enabledProtocolServers(getApplication())
            .filter { ServerCapabilitiesHolder.isChatz(it.id) && it.url.isNotBlank() && it.token.isNotBlank() }
        if (chatzServers.isNotEmpty()) {
            val merged = LinkedHashMap<Long, GotifyMessage>()
            var anySuccess = false
            for (server in chatzServers) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        ChatzApi.getInstance(server.url, server.token)
                            .searchMessages(trimmed, SEARCH_LIMIT)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Chatz 搜索失败（${server.id}）: ${e.message}")
                        null
                    }
                }
                if (result != null) {
                    anySuccess = true
                    // 结果里的 id 命名空间化成本地 id 再给 UI（slot 0 时不变）
                    for (m in result.messages) {
                        val remote = m.id
                        val local = LocalIds.makeMessageId(server.slot, remote)
                        merged[local] = m.copy(
                            id = local,
                            serverId = server.id,
                            remoteId = remote
                        )
                    }
                }
            }
            if (anySuccess) return merged.values.toList()
        }

        val imageMarkdownRegex = Regex("!\\[.*?\\]\\(.*?\\)")
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val canUseFts = tokens.isNotEmpty() &&
                tokens.all { it.length >= FTS_MIN_TOKEN_LENGTH }

        val candidates: List<MessageEntity> = withContext(Dispatchers.IO) {
            if (canUseFts) {
                val matchExpr = buildFtsMatchExpr(tokens)
                try {
                    AppLog.d(TAG, "🔍 FTS5 搜索: $matchExpr")
                    dao.searchFts(matchExpr, SEARCH_LIMIT)
                } catch (e: Exception) {
                    AppLog.w(TAG, "FTS5 搜索失败，降级 LIKE：${e.message}")
                    dao.search("%$trimmed%", SEARCH_LIMIT)
                }
            } else {
                AppLog.d(TAG, "🔍 短查询走 LIKE: $trimmed")
                dao.search("%$trimmed%", SEARCH_LIMIT)
            }
        }

        return candidates.filter { entity ->
            val cleanMessage = entity.message.replace(imageMarkdownRegex, "")
            val title = entity.title ?: ""
            title.contains(trimmed, ignoreCase = true) ||
                    cleanMessage.contains(trimmed, ignoreCase = true)
        }.map { it.toGotifyMessage() }
    }

    private fun buildFtsMatchExpr(tokens: List<String>): String {
        return tokens.joinToString(" AND ") { raw ->
            val trimmed = raw.trim('"', '\'')
            val escaped = trimmed.replace("\"", "\"\"")
            "\"$escaped\""
        }
    }

    suspend fun getMessageIndex(messageId: Long, appId: Int): Int? {
        return withContext(Dispatchers.IO) {
            val target = dao.getMessageById(messageId) ?: return@withContext null
            val channelId = currentChannelIdFlow.value
            val tag = currentTagFlow.value
            when {
                // 与 createPagingSource 的分派顺序保持一致
                tag != null -> dao.countNewerThanForTag(
                    target.date, target.receivedAt, target.id, tag
                )
                channelId != null -> dao.countNewerThanForChannel(
                    target.date, target.receivedAt, target.id, channelId
                )
                appId == FILTER_ALL -> dao.countNewerThan(target.date, target.receivedAt, target.id)
                appId == FILTER_STARRED -> dao.countNewerThanForStarred(
                    target.date, target.receivedAt, target.id
                )
                appId == FILTER_UNREAD -> {
                    // ⚠️ 与 createPagingSource 一致：空列表不查库
                    val ids = chatzServerIdsFlow.value
                    if (ids.isEmpty()) 0
                    else dao.countNewerThanForUnread(
                        target.date, target.receivedAt, target.id, ids
                    )
                }
                else -> {
                    // appId 是合成筛选 id：还原出所属服务器 + 服务端原始 appid
                    val slot = LocalIds.slotOfAppFilter(appId)
                    val sid = ServerConfigStore.bySlot(getApplication(), slot)?.id
                        ?: legacyServer.id
                    dao.countNewerThanForAppInServer(
                        target.date, target.receivedAt, target.id, sid, LocalIds.appIdOf(appId)
                    )
                }
            }
        }
    }
}