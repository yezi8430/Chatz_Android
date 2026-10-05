package asia.guojuice.yezigotify.connection

import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.db.LocalIds
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Chatz / Gotify 协议服务器的连接
 *
 * 走 `GET {baseUrl}/stream?token=xxx` 长连接，事件分三类：
 *   - 无 `event` 字段 → 一条新消息
 *   - `messageDeleted` → 消息被删除（这里直接处理，因为它同时影响状态栏通知）
 *   - 其余 Chatz 扩展事件 → 原样转给 ViewModel（id 由 ViewModel 按 slot 转换）
 */
class ProtocolConnection(
    config: ServerConfig,
    deps: ConnectionDeps
) : ServerConnection(config, deps) {

    /** 最新创建的 WebSocket */
    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * 当前"有效"的 WebSocket
     *
     * 重连时会先建新连接再关旧的，期间两个 socket 都可能回调；
     * 所有回调都先比对 `webSocket != currentWebSocket` 来早退，避免旧连接的事件污染。
     */
    @Volatile
    private var currentWebSocket: WebSocket? = null

    override fun start() {
        manualStop = false
        reconnectAttempts = 0
        connect()
        startHeartbeat()
    }

    override fun stop() {
        manualStop = true
        stopHeartbeat()
        cancelReconnect()
        val ws = currentWebSocket ?: webSocket
        webSocket = null
        currentWebSocket = null
        isConnecting = false
        try {
            ws?.close(1000, "Stopped")
        } catch (_: Exception) {
        }
        updateState(ConnectionState.DISCONNECTED)
    }

    override fun sendPing() {
        val ws = currentWebSocket ?: return
        try {
            val success = ws.send("")
            if (success) {
                AppLog.d(logTag, "[${config.id}] 发送心跳")
            } else {
                AppLog.w(logTag, "[${config.id}] 心跳发送失败（连接可能已关闭），重新连接")
                webSocket = null
                currentWebSocket = null
                connect()
            }
        } catch (e: Exception) {
            if (isNetworkIssue(e)) {
                AppLog.w(logTag, "[${config.id}] 心跳异常（网络不可用）: ${e.message}")
            } else {
                AppLog.e(logTag, "[${config.id}] 心跳异常: ${e.message}")
            }
            webSocket = null
            currentWebSocket = null
            connect()
        }
    }

    /**
     * 作废当前 WebSocket（不改 manualStop、不动心跳）
     *
     * ⚠️ 为什么必须有这一步：`connect()` 开头有「已有连接就跳过」的幂等早退，
     *    而 OkHttp 在 `pingInterval = 0` + `readTimeout = 0` 这套配置下
     *    **几乎不可能**自己发现「对端已经没了」这种半死连接 ——
     *    `send()` 照常返回 true，只是把数据排进了发送缓冲。
     *
     *    于是会形成死锁：网络丢了 → 若只改状态、socket 还挂着 →
     *    网络回来 → onNetworkRestored() → connect() → **被幂等那行挡回** ⇒ 永远不再重连。
     *    （2026-10-03 用户实测：开飞行模式下楼、回来后再没重连，服务卡一直显示未连接。）
     *
     * 旧 socket 还活着也无所谓：它的回调第一行都会比对 `webSocket != currentWebSocket`
     * 然后早退，不会污染新连接。
     */
    override fun abandonConnection() {
        val ws = currentWebSocket ?: webSocket
        webSocket = null
        currentWebSocket = null
        isConnecting = false
        try {
            ws?.close(1000, "Abandoned")
        } catch (_: Exception) {
        }
    }

    override fun connect() {
        if (isConnecting) return

        // 已经有一条活着的连接就直接返回
        //
        // 调用方里有些是"通知式"的（网络恢复回调、重连任务），它们并不知道当前已经连上。
        // 无条件往下走会额外开一条 WebSocket，而旧的那条**不会被关闭**
        // （原来那个关闭条件 `old != currentWebSocket` 基本永远为假：这两个变量总是
        // 一起被赋值、一起被置空），于是：
        //   - 服务端把每条消息往两条连接各推一次（白费流量，也是"同一条消息出现两次"的来源）
        //   - 旧 socket 一直开着直到服务端超时，属于泄漏
        // 需要**强制**重连的地方（心跳发送失败）会先把 currentWebSocket 置空再调这里。
        if (currentWebSocket != null) {
            AppLog.d(logTag, "[${config.id}] 已有连接，跳过重复 connect()")
            return
        }

        isConnecting = true

        // 兜底：把可能残留的 socket 关掉，避免泄漏
        val old = webSocket
        webSocket = null
        if (old != null) {
            try {
                old.close(1000, "Replacing")
            } catch (_: Exception) {
            }
        }

        val baseUrl = config.url.trimEnd('/')
        val token = config.token
        if (manualStop || baseUrl.isEmpty() || token.isEmpty()) {
            isConnecting = false
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/stream?token=$token")
            .addHeader("Authorization", "Bearer $token")
            .build()

        val ws = deps.client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != currentWebSocket) return
                AppLog.d(logTag, "[${config.id}] WebSocket 连接成功，协议=${response.protocol}")
                reconnectAttempts = 0
                isConnecting = false
                updateState(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != currentWebSocket) return
                AppLog.d(logTag, "[${config.id}] 收到原始消息: $text")
                try {
                    val eventObj = deps.gson.fromJson(text, WsEvent::class.java)
                    val event = eventObj.event
                    if (event == null) {
                        // 没有 event 字段 = 一条新消息
                        val raw = deps.gson.fromJson(text, GotifyMessage::class.java)
                        deps.onMessage(config, normalize(raw))
                    } else if (event == "messageDeleted") {
                        deps.onRemoteDeleted(
                            config,
                            // 事件里的 id 是服务端原始 id，先转成本地 id
                            LocalIds.makeMessageId(config.slot, eventObj.id)
                        )
                    } else if (event in CHATZ_EVENTS) {
                        deps.onChatzEvent(config, text)
                    } else {
                        AppLog.d(logTag, "未知事件类型: '$event'")
                    }
                } catch (e: Exception) {
                    AppLog.e(logTag, "[${config.id}] 解析失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != currentWebSocket) return
                this@ProtocolConnection.webSocket = null
                currentWebSocket = null
                isConnecting = false
                if (isNetworkIssue(t)) {
                    AppLog.w(logTag, "[${config.id}] WebSocket 中断（网络不可用）: ${t.message}")
                } else {
                    AppLog.e(logTag, "[${config.id}] WebSocket 连接失败: ${t.message}")
                }
                if (manualStop) return
                updateState(ConnectionState.RECONNECTING)
                if (deps.isNetworkAvailable()) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != currentWebSocket) return
                this@ProtocolConnection.webSocket = null
                currentWebSocket = null
                isConnecting = false
                if (manualStop) return
                if (code == 1000 || code == 1001) {
                    updateState(ConnectionState.DISCONNECTED)
                } else if (deps.isNetworkAvailable()) {
                    updateState(ConnectionState.RECONNECTING)
                    scheduleReconnect()
                } else {
                    updateState(ConnectionState.DISCONNECTED)
                }
            }
        })

        webSocket = ws
        currentWebSocket = ws
    }

    /**
     * 把服务端原始 id 命名空间化成本地 id
     *
     * 之后的流程（入库 / 广播 / 通知）全部用本地 id，出站 API 才用 remoteId。
     *
     * ⚠️ `channelId` 也必须一起转 —— 通知链路里`suppressedByChannelMute` 会拿它去查
     * `channels` 表（那是本地 id），按频道存的通知配置也以本地 id 为 key。
     * slot 0 时两者恰好相等，所以漏转的后果只在多台（slot > 0）的 Chatz 上暴露：
     * 频道静音会静默失效。
     *
     * 幂等性：`makeChannelId(slot, makeChannelId(slot, x)) == makeChannelId(slot, x)`，
     * 所以 `toEntity()` 里会再转一次也不会算错。
     */
    private fun normalize(raw: GotifyMessage): GotifyMessage {
        val remoteId = raw.id
        return raw.copy(
            id = LocalIds.makeMessageId(config.slot, remoteId),
            // 频道 id 同样跨了进程边界，一并转成本地 id
            channelId = raw.channelId?.let { LocalIds.makeChannelId(config.slot, it) },
            serverId = config.id,
            remoteId = remoteId
        )
    }

    companion object {
        /**
         * Chatz 扩展事件（需要交给 ViewModel 处理，而不是在连接层做业务）
         *
         * ⚠️ `messageAggregated` 事件体里的整个 `message` 对象**不做 id 转换**，
         * 由 ViewModel 用 `toEntity(server)` 统一派生本地 id，否则会双重位移。
         */
        val CHATZ_EVENTS = setOf(
            "messageAggregated",
            "messageRead",
            "messageUnread",
            "messagesReadAll",
            "messageArchived",
            "messageUnarchived",
            "channelCreated",
            "channelUpdated",
            "channelDeleted",
            "subscriptionChanged",
            // 账号信息（昵称/用户名/邮箱/头像/密码）在别处被改过。
            // 服务端在 6 个 /user/* 路由里都会推这一条；事件体是空的
            // `{"event":"userUpdated"}`，客户端收到后重新拉一次 /auth/me 即可。
            // ⚠️ 漏在这里会表现成 logcat 打「未知事件类型: 'userUpdated'」，
            //    事件被静默丢弃，界面永远不刷新。
            "userUpdated"
        )
    }
}