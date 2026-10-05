package asia.guojuice.yezigotify.connection

import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.NetworkUtil
import asia.guojuice.yezigotify.config.ServerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WebSocket 事件信封（只有 event / id 两个字段是所有事件共有的）
 */
data class WsEvent(
    val id: Long = 0,
    val event: String? = null
)

/**
 * 网络类异常判定（用于把"断网"和"服务端出错"在日志上分开）
 */
internal fun isNetworkIssue(t: Throwable): Boolean {
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
 * 一台服务器的连接
 *
 * 一台服务器 = 一条长连接（协议服务器是 `/stream` WebSocket，ntfy 是订阅 WebSocket）。
 * 心跳、指数退避重连、主动停止标记都在这一层统一实现，
 * 子类只需要实现 [connect] / [start] / [stop] / [sendPing]。
 */
abstract class ServerConnection(
    initialConfig: ServerConfig,
    protected val deps: ConnectionDeps
) {

    /** 日志 tag：沿用 GotifyService，保持现有 logcat 过滤习惯（adb logcat -s Gotify_GotifyService:*） */
    protected open val logTag: String = "GotifyService"

    /**
     * 当前配置
     *
     * 可变：只有 `keepAliveSeconds` / `displayName` 这类**不影响连接参数**的字段
     * 允许原地更新（[updateMeta]），避免为改一个保活间隔就断掉 WebSocket。
     */
    var config: ServerConfig = initialConfig
        private set

    val serverId: String get() = config.id

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        protected set

    @Volatile
    protected var isConnecting = false

    protected var reconnectAttempts = 0
    protected var reconnectJob: Job? = null
    protected var heartbeatJob: Job? = null

    /**
     * 主动停止标记
     *
     * `stop()` 必须置 true 并取消心跳 / 重连任务，否则暂停的服务器会被
     * 心跳或指数退避的重连任务自动拉回来（表现为"开关关掉了却还在收消息"）。
     * 初值 true：构造出来还没 start，任何回调都不该触发重连。
     */
    @Volatile
    protected var manualStop = true

    /** 当前保活间隔（秒）；config 里 <= 0 表示"自动"（WiFi 45s / 移动 180s） */
    protected val keepAliveSeconds: Int
        get() = if (config.keepAliveSeconds <= 0) {
            NetworkUtil.autoKeepAliveSeconds(deps.appContext)
        } else {
            config.keepAliveSeconds.coerceAtLeast(20)
        }

    /** 原地更新元信息（不改连接参数）。返回是否只有元信息变化 */
    fun updateMeta(newConfig: ServerConfig) {
        config = newConfig
    }

    protected fun updateState(newState: ConnectionState) {
        if (state != newState) {
            state = newState
            deps.onStateChanged(config.id, newState)
        }
    }

    /**
     * 指数退避重连：5s → 10s → 20s …… 上限 5 分钟
     *
     * 指数用 `coerceAtMost(10)` 夹住再移位：不着色会溢出成负数，
     * 而 `coerceAtMost` 对负数不生效，delay(负数) 会直接抛异常。
     */
    protected fun scheduleReconnect() {
        if (manualStop) return
        reconnectJob?.cancel()
        reconnectAttempts++
        val delayMs = (5000L * (1 shl (reconnectAttempts - 1).coerceAtMost(10)))
            .coerceIn(5000L, MAX_RECONNECT_DELAY)
        AppLog.d(logTag, "[${config.id}] ${delayMs}ms 后重连（第 $reconnectAttempts 次）")
        reconnectJob = deps.scope.launch {
            delay(delayMs)
            if (!manualStop && deps.isNetworkAvailable()) connect()
        }
    }

    protected fun startHeartbeat() {
        stopHeartbeat()
        val interval = keepAliveSeconds
        AppLog.d(logTag, "[${config.id}] 心跳间隔: ${interval}s")
        heartbeatJob = deps.scope.launch {
            while (isActive) {
                delay(interval * 1000L)
                if (manualStop) return@launch

                // ── 兜底看门狗 ────────────────────────────────────────
                // 「状态不是已连接」时**先重建**，别指望 sendPing() 能救回来：
                // 半死连接（对端没了、本地 socket 还在）上 `send()` 照样返回 true，
                // 只是把数据排进了发送缓冲 —— 心跳看起来一切正常，连接其实早就没了。
                //
                // 触发场景：网络丢失只把状态改成 DISCONNECTED、socket 还挂着，
                // 而恢复回调那条路也没走通 ⇒ 会永久卡在「未连接」。
                // （2026-10-03 用户实测：飞行模式下楼、回来后一直不重连。）
                //
                // ⚠️ 三个前提条件都不能少，否则会破坏指数退避：
                //   `reconnectJob?.isActive != true` —— 已经排了重连任务就交给它。
                //     少了这一条，服务端长时间挂掉时看门狗会每个心跳周期强连一次，
                //     把「5s→10s→…→5min」的退避整个绕过去。
                //   `!isConnecting`   —— 正在握手中，别插一脚。
                //   `deps.isNetworkAvailable()` —— 没网时重建必然失败，纯浪费。
                if (state != ConnectionState.CONNECTED) {
                    if (deps.isNetworkAvailable() &&
                        !isConnecting &&
                        reconnectJob?.isActive != true
                    ) {
                        AppLog.w(logTag, "[${config.id}] 心跳发现未连接（state=$state）且没有待重连任务，强制重建")
                        reconnectAttempts = 0
                        cancelReconnect()
                        forceReconnect()
                    }
                    continue
                }

                sendPing()
            }
        }
    }

    protected fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 保活间隔变化时只重启心跳，不动 WebSocket（避免连接抖动） */
    fun restartHeartbeat() {
        if (manualStop) return
        startHeartbeat()
    }

    protected fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * 作废当前连接（不改 [manualStop]、不动心跳）
     *
     * 子类实现：把持有的 socket / 订阅对象置空并关闭。
     * 与 [stop] 的区别：这是「这条连接已经不可信了，扔掉重来」，不是「用户主动关掉」。
     */
    protected open fun abandonConnection() {}

    /**
     * 强制重建：先作废旧连接，再立刻连
     *
     * ⚠️ 必须区别于直接调 [connect]：子类的 `connect()` 一般都有
     *    「已有连接就跳过」的幂等早退，而半死连接靠 `send()` 是发现不了的，
     *    会被那行早退**永久**挡在门外。凡是"我认为这条连接已经废了"的场合，
     *    都要走这里而不是 `connect()`。
     */
    protected fun forceReconnect() {
        abandonConnection()
        connect()
    }

    /** 建立/恢复连接；由 [start] 或重连任务调用。子类自行做幂等（isConnecting / manualStop） */
    protected abstract fun connect()

    abstract fun start()

    abstract fun stop()

    /** 发一次保活空帧 */
    abstract fun sendPing()

    /** 网络丢失：作废旧连接 + 置为断开（恢复时重建，见 [onNetworkRestored]） */
    open fun onNetworkLost() {
        abandonConnection()
        updateState(ConnectionState.DISCONNECTED)
    }

    /**
     * 网络恢复：未主动停止时**作废旧连接并立刻重建**
     *
     * ⚠️ 这里是 [forceReconnect] 而不是 [connect] —— 别改回去。
     *    网络一旦变过，旧 socket 就可能已经是半死的；而半死连接看不出来
     *    （`send()` 照样返回 true），子类的 `connect()` 又有「已有连接就跳过」的幂等早退，
     *    用 `connect()` 会被那行挡回去、表现为「网络回来了却永远不重连」。
     *
     * ⚠️ 但**已经是 CONNECTED 就不要动**：系统在 App 启动时也会发一次网络可用回调，
     *    无条件重建会把刚建好的连接白拆一次，那期间到达的消息只能等下次同步。
     *    「状态是 CONNECTED、socket 却半死」这种罕见表象交给 OkHttp 的
     *    `pingInterval` 兜住（它会回调 onFailure 走正常重连链路）。
     */
    open fun onNetworkRestored() {
        if (manualStop) return
        reconnectAttempts = 0
        cancelReconnect()
        // 已经连上、或握手正在进行中 ⇒ 都别插一脚。
        //
        // `isConnecting` 那一条是为了避开启动时的「白拆」：系统在 App 起来后不久
        // 就会发一次网络可用回调，而此刻 WS 可能还在握手（state 仍是 RECONNECTING），
        // 只看 state 会误判成「没连上」，把刚发起的连接拆掉重来。
        if (state == ConnectionState.CONNECTED || isConnecting) {
            AppLog.d(logTag, "[${config.id}] 网络恢复回调，但已连接/正在握手（state=$state, connecting=$isConnecting），跳过重建")
            return
        }
        forceReconnect()
    }

    companion object {
        const val MAX_RECONNECT_DELAY = 300_000L
    }
}