package asia.guojuice.yezigotify.connection

import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.config.AuthMode
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ntfy.NtfyMessage
import asia.guojuice.yezigotify.ntfy.NtfyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ntfy 服务器的连接
 *
 * 内部持有一个 [NtfyService] 实例 —— 它本来就是实例级状态（webSocket / isRunning /
 * isConnected / reconnectAttempts / reconnectJob 都是成员变量），天然支持多实例，
 * 所以这里只做"配置 → 实例"和"NtfyMessage → GotifyMessage"的适配。
 */
class NtfyConnection(
    config: ServerConfig,
    deps: ConnectionDeps
) : ServerConnection(config, deps) {

    private var ntfy: NtfyService? = null

    override fun start() {
        manualStop = false
        reconnectAttempts = 0

        val usePassword = config.authMode == AuthMode.USER_PASSWORD
        val service = NtfyService(
            baseUrl = config.url.trimEnd('/'),
            topic = config.topic,
            username = if (usePassword) config.username else null,
            password = if (usePassword) config.password else null,
            token = if (usePassword) null else config.token,
            keepAliveSeconds = keepAliveSeconds,
            // 沿用原日志 tag，保持 adb logcat -s Gotify_GotifyService:* 依然能看到 ntfy 日志
            tag = logTag
        ).apply {
            onMessage = { msg -> this@NtfyConnection.onNtfyEvent(msg) }
            onConnectionChanged = { connected ->
                if (!this@NtfyConnection.manualStop) {
                    this@NtfyConnection.updateState(
                        if (connected) ConnectionState.CONNECTED else ConnectionState.RECONNECTING
                    )
                }
            }
            // ── 删除事件 ──
            //
            // 这两类事件**不会**走 onMessage：NtfyClient 此前把它们归进 else 分支
            // 静默丢弃，所以下面 onNtfyEvent 里那段删除处理实际上从未被触发过。
            //
            // message_delete 的载荷里 id 是被删消息的 ntfy 字符串 id（顶层）。
            // 本地 id 由 (slot, 字符串 id) 确定性推导 —— 与入库时用的是同一个
            // LocalIds.ntfyLocalId，所以这里算出来的就是那一行。
            onMessageDelete = { remoteId ->
                if (remoteId.isNotBlank()) {
                    val localId = LocalIds.ntfyLocalId(this@NtfyConnection.config.slot, remoteId)
                    AppLog.d(
                        logTag,
                        "[${this@NtfyConnection.config.id}] ntfy 删除事件，远程ID=$remoteId，localId=$localId"
                    )
                    deps.onRemoteDeleted(this@NtfyConnection.config, localId)
                }
            }
            // message_clear = 整个 topic 清空。没有「单条 id」可用，
            // 交给上层按 serverId 清掉该服务器的全部 ntfy 消息。
            onMessageClear = {
                AppLog.d(logTag, "[${this@NtfyConnection.config.id}] ntfy 清空事件（message_clear）")
                deps.onTopicCleared(this@NtfyConnection.config)
            }
        }
        ntfy = service
        updateState(ConnectionState.RECONNECTING)
        AppLog.d(logTag, "[${config.id}] 启动 ntfy 订阅: topic=${config.topic}")
        service.start()
        startHeartbeat()
    }

    override fun stop() {
        manualStop = true
        stopHeartbeat()
        cancelReconnect()
        ntfy?.stop()
        ntfy = null
        updateState(ConnectionState.DISCONNECTED)
    }

    /**
     * 基类要求的"建立连接"
     *
     * ntfy 的连接与重连由 [NtfyService] **内部**自己管理（它自带 `connect` +
     * 指数退避的 `scheduleReconnect`），所以这里不做同款实现，只做兜底：
     * 被基类的重连任务或网络恢复回调调到时，确保实例在跑。
     */
    override fun connect() {
        if (manualStop) return
        val current = ntfy
        when {
            current == null -> start()
            !current.isStartingOrAlive() -> current.start()
            else -> AppLog.d(logTag, "[${config.id}] ntfy 实例已在运行，跳过重复连接")
        }
    }

    override fun sendPing() {
        val ws = ntfy?.getWebSocket() ?: return
        try {
            val success = ws.send("")
            if (success) {
                AppLog.d(logTag, "[${config.id}] 发送心跳到 ntfy")
            } else {
                AppLog.w(logTag, "[${config.id}] ntfy 心跳发送失败（连接可能已关闭）")
                ntfy?.onNetworkRestored()
            }
        } catch (e: Exception) {
            if (isNetworkIssue(e)) {
                AppLog.w(logTag, "[${config.id}] ntfy 心跳异常（网络不可用）: ${e.message}")
            } else {
                AppLog.e(logTag, "[${config.id}] ntfy 心跳异常: ${e.message}")
            }
            ntfy?.onNetworkRestored()
        }
    }

    /**
     * 作废底层 ntfy 订阅（不改 manualStop）
     *
     * ⚠️ 这是 ntfy 能自愈的关键一步。不做的话：
     *   `NtfyService.isRunning` / `isConnected` 都只在 WS 回调里更新，
     *   而半死连接**不会**触发回调 ⇒ 它们一直是脏 `true`，
     *   把 [connect] 和 ntfy 内部的 `onNetworkRestored()` 两道守卫全挡掉 ——
     *   网络回来了也永远不重连（与 2026-10-03 修的 Chatz 死锁同款）。
     *   顺带：基类心跳里那个兜底看门狗调 [ServerConnection.forceReconnect] 时，
     *   第一件事就是 `abandonConnection()`；ntfy 以前没实现它 ⇒ 看门狗形同空转。
     */
    override fun abandonConnection() {
        ntfy?.abandon()
    }

    override fun onNetworkRestored() {
        if (manualStop) return
        reconnectAttempts = 0
        cancelReconnect()

        // `state == DISCONNECTED` 是「基类明确判定这条连接已经丢了」的唯一信号
        // （只有 onNetworkLost 会置它）。此刻底层 ntfy 的 `isRunning` / `isConnected`
        // 多半是脏 `true`（半死连接不触发任何回调）⇒ 交给 ntfy 自己重连会被它的守卫挡掉，
        // 必须整个作废重建。
        //
        // 其余两种情况都交给 ntfy 内部：
        //   · 启动瞬间 state 是 RECONNECTING、isConnected 还是 false ⇒ 它自己会连上，
        //     别在这里白拆一次刚建好的连接
        //   · 正常掉线后在退避中也仍是 RECONNECTING ⇒ 让它立刻重试（配合上面刚 cancel 掉的退避）
        //
        // ⚠️ 为什么不用 `isConnecting`：那个字段是 ProtocolConnection 在用的，
        //    NtfyConnection 从来不赋值，写上去是永远为 false 的死条件。
        if (state == ConnectionState.DISCONNECTED) {
            forceReconnect()
        } else {
            ntfy?.onNetworkRestored()
        }
    }

    // ==================== NtfyMessage → GotifyMessage ====================

    private fun onNtfyEvent(msg: NtfyMessage) {
        AppLog.d(logTag, "[${config.id}] ntfy 事件: event=${msg.event}, id=${msg.id}, title=${msg.title}")

        if (msg.event == "delete" || msg.event == "messageDeleted" || msg.event == "message_delete") {
            val rawId = if (msg.event == "message_delete" && !msg.sequenceId.isNullOrBlank()) {
                msg.sequenceId
            } else {
                msg.id
            }
            // 本地 id 由 (slot, ntfy 字符串 id) 确定性推导，与入库时一致
            val localId = LocalIds.ntfyLocalId(config.slot, rawId)
            AppLog.d(logTag, "[${config.id}] ntfy 删除事件(${msg.event})，原ID=$rawId，localId=$localId")
            deps.onRemoteDeleted(config, localId)
            return
        }

        if (msg.event == "keepalive" || msg.event == "open") return

        val dateStr = formatDate(msg.time)
        val gotifyPriority = when (msg.priority) {
            5 -> 10
            4 -> 8
            3 -> 5
            2 -> 3
            1 -> 1
            else -> 5
        }

        val localId = LocalIds.ntfyLocalId(config.slot, msg.id)

        val extrasBuilder = mutableMapOf<String, Any>()
        msg.icon?.let { extrasBuilder["icon"] = it }
        if (msg.imageUrl != null) {
            extrasBuilder["client::display"] = mapOf(
                "contentType" to "image/jpeg",
                "url" to msg.imageUrl
            )
        }
        msg.attachment?.let { att ->
            att.url?.takeIf { it.isNotBlank() }?.let { url ->
                extrasBuilder["ntfy_attachment_url"] = url
            }
            att.name?.takeIf { it.isNotBlank() }?.let { name ->
                extrasBuilder["ntfy_attachment_name"] = name
            }
        }
        if (msg.tags.isNotEmpty()) {
            extrasBuilder["ntfy_tags"] = msg.tags
        }

        deps.onMessage(
            config,
            GotifyMessage(
                id = localId,
                // appid == 0 是"这是 ntfy 消息"的既有隐式约定（抽屉 / 图标 / 通知都依赖它）
                appid = 0,
                title = msg.title.ifEmpty { "ntfy" },
                message = msg.message,
                priority = gotifyPriority,
                date = dateStr,
                extras = if (extrasBuilder.isEmpty()) null else extrasBuilder,
                serverId = config.id,
                remoteId = LocalIds.remoteIdOf(localId)
            )
        )
    }

    private fun formatDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val millis = if (epochSeconds > 0) epochSeconds * 1000 else System.currentTimeMillis()
        return sdf.format(Date(millis))
    }
}