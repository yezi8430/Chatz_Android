package asia.guojuice.yezigotify.ntfy

import asia.guojuice.yezigotify.AppLog
import okhttp3.Credentials.basic
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NtfyClient(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val token: String? = null,
    private val pingInterval: Long = 0,
) {
    /**
     *  共享 OkHttpClient 改造
     *
     * 原实现：每次 new NtfyClient 都 OkHttpClient.Builder().build()，
     *   而 NtfyService 每次重连都 new NtfyClient → 连接池、Dispatcher 线程池、
     *   DNS 缓存、RouteDatabase 全部重复分配，长期运行堆叠大量空闲连接与线程。
     *
     * 新实现：从 companion 的 baseClient 派生。
     *   - 派生 client 复用 baseClient 的连接池 / Dispatcher / DNS / RouteDatabase
     *   - 只覆盖 readTimeout 与 pingInterval 这两个"连接级"参数
     *   - 相同参数组合缓存复用，避免重复 newBuilder()
     */
    private val okhttp: OkHttpClient = obtainClient(
        readTimeoutSeconds = 0,          // WebSocket 长连接，不超时
        pingIntervalSeconds = pingInterval
    )

    /**
     * 判断是否为"网络不可用"类异常（飞行模式、断网、切网、DNS 失败等）
     * 这类异常在网络恢复后会自动重连，属于预期内的，不需要完整堆栈
     */
    private fun isNetworkIssue(t: Throwable): Boolean {
        return t is java.net.SocketException
                || t is java.net.UnknownHostException
                || t is java.net.ConnectException
                || t is java.net.SocketTimeoutException
                || t is java.io.InterruptedIOException
                || t is java.io.EOFException
                || t is javax.net.ssl.SSLException
                || t is java.net.NoRouteToHostException
                || t is java.net.PortUnreachableException
    }

    /**
     * 使用 WebSocket 订阅 topic，路径为 /topic/ws
     *
     * @param onMessage       收到 `message` 事件（新消息）
     * @param onMessageDelete 收到 `message_delete` 事件（某条消息被删），参数是 ntfy 的远程消息 id
     * @param onMessageClear  收到 `message_clear` 事件（整个 topic 被清空）
     *
     * 为什么之前只处理 open/message/keepalive 是个缺口：
     *   ntfy 的实时通道**确实**会推删除事件（事件名 `message_delete`，
     *   载荷形如 `{"id":"...","time":...,"event":"message_delete","message":"<被打包的消息>"}`；
     *   整 topic 清空推 `message_clear`）。此前它们全落进 `else` 分支被静默丢弃，
     *   于是「服务端删了消息，App 里还在」——和 Chatz 增量同步看不见删除是同一类问题。
     */
    fun subscribeWebSocket(
        topic: String,
        onMessage: (NtfyMessage) -> Unit,
        onConnected: (() -> Unit)? = null,
        onDisconnected: (() -> Unit)? = null,
        onMessageDelete: ((String) -> Unit)? = null,
        onMessageClear: (() -> Unit)? = null
    ): WebSocket {
        // ★★★ 关键：路径应为 /topic/ws ★★★
        val url = "$baseUrl/$topic/ws"
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { addAuth(this) }
            .build()

        val ws = okhttp.newWebSocket(request, object : WebSocketListener() {
            private var connectedNotified = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.d("NtfyClient", "WebSocket 已打开，topic=$topic")
                // 按官方文档，连接后服务器会发送 open 事件，我们无需额外发送订阅消息
                // 我们等待收到 open 事件再触发 onConnected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val jsonObj = JSONObject(text)
                    val event = jsonObj.optString("event")
                    when (event) {
                        "open" -> {
                            if (!connectedNotified) {
                                connectedNotified = true
                                onConnected?.invoke()
                                AppLog.d("NtfyClient", "WebSocket 连接成功 (open 事件)")
                            }
                        }
                        "message" -> {
                            if (!connectedNotified) {
                                connectedNotified = true
                                onConnected?.invoke()
                            }
                            val msg = parseMessage(jsonObj)
                            onMessage(msg)
                        }
                        "keepalive" -> {
                            // 忽略心跳（不打日志，避免刷屏）
                        }
                        "message_delete" -> {
                            // 载荷里的 id 是被删那条消息的 ntfy id。
                            // openapi 定义里它在顶层（与 event 平级），但有些实现会
                            // 把消息体整个塞进顶层 —— 两种取法都试一遍，取到就用。
                            val id = jsonObj.optString("id").takeIf { it.isNotEmpty() }
                                ?: JSONObject(text).optJSONObject("message")?.optString("id")
                            if (!id.isNullOrEmpty()) {
                                onMessageDelete?.invoke(id)
                            } else {
                                AppLog.w("NtfyClient", "message_delete 事件缺少 id: $text")
                            }
                        }
                        "message_clear" -> {
                            onMessageClear?.invoke()
                        }
                        else -> {
                            // 其他类型，如 error / poll_request
                            AppLog.d("NtfyClient", "未知事件类型: $event")
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e("NtfyClient", "解析消息失败", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.d("NtfyClient", "WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
                onDisconnected?.invoke()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.d("NtfyClient", "WebSocket 已关闭: $code $reason")
                onDisconnected?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // ★ 网络类异常（飞行模式/断网/切网）只记一行 warning，
                //   不再打印完整堆栈，避免 logcat 刷屏
                if (isNetworkIssue(t)) {
                    AppLog.w("NtfyClient", "WebSocket 中断（网络不可用）: ${t.message}")
                } else {
                    AppLog.e("NtfyClient", "WebSocket 连接失败: ${t.message}", t)
                }
                onDisconnected?.invoke()
            }
        })
        return ws
    }

    private fun parseMessage(obj: JSONObject): NtfyMessage {
        // 1. 先尝试顶层 image 字段
        var imageUrl = obj.optString("image").takeIf { it.isNotEmpty() }

        // 2. 如果没有顶层 image，检查 attachment 是否是图片
        if (imageUrl == null) {
            val attachmentObj = obj.optJSONObject("attachment")
            attachmentObj?.let {
                val type = it.optString("type")
                // 如果 attachment 是图片类型，则使用它的 url
                if (type.startsWith("image/")) {
                    imageUrl = it.optString("url").takeIf { url -> url.isNotEmpty() }
                }
            }
        }

        // 3. 解析 attachment 信息存起来（后续可能用于显示文件名/大小）
        val attachmentObj = obj.optJSONObject("attachment")
        val attachment = attachmentObj?.let {
            NtfyMessage.Attachment(
                name = it.optString("name").takeIf { s -> s.isNotEmpty() },
                url = it.optString("url").takeIf { s -> s.isNotEmpty() },
                size = if (it.has("size")) it.optLong("size") else null,
                type = it.optString("type").takeIf { s -> s.isNotEmpty() }
            )
        }

        return NtfyMessage(
            id = obj.optString("id"),
            sequenceId = obj.optString("sequence_id").takeIf { it.isNotEmpty() },
            time = obj.optLong("time"),
            event = obj.optString("event"),
            topic = obj.optString("topic"),
            message = obj.optString("message"),
            title = obj.optString("title"),
            priority = obj.optInt("priority", 0),
            tags = obj.optJSONArray("tags")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            } ?: emptyList(),
            imageUrl = imageUrl,
            icon = obj.optString("icon").takeIf { it.isNotEmpty() },
            attachment = attachment
        )
    }

    /**
     *  addHeader → header
     *    addHeader 是"追加"，同一 Builder 复用时会重复添加 Authorization，
     *    服务器取第一个或最后一个不确定，可能触发 401。
     *    header 是"覆盖"，语义明确。
     */
    private fun addAuth(builder: Request.Builder) {
        when {
            !token.isNullOrBlank() -> {
                builder.header("Authorization", "Bearer $token")
            }
            !username.isNullOrBlank() && !password.isNullOrBlank() -> {
                builder.header("Authorization", basic(username, password))
            }
        }
    }

    companion object {
        private const val TAG = "NtfyClient"

        /**
         * 全局共享的 OkHttpClient 基础实例（懒加载）
         * - 连接池、Dispatcher（线程池）、DNS、RouteDatabase 全局唯一
         * - 后续通过 newBuilder() 派生：派生 client 复用这些全局资源，
         *   仅覆盖需要变化的字段（如 readTimeout / pingInterval）
         */
        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 按 (readTimeout, pingInterval) 组合缓存派生 client，
         * 避免同一配置反复 newBuilder() 产生冗余实例。
         *
         * 场景说明：NtfyService 重连时会反复 new NtfyClient，
         * 若每次都 newBuilder() 而不缓存，会创建大量几乎相同的 client 包装对象
         * （连接池虽共享，但 client 本身也是 GC 压力）。
         */
        private val derivedClientCache = ConcurrentHashMap<String, OkHttpClient>()

        private fun obtainClient(
            readTimeoutSeconds: Long,
            pingIntervalSeconds: Long
        ): OkHttpClient {
            val key = "r${readTimeoutSeconds}_p${pingIntervalSeconds}"
            return derivedClientCache.getOrPut(key) {
                baseClient.newBuilder()
                    // readTimeoutSeconds=0 表示不超时（WebSocket 必须）
                    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    // pingIntervalSeconds=0 表示不做 OkHttp 层 ping，
                    // 靠上层 NtfyConnection.sendPing() 定期发空帧保活
                    .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
                    .build()
            }
        }
    }
}