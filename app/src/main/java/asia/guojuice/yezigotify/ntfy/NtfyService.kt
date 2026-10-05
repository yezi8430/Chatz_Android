package asia.guojuice.yezigotify.ntfy

import asia.guojuice.yezigotify.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket

class NtfyService(
    private val baseUrl: String,
    private val topic: String,
    private val username: String? = null,
    private val password: String? = null,
    private val token: String? = null,
    keepAliveSeconds: Int = 45,
    private val tag: String = "NtfyService",
) {

    var onMessage: ((NtfyMessage) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    /**
     * ntfy `message_delete`：某条消息被删，参数是 ntfy 的远程消息 id
     *
     * 上层（持 Room 与 serverId 的那一层）据此删本地那一行。
     * 本类不碰数据库，只做事件转发 —— 分层干净，也便于单测。
     */
    var onMessageDelete: ((String) -> Unit)? = null

    /** ntfy `message_clear`：整个 topic 被清空 */
    var onMessageClear: (() -> Unit)? = null

    @Volatile
    var keepAliveSeconds: Int = keepAliveSeconds.coerceAtLeast(20)
        set(value) {
            val newVal = value.coerceAtLeast(20)
            field = newVal
            // 仅记录，实际心跳由 OkHttp 管理
        }

    /**
     *  协程作用域
     *
     * 关键：stop() 时【不能】调用 scope.cancel()。
     * 一旦 scope 的 Job 被取消，之后所有 scope.launch { } 都会立即返回一个
     * 已 Cancelled 的 Job，块内代码不执行 —— scheduleReconnect() 会永久失效，
     * 表现为 stop() 后再 start() "看起来连上了但再也不重连"。
     *
     * 本类中 scope 只承载 reconnectJob 一个长期 job，
     * stop() 只要 cancel reconnectJob + 关闭 WebSocket 就够，
     * scope 本身留着可复用，不会持有系统资源，也不会泄漏（NtfyService
     * 实例被 GC 时 scope 一并回收）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var webSocket: WebSocket? = null

    fun getWebSocket(): WebSocket? = webSocket

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var isConnecting = false
    private val maxReconnectDelay = 300_000L

    private var reconnectJob: Job? = null
    private var manualStop = false

    fun isStartingOrAlive(): Boolean = isRunning

    fun start() {
        // 如果已经在运行，先彻底关闭旧连接（但不设置 manualStop = true，因为我们希望自动重连）
        if (isRunning) {
            AppLog.d(tag, "ntfy 已在运行，执行强制重启")
            reconnectJob?.cancel()
            closeWebSocket()
            isRunning = false
            isConnected = false
            // 注意：不设置 manualStop = true，后续 connect() 会重新建立连接
            // 同时清除可能残留的重连任务
        }
        // 确保允许重连
        manualStop = false
        // 发起新连接
        connect()
    }

    fun onNetworkRestored() {
        AppLog.d(tag, "ntfy 收到网络恢复通知")
        reconnectAttempts = 0
        reconnectJob?.cancel()
        if (!isConnected && !isConnecting && !manualStop) {
            connect()
        }
    }

    private fun connect() {
        if (isConnecting) {
            AppLog.d(tag, "已有连接尝试正在进行，跳过")
            return
        }
        isConnecting = true
        closeWebSocket()
        AppLog.d(tag, "开始 WebSocket 订阅 ntfy topic: $topic")

        val client = NtfyClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
            token = token,
            // ⚠️ 从 0 改成 30（2026-10-03）。原来注释说「靠上层 sendPing() 定期发空帧保活」——
            // 但那个保活只是 `ws.send("")` 看一眼返回值，**检测不出半死连接**
            // （对端没了时 send 照样返回 true，只是排进发送缓冲）。
            // OkHttp 的 WS ping 才是唯一能自己发现这事的机制：每 30s 发一个 ping 帧，
            // 上一个还没等到 pong 就再发，判定连接已死并回调 onDisconnected
            // ⇒ 走 NtfyService 自己的指数退避重连。标准 WS 服务端会回 pong（ntfy 也是）。
            pingInterval = 30
        )

        isRunning = true

        webSocket = client.subscribeWebSocket(
            topic = topic,
            onMessage = { msg -> onMessage?.invoke(msg) },
            onConnected = {
                isConnecting = false
                AppLog.d(tag, "ntfy WebSocket 连接成功")
                isConnected = true
                reconnectAttempts = 0
                onConnectionChanged?.invoke(true)
            },
            onDisconnected = {
                isConnecting = false
                AppLog.d(tag, "ntfy WebSocket 连接断开")
                isConnected = false
                onConnectionChanged?.invoke(false)
                if (!manualStop) {
                    scheduleReconnect()
                }
            },
            // ── 删除事件 ──
            // ntfy 会推 message_delete（单条）与 message_clear（整 topic 清空）。
            // 之前这两类事件被静默丢弃，表现就是「服务端删了，App 里还留着」。
            // 这里只把事件**上报**给上层（App 持有 Room 与 serverId），
            // 由上层决定删哪些行 —— 本类不依赖数据库，保持分层干净。
            onMessageDelete = { remoteId -> onMessageDelete?.invoke(remoteId) },
            onMessageClear = { onMessageClear?.invoke() }
        )
    }

    fun stop() {
        AppLog.i(tag, "ntfy 订阅已停止（主动）")
        manualStop = true
        isConnecting = false
        //  取消挂起的重连任务，并清引用
        reconnectJob?.cancel()
        reconnectJob = null
        //  关闭 WebSocket（真正的网络资源）
        closeWebSocket()
        isRunning = false
        isConnected = false
        //  不再调用 scope.cancel()：
        //    保留 scope 供下次 start() 复用，避免 scheduleReconnect 静默失效
        onConnectionChanged?.invoke(false)
    }

    /**
     * 作废当前连接（**不**设 manualStop）
     *
     * 与 [stop] 的区别：`stop()` 是「用户主动关掉」，作废是「这条连接已经不可信了，扔掉重来」。
     * 网络恢复 / 心跳看门狗需要的是后者 —— 走 `stop()` 会把 `manualStop` 设成 true，
     * 之后所有自动重连都被它挡住（表现就是「连接再也回不来」）。
     *
     * ⚠️ 为什么必须有它：`isConnected` / `isRunning` 只在 WS 回调里更新，
     *    而**半死连接**（对端没了、本地 socket 还在）不会触发任何回调
     *    ⇒ 这两个标志会一直是脏的 `true`，把 [onNetworkRestored] 和
     *    `NtfyConnection.connect()` 里的守卫全部挡掉，网络回来了也永远不重连。
     *    （2026-10-03：和 Chatz 那次修的死锁是同一个形状。）
     */
    fun abandon() {
        AppLog.d(tag, "ntfy 作废当前连接（不改 manualStop）")
        reconnectJob?.cancel()
        reconnectJob = null
        isConnecting = false
        isRunning = false
        isConnected = false
        closeWebSocket()
        onConnectionChanged?.invoke(false)
    }

    private fun closeWebSocket() {
        webSocket?.let {
            if (!it.close(1000, "主动关闭")) {
                // 如果 close 返回 false，说明已经关闭或正在关闭
            }
        }
        webSocket = null
    }

    private fun scheduleReconnect() {
        if (manualStop) return
        isRunning = false
        isConnected = false
        reconnectJob?.cancel()
        reconnectAttempts++
        val delay = (5_000L * (1 shl (reconnectAttempts - 1))).coerceAtMost(maxReconnectDelay)
        AppLog.d(tag, "ntfy 将在 ${delay}ms 后重连（第 $reconnectAttempts 次）")
        reconnectJob = scope.launch {
            delay(delay)
            if (!manualStop) {
                AppLog.d(tag, "ntfy 开始第 $reconnectAttempts 次重连")
                connect()
            }
        }
    }
}