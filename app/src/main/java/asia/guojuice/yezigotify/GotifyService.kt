package asia.guojuice.yezigotify

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.connection.ConnectionDeps
import asia.guojuice.yezigotify.connection.ConnectionManager
import asia.guojuice.yezigotify.connection.ConnectionState
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.dnd.AppDndConfig
import asia.guojuice.yezigotify.ui.dnd.ChannelDndManager
import asia.guojuice.yezigotify.ui.dnd.DoNotDisturbManager
import asia.guojuice.yezigotify.ui.dnd.PerAppDndManager
import asia.guojuice.yezigotify.ui.widget.RecentMessagesWidget
import asia.guojuice.yezigotify.utils.AppInfo
import asia.guojuice.yezigotify.utils.MessageSender
import asia.guojuice.yezigotify.utils.MessageText
import asia.guojuice.yezigotify.utils.ServerDisplay
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GotifyService : Service() {

    private val tag = "GotifyService"

    /**
     * 多服务器连接管理器
     *
     * 一台服务器 = 一条 WebSocket（协议服务器走 /stream，ntfy 走订阅 WS）。
     * 见 connection/ 包。
     */
    private lateinit var manager: ConnectionManager

    private lateinit var client: OkHttpClient

    // Widget 刷新节流（trailing throttle，5 秒窗口）
    private var lastWidgetUpdateTime = 0L
    private val WIDGET_UPDATE_MIN_INTERVAL = 5_000L
    private val widgetHandler = Handler(Looper.getMainLooper())
    private var pendingWidgetUpdate: Runnable? = null

    // 通知分组缓存（key = "$serverId|$appid"）
    private data class CachedGroup(
        val messages: MutableList<GotifyMessage>,
        @Volatile var lastAccessTime: Long
    )
    private val notificationCache = mutableMapOf<String, CachedGroup>()
    private val maxCachedPerGroup = 5
    private val GROUP_CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    /**
     * 正在处理中的消息
     *
     * handleNewMessage 的去重是"先查库、再插入"，但每条消息都在独立的
     * serviceScope.launch 里跑 —— 同一条消息被投递两次时，两个协程会
     * 同时查到 null（都还没插入），于是双双通知一遍。
     * 用这个集合保证同一条消息只处理一次（add 成功者才继续）。
     *
     * key = "$serverId|$本地消息id"：本地 id 本身已含 slot，理论上全局唯一，
     * 带上 serverId 只是让日志/排查更直观，也防住"同 slot 多连接"的极端情况。
     */
    private val processingMessageIds =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val gson = Gson()

    @Volatile
    private var isNetworkAvailable = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 「网络恢复 → 重连」的延迟任务
     *
     * `onAvailable` 与 `onCapabilitiesChanged` 常常在几十毫秒内**接连**到达，
     * 不去重会同时发起两次重连。后到的回调取消前一个 —— 语义上也对：越晚的信息越新
     * （尤其 `VALIDATED` 比 `onAvailable` 更可信，它应该能"提前"那次等待）。
     */
    private var networkRestoreJob: Job? = null

    /** 丢网后的主动复查任务（见 [NETWORK_LOST_RECHECK_DELAYS_MS]） */
    private var networkRecheckJob: Job? = null

    companion object {
        const val BROADCAST_NEW_MESSAGE = "asia.guojuice.yezigotify.NEW_MESSAGE"
        const val ACTION_CLEAR_NOTIFICATIONS = "asia.guojuice.yezigotify.CLEAR_NOTIFICATIONS"
        const val BROADCAST_CONNECTION_STATE = "asia.guojuice.yezigotify.CONNECTION_STATE"
        const val EXTRA_STATE = "state"

        /**
         * 前台服务通知的 id
         *
         * ⚠️ 必须**始终**是 1：`startForeground(1, ...)` 绑的就是它。
         *    换成别的 id 会变成两条常驻通知，而且前台服务的绑定也会错乱。
         */
        const val SERVICE_NOTIFICATION_ID = 1

        /**
         * `onAvailable` 触发重连前的**兜底等待**
         *
         * `onAvailable` 只说明「有这个网络」，不代表路由/DNS 已经就绪
         * （WiFi 刚关联上时常常还不能真正发包，连了也会失败、白吃一次退避）。
         * 正常情况下几毫秒后会收到 `VALIDATED`，那次会把这个等待取消、立刻重连；
         * 所以这个值只在"VALIDATED 一直不来"时才真正生效。
         */
        const val NETWORK_RESTORE_FALLBACK_DELAY_MS = 1500L

        /**
         * 丢网之后「主动复查」的时间点（累计：2s / 5s / 12s / 27s）
         *
         * ⚠️ 为什么需要复查：`onLost` 之后系统**不保证**一定再派发 `onAvailable`
         *    （飞行模式来回切、WiFi 其实一直没断的场景实测会漏）。漏了就没人再把
         *    `isNetworkAvailable` 改回 true，它会永久停在 false —— 而这个值是
         *    **所有重连入口的总闸**，一假就只剩 OkHttp 那 30s 的 ping 能救。
         *    所以这里隔几秒直接问系统一次「现在到底有没有网」，有就走正常恢复。
         */
        val NETWORK_LOST_RECHECK_DELAYS_MS = longArrayOf(2000L, 3000L, 7000L, 15000L)

        /** 广播里携带来源服务器 id（多服务器后 UI 需要区分来源） */
        const val EXTRA_SERVER_ID = "server_id"

        const val ACTION_WIDGET_UPDATE = "asia.guojuice.yezigotify.WIDGET_UPDATE"

        /** 配置（服务卡）变化后重载连接：差分增删，不再 stopService+startService */
        const val ACTION_RELOAD_CONFIGS = "asia.guojuice.yezigotify.RELOAD_CONFIGS"

        /** Chatz 扩展事件广播（由 ViewModel 处理） */
        const val BROADCAST_CHATZ_EVENT = "asia.guojuice.yezigotify.CHATZ_EVENT"

        @Volatile
        var isServiceRunning = false
            private set

        /** 汇总连接状态（全部连上 → CONNECTED；混合 → PARTIAL；全断 → DISCONNECTED） */
        @Volatile
        var currentState: ConnectionState = ConnectionState.DISCONNECTED
            internal set

        /**
         * 每台服务器的连接状态（key = serverId）
         *
         * 抽屉里显示每台的连接状态点用。这里只读不建连接，
         * 由 ConnectionManager 的状态回调同步过来（不要在 UI 里 new ConnectionManager）。
         */
        @Volatile
        var serverStates: Map<String, ConnectionState> = emptyMap()
            internal set

        fun isRunning(): Boolean = isServiceRunning
    }

    private fun updateState(newState: ConnectionState) {
        if (currentState != newState) {
            currentState = newState
            val intent = Intent(BROADCAST_CONNECTION_STATE)
            intent.setPackage(packageName)
            intent.putExtra(EXTRA_STATE, newState.name)
            sendBroadcast(intent)
        }
        // ⚠️ 放在 `if` **外面**：updateState 只按「汇总态」去重，
        //    而通知还要看「有无网络 + 每台明细」，真正的去重在
        //    refreshServiceNotification 自己的签名里。
        refreshServiceNotification()
    }

    private fun buildHttpClient(): OkHttpClient {
        // ⚠️ pingInterval 必须有值（2026-10-03 修）。
        //
        // 原来这里是 `pingInterval(0)`，注释写「由每连接的心跳协程管理」——
        // 但那个"心跳"只是 `ws.send("")` 并看一眼返回值，**根本检测不出半死连接**：
        // 对端已经没了（飞行模式 / 网卡切换 / NAT 超时）时 `send()` 依然返回 true，
        // 只是把数据排进了发送缓冲。于是 App 会一直以为自己还连着，永远不重连。
        //
        // OkHttp 的 WS ping 是唯一能自己发现这事的机制：它每 30s 发一个 ping 帧，
        // 若上一个 ping 还没等到 pong 就要再发，就判定连接已死并回调 onFailure
        // （⇒ 走 onFailure → scheduleReconnect 那条正常链路）。
        // 服务端用的是 `ws` 库，对收到的 ping 会自动回 pong（见 src/ws.js），所以兼容。
        // 应用层那条空帧心跳保留不动，两者互不干扰。
        AppLog.d(tag, "WebSocket 自动心跳(ping)间隔 30s；应用层心跳仍由每连接的协程管理")
        return OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        client = buildHttpClient()
        isServiceRunning = true
        createNotificationChannels()

        // ⚠️ 启动时先问系统一次「现在到底有没有网」。
        //    isNetworkAvailable 的初值是 false，而真正的赋值要等 ConnectivityManager
        //    的回调 —— 中间这段时间前台通知会先闪一下「无网络」再变成「已连接」。
        //    这里只是把初值纠对，真正的判定仍然以回调为准。
        isNetworkAvailable = runCatching {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork != null
        }.getOrDefault(false)

        startForegroundSafely()

        manager = ConnectionManager(
            ConnectionDeps(
                appContext = applicationContext,
                client = client,
                scope = serviceScope,
                gson = gson,
                isNetworkAvailable = { isNetworkAvailable },
                onMessage = { server, msg -> handleNewMessage(server, msg) },
                onChatzEvent = { server, json -> handleChatzEvent(server, json) },
                onRemoteDeleted = { _, localId -> handleMessageDeleted(localId) },
                onTopicCleared = { server -> handleTopicCleared(server) },
                onStateChanged = { serverId, state -> manager.onConnectionStateChanged(serverId, state) }
            )
        )
        manager.onAggregateChanged = { aggregate ->
            // 同步每台服务器的状态（抽屉展示用），再上报汇总态
            serverStates = manager.states.value
            updateState(aggregate)
        }

        registerNetworkCallback()
        updateState(ConnectionState.RECONNECTING)
        reloadConfigs()
    }

    /** 按当前服务卡配置差分建立/更新连接 */
    private fun reloadConfigs() {
        serviceScope.launch {
            try {
                val configs = ServerConfigStore.enabled(this@GotifyService)
                // 顺手把显示名缓下来给前台通知的状态明细用 ——
                // 通知刷新比换配置频繁得多，不该每次都去同步读一遍存储
                serverLabels = configs.associate { it.id to ServerDisplay.labelOf(it) }
                configsLoaded = true
                manager.applyConfigs(configs)
                // 配置刚变过：通知里的「每台明细」要跟着更新
                refreshServiceNotification()
            } catch (e: Exception) {
                AppLog.e(tag, "重载连接配置失败: ${e.message}")
            }
        }
    }

    fun clearAllNotifications() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        synchronized(notificationCache) {
            for (groupKey in notificationCache.keys) {
                nm.cancel(groupKey.hashCode())
            }
            notificationCache.clear()
        }
        nm.cancelAll()
        AppLog.d(tag, "已清除所有状态栏通知")
    }

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        val importance = appProcessInfo.importance
        return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(tag, "onStartCommand 收到 intent action: ${intent?.action}")

        when (intent?.action) {
            ACTION_CLEAR_NOTIFICATIONS -> {
                clearAllNotifications()
                return START_STICKY
            }

            ACTION_RELOAD_CONFIGS -> {
                reloadConfigs()
                return START_STICKY
            }
        }

        // 无 action 的启动也要做一次差分重载：换服务器 / 换 token 后
        // MainActivity 只会 startService，不传任何 extra。
        // applyConfigs 对"没有变化"是 no-op，不会引起重连抖动。
        if (manager.connectionCount() == 0 && ServerConfigStore.hasAnyEnabled(this)) {
            updateState(ConnectionState.RECONNECTING)
        }
        reloadConfigs()
        return START_STICKY
    }

    /**
     * serverId → 显示名
     *
     * 只在 [reloadConfigs] 里刷新。刻意不做成「每次画通知都去 ServerConfigStore 读一遍」——
     * 那是同步读偏好 + 解析 JSON，而通知刷新（状态跳变）比换配置频繁得多。
     */
    @Volatile
    private var serverLabels: Map<String, String> = emptyMap()

    /**
     * 是否已经至少成功加载过一次配置
     *
     * 用来区分「还没加载完」和「真的一台都没启用」—— 两者都表现为 serverStates 为空，
     * 但文案不一样（「启动中…」vs「未启用任何服务器」）。
     */
    @Volatile
    private var configsLoaded = false

    /**
     * 上一次画到通知上的内容签名
     *
     * 通知更新有系统节流，重复 notify 同一条没有意义、甚至可能被系统丢弃/告警。
     * 这里按「配置是否加载 + 有无网络 + 每台服务器状态」做去重。
     *
     * ⚠️ 不能用 `currentState` 代替它：那是**汇总态**，增删一台服务器时汇总态可能压根不变
     *    （比如都是一路 CONNECTED），但明细必须重画。
     */
    @Volatile
    private var lastServiceNotificationSignature: String? = null

    private fun startForegroundSafely() {
        val notification = buildServiceNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    /**
     * 每台服务器的状态短语
     *
     * ⚠️ 「无网络」和「连接失败」必须分开：前者是本地没网（等系统恢复就行），
     *    后者是**有网却连不上服务器**（要去查服务端 / token / 域名 / 证书）。
     *    以前两者都显示成「未连接」，排查时根本分不清是哪一头的问题。
     */
    private fun perServerStateText(state: ConnectionState, netUp: Boolean): String = when {
        !netUp -> "无网络"
        state == ConnectionState.CONNECTED -> "已连接"
        state == ConnectionState.RECONNECTING -> "正在重连"
        else -> "连接失败"
    }

    /**
     * 前台服务通知
     *
     * ⚠️ 这条通知**不能去掉** —— Android 要求前台服务必须有常驻通知，
     *    `setOngoing(true)` 让它不可划掉。能做的是让内容反映真实状态。
     *
     * 基调刻意保持「安静」：渠道 `IMPORTANCE_MIN` + 静音 + 锁屏隐藏，
     * 所以它不弹、不响、锁屏看不见，只在下拉通知栏时才读得到 ——
     * 目标是「能查」，不是「被提醒」。
     */
    private fun buildServiceNotification(): Notification {
        val netUp = isNetworkAvailable
        val states = serverStates
        val total = states.size
        val connected = states.values.count { it == ConnectionState.CONNECTED }

        val summary = when {
            !configsLoaded -> "启动中…"
            total == 0 -> "未启用任何服务器"
            !netUp -> "无网络"
            connected == total -> "已连接 · 监听新消息中"
            connected == 0 && states.values.any { it == ConnectionState.RECONNECTING } -> "正在重连…"
            connected == 0 -> "连接失败，正在重试"
            else -> "部分服务器未连接"
        }

        val builder = NotificationCompat.Builder(this, "service")
            .setSmallIcon(R.drawable.transparent_icon)
            // 以前标题是空的、正文写死「监听新消息中」；现在标题放汇总态
            .setContentTitle(summary)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            // 更新时不提示第二次。现在虽然静音压着，但显式写上更稳 ——
            // 将来万一调高优先级，少了它每跳一次状态就会响一下
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)

        if (total == 0) {
            builder.setContentText(if (netUp) "" else "网络恢复后会自动重连")
            return builder.build()
        }

        val lines = states.entries
            .sortedBy { it.key }
            .map { (id, st) -> "${serverLabels[id] ?: id}：${perServerStateText(st, netUp)}" }

        // 折叠态：单台就直接显示那台；多台显示「N/M 台已连接」
        builder.setContentText(if (total == 1) lines.first() else "$connected/$total 台已连接")

        // 展开态：逐台列出。用 InboxStyle 而不是 BigTextStyle —— 每台一行更清楚，
        // 也不会像 BigText 那样被长域名撑成一坨。
        val style = NotificationCompat.InboxStyle().setBigContentTitle(summary)
        lines.forEach { style.addLine(it) }
        builder.setStyle(style)

        return builder.build()
    }

    /**
     * 按需重画前台通知
     *
     * 由 [updateState] 触发（连接状态一变就会走到），
     * 靠 [lastServiceNotificationSignature] 去重 ⇒ 重复调用是廉价的 no-op。
     */
    private fun refreshServiceNotification() {
        val states = serverStates
        // 签名里四样都要带上：
        //   configsLoaded —— 「启动中…」→「未启用任何服务器」要能切换
        //   isNetworkAvailable —— 少了它，切飞行模式时文案不会变
        //                        （汇总态可能两次都是 DISCONNECTED）
        //   每台状态 —— 少了它，增删一台服务器而汇总态不变时明细不会更新
        //   每台显示名 —— 少了它，**只改了服务器名字**时明细还是旧名字
        //                 （状态没跳变，什么都不会触发重画）
        val signature = buildString {
            append(configsLoaded).append('|').append(isNetworkAvailable).append('|')
            states.entries.sortedBy { it.key }.forEach {
                append(it.key)
                    .append('=').append(it.value)
                    .append('@').append(serverLabels[it.key].orEmpty())
                    .append(',')
            }
        }
        if (signature == lastServiceNotificationSignature) return
        lastServiceNotificationSignature = signature
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        } catch (e: Exception) {
            AppLog.w(tag, "刷新前台通知失败: ${e.message}")
        }
    }

    /**
     * 收到一条新消息（id / serverId / remoteId 已由连接层命名空间化）
     */
    private fun handleNewMessage(server: ServerConfig, msg: GotifyMessage) {
        if (msg.message.isNullOrBlank() && msg.appid == 0) {
            return
        }

        cleanupStaleNotificationGroups()

        val rawJson = gson.toJson(msg)
        val processKey = "${server.id}|${msg.id}"

        serviceScope.launch {
            // 同一消息并发到达时（服务端 multi-channel 广播 / 重连补投），
            // 只让第一个协程继续，后面的直接丢弃，避免重复通知
            if (!processingMessageIds.add(processKey)) {
                AppLog.d(tag, "⏭️ 同一消息正在处理中，跳过重复: $processKey")
                return@launch
            }
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val existing = db.messageDao().getMessageById(msg.id)
                if (existing != null) return@launch

                val entity = msg.toEntity(server, receivedAt = System.currentTimeMillis())
                db.messageDao().insert(entity)
                updateWidgetIfNeeded()

                val broadcast = Intent(BROADCAST_NEW_MESSAGE)
                broadcast.setPackage(packageName)
                broadcast.putExtra("message", rawJson)
                broadcast.putExtra(EXTRA_SERVER_ID, server.id)
                sendBroadcast(broadcast)

                // 本机刚发出去的那条（通知栏快捷回复 / App 内发送）：
                // 上面该做的入库和广播都做完了，只是不再给自己弹通知
                val selfSent = SelfSentMessages.consume(server.id, msg.channelId, msg.message)

                if (selfSent) {
                    AppLog.d(tag, "🔇 本机刚发出的消息，跳过通知")
                } else if (!isAppInForeground()) {
                    if (suppressedByChannelMute(db, msg)) {
                        AppLog.d(
                            tag,
                            "🔇 频道已静音且优先级未豁免，跳过通知: " +
                                    "channel=${msg.channelId}, priority=${msg.priority}"
                        )
                    } else {
                        showNotification(server, msg)
                    }
                } else {
                    AppLog.d(tag, "应用在前台，跳过状态栏通知")
                }
            } catch (e: Exception) {
                AppLog.e(tag, "处理消息失败: ${e.message}")
            } finally {
                // 无论成功失败都要释放，否则这个 id 以后再也进不来
                processingMessageIds.remove(processKey)
            }
        }
    }

    private fun cleanupStaleNotificationGroups() {
        val now = System.currentTimeMillis()
        synchronized(notificationCache) {
            val it = notificationCache.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.lastAccessTime > GROUP_CACHE_TTL_MS) {
                    try {
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(entry.key.hashCode())
                    } catch (_: Exception) {
                    }
                    it.remove()
                    AppLog.d(tag, "清理超时通知分组: ${entry.key}")
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            // ⚠️ 这里原来是空的（2026-10-03 修）。
            //    恢复逻辑只挂在 onCapabilitiesChanged 上，但切网时**不保证**那次回调会来
            //    （顺序/时机看系统），一旦没来，isNetworkAvailable 会一直停在 false ——
            //    连心跳里那个看门狗都会被这个开关挡住，于是永远不重连。
            //    onAvailable 只保证「有 INTERNET 能力」（不保证 VALIDATED，比如强制门户），
            //    但对「能不能连上自己的服务器」来说已经够用了，所以两边都要能触发恢复。
            //
            // ⚠️ 但它**不保证路由/DNS 已就绪**（WiFi 刚关联上时经常还不能真正发包），
            //    所以这里仍然保留原来那 1.5s 的等待 —— 它是**兜底**：
            //    正常情况下几毫秒后就会收到 VALIDATED，那次会把这个等待取消掉。
            override fun onAvailable(network: Network) {
                onNetworkMaybeUp(delayMs = NETWORK_RESTORE_FALLBACK_DELAY_MS)
            }

            override fun onLost(network: Network) {
                isNetworkAvailable = false
                manager.onNetworkLost()
                // 回调不一定补得上（系统不保证再派发 onAvailable），排一次主动复查兜底
                scheduleNetworkRecheck()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

                if (!hasInternet) {
                    // 连 INTERNET 能力都没了 ⇒ 这个网络确实上不了网
                    isNetworkAvailable = false
                    return
                }

                // ⚠️ 只要还有 INTERNET 能力就别判成「无网络」（2026-10-06 修）。
                //
                // 原写法要求 VALIDATED（系统的强制门户探测通过）才算有网，探测没过就置 false。
                // 但 VALIDATED 在国内 / 企业内网 / 纯内网自托管场景**经常一直拿不到**
                // —— 探测要访问 Google 的连通性检查地址，访问不到就永远不置位；
                // 而开关飞行模式会让系统**重新走一遍探测**，这段时间里它必定是 false。
                //
                // 麻烦的是 isNetworkAvailable 是所有重连入口的**总闸**：指数退避、
                // 心跳看门狗、WS 失败后要不要重排，全都先问它。它一假，就只剩
                // OkHttp 那 30s 的 ping 能救 —— 正是「显示无网络 + 二三十秒才连上」。
                //
                // 能不能真连上服务器，交给连接层真连一次：连不上会走指数退避，不会失联。
                // VALIDATED 仍然有用 —— 拿到了说明路由已就绪，不用等兜底那 1.5s。
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                onNetworkMaybeUp(delayMs = if (isValidated) 0 else NETWORK_RESTORE_FALLBACK_DELAY_MS)
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    /**
     * 网络（可能）恢复：按需触发一次重连
     *
     * @param delayMs 等多久再重连。`onAvailable` 传兜底值（路由可能还没好）；
     *                `onCapabilitiesChanged(VALIDATED)` 传 0（系统已验证能上网，立刻连）
     *
     * ── 什么情况下才会真的触发 ────────────────────────────
     * 只有两种：
     *   ① 「从无网变有网」的那次跳变（`isNetworkAvailable` false → true）
     *      —— 这才是"网络恢复了"的正牌信号
     *   ② 已经排了一个**兜底等待**、而这次来的是更可信的 VALIDATED（`delayMs == 0`）
     *      —— 那就把兜底等待取消、立刻重连
     *
     * ⚠️ 为什么卡这么死：`onCapabilitiesChanged` 在**信号强弱变化 / 带宽更新**时会
     *    频繁回调。而服务端挂掉时 `state` 一直不是 CONNECTED —— 若无脑每次回调都触发
     *    `onNetworkRestored()`，等于每个回调都把指数退避（5s→10s→…→5min）取消掉重来，
     *    变成对挂掉的服务端狂轰。这两条限定把触发次数压到了"每次网络变化一次"。
     *
     * ⚠️ 触发之后还有一步：断过网（①）时先 `manager.onNetworkLost()` 再 `onNetworkRestored()`。
     *    因为 `onLost` 万一没派发，每台连接的 `state` 会停在脏的 CONNECTED，
     *    而 ServerConnection 里有「已连接就跳过重建」的守卫 ⇒ 直接恢复会被那道守卫挡掉。
     *    先作废一次才能真重建 —— 飞行模式来回切留下的半死连接就是这么卡住的。
     */
    private fun onNetworkMaybeUp(delayMs: Long) {
        val wasAvailable = isNetworkAvailable
        // 「有没有网」这个值仍然维护着：通知文案要用它区分「无网络」和「连接失败」
        isNetworkAvailable = true
        networkRecheckJob?.cancel()

        // ② 把已有的兜底等待"提前"：只有当次是 VALIDATED 且确有任务在等，才允许
        val upgradePendingFallback = delayMs == 0L && networkRestoreJob?.isActive == true
        // ① 之外的重复回调一律忽略
        if (wasAvailable && !upgradePendingFallback) return

        // 网络**一直都在**且已经连着 ⇒ 别白拆一次刚建好的连接
        if (wasAvailable && currentState == ConnectionState.CONNECTED) {
            networkRestoreJob?.cancel()
            return
        }

        networkRestoreJob?.cancel()
        networkRestoreJob = serviceScope.launch(Dispatchers.Main) {
            if (delayMs > 0) delay(delayMs)

            // ⚠️ 断过网（wasAvailable == false）就**必须先作废旧连接**。
            //
            // 半死连接上 `state` 会停在 CONNECTED —— 对端（飞行模式 / NAT）已经没了，
            // 本地 socket 还挂着，WS 收不到任何回调，状态就一直是「已连接」。
            // 而 ServerConnection.onNetworkRestored 里有「state == CONNECTED 就跳过」的守卫
            // （那是为了避免系统乱发回调时白拆连接），不清一下的话重连会被它整个挡掉，
            // 只能等 OkHttp 30s 的 ping 才发现连接早就死了 —— 飞行模式来回切就是这么卡的。
            //
            // onNetworkLost() 干两件事：abandonConnection() 丢掉半死 socket +
            // 把每台置成 DISCONNECTED，于是后面的 onNetworkRestored() 走的是正常重建路径。
            if (!wasAvailable) manager.onNetworkLost()

            AppLog.d(tag, "网络恢复（等待 ${delayMs}ms 后）触发重连，state=$currentState")
            manager.onNetworkRestored()
        }
    }

    /**
     * 丢网后主动复查：隔几秒直接问系统一次「现在有没有网」
     *
     * 存在原因是 `onLost` 之后系统**不保证**补发 `onAvailable` —— 飞行模式来回切、
     * WiFi 实际没断的场景实测会漏。漏了就没人再把 `isNetworkAvailable` 改回 true，
     * 而它是所有重连入口的总闸（退避 / 心跳看门狗 / WS 失败后是否重排，都先问它）。
     * 不复查的话，唯一出路就是等 OkHttp 那 30s 的 ping。
     */
    private fun scheduleNetworkRecheck() {
        networkRecheckJob?.cancel()
        networkRecheckJob = serviceScope.launch(Dispatchers.IO) {
            for (stepMs in NETWORK_LOST_RECHECK_DELAYS_MS) {
                delay(stepMs)
                // 期间回调来了 ⇒ 交给它处理，别重复触发
                if (isNetworkAvailable) return@launch
                if (isNetworkUpPerSystem()) {
                    AppLog.d(tag, "丢网后复查：系统报告网络已恢复（回调没来），主动触发重连")
                    onNetworkMaybeUp(delayMs = 0)
                    return@launch
                }
            }
            AppLog.d(tag, "丢网后复查结束：系统仍报告无网络，等回调或下次状态变化")
        }
    }

    /** 直接问系统「现在有没有可用的上网通道」——不依赖回调，只看 INTERNET 能力 */
    private fun isNetworkUpPerSystem(): Boolean {
        val cm = connectivityManager
            ?: runCatching { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull()
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 触发 Widget 刷新（trailing throttle，5 秒窗口）
     */
    private fun updateWidgetIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastWidgetUpdateTime

        if (elapsed >= WIDGET_UPDATE_MIN_INTERVAL) {
            lastWidgetUpdateTime = now
            pendingWidgetUpdate?.let {
                widgetHandler.removeCallbacks(it)
                pendingWidgetUpdate = null
            }
            RecentMessagesWidget.notifyWidgetDataChanged(applicationContext)
        } else {
            if (pendingWidgetUpdate == null) {
                val delayMs = WIDGET_UPDATE_MIN_INTERVAL - elapsed
                val task = Runnable {
                    lastWidgetUpdateTime = System.currentTimeMillis()
                    pendingWidgetUpdate = null
                    RecentMessagesWidget.notifyWidgetDataChanged(applicationContext)
                }
                pendingWidgetUpdate = task
                widgetHandler.postDelayed(task, delayMs)
                AppLog.d(tag, "⏳ Widget 刷新窗口内，延迟 ${delayMs}ms 补刷新")
            } else {
                AppLog.d(tag, "🚫 Widget 刷新窗口内，已存在挂起任务，合并")
            }
        }
    }

    // ===================================================

    // 文本清洗 / 图片取址都统一走 MessageText（原先本文件各有一份实现，见那个类的说明）
    private fun cleanMessageText(text: String): String = MessageText.stripImageMarkdown(text)

    // ===== 通知展示（已支持每 App 独立配置 + App 图标） =====

    /**
     * 是否因为「频道静音」而跳过这条通知
     *
     * 规则：
     *   - 频道没静音 → false（照常通知）
     *   - 频道已静音，且优先级落在「全局免打扰的豁免区间」内 → false（静音但重要的照常弹）
     *   - 频道已静音，优先级没被豁免 → true（完全不弹）
     *
     * 豁免区间直接复用勿扰设置里那个（dnd_exempt_min ~ dnd_exempt_max，默认 8~10）：
     *   - 不引入第二套阈值，用户只需要在一个地方配"什么算重要"
     *   - 只读数值，和勿扰开关是否开启无关，所以没开勿扰时静音豁免照样生效
     *
     * 和系统免打扰的区别：那两者（DoNotDisturbManager / PerAppDndManager）是
     * "弹但不出声"，这里是"根本不弹"，两者互不干扰。
     *
     * 查不到频道就按未静音处理：本地还没有该频道记录时（首次同步未跑完），
     * 宁可多弹一次，也不要因为查不到就把通知静默吞掉。
     */
    private suspend fun suppressedByChannelMute(db: AppDatabase, msg: GotifyMessage): Boolean {
        val cid = msg.channelId ?: return false

        val muted = try {
            db.channelDao().getById(cid)?.muted == true
        } catch (e: Exception) {
            AppLog.d(tag, "查询频道静音状态失败: ${e.message}")
            false
        }
        if (!muted) return false

        val exemptMin = DoNotDisturbManager.getExemptMin()
        val exemptMax = DoNotDisturbManager.getExemptMax()
        return msg.priority !in exemptMin..exemptMax
    }

    private fun showNotification(server: ServerConfig, msg: GotifyMessage) {
        val prefs = getSharedPreferences("gotify", MODE_PRIVATE)

        // 配置只解析一次，往下传：避免 showNormal / showMerged 里各自重查
        val (hasConfig, config) = resolveDndConfig(msg)

        val globalMin = DoNotDisturbManager.getExemptMin()
        val globalMax = DoNotDisturbManager.getExemptMax()

        val exempt = hasConfig &&
                config.isPriorityExempt(msg.priority, globalMin, globalMax)

        val localSilent = if (exempt) {
            false
        } else {
            val appSilent = hasConfig && config.shouldBeSilent()
            val globalSilent = DoNotDisturbManager.getNotificationBehavior(msg.priority) ==
                    DoNotDisturbManager.Behavior.SILENT
            appSilent || globalSilent
        }

        // ★ 服务端显式要求静默（路由规则的 set_silent，例如内置的 night-silent 模板）
        //   优先于本地判定 —— 那是服务端对这条消息的明确指令，本地免打扰只是兜底启发式。
        //   旧服务端不下发 silent，默认 false ⇒ 等价于以前的行为。
        val silent = msg.silent || localSilent

        val globalMerge = prefs.getBoolean("notification_merge", false)
        val mergeEnabled = config.merge ?: globalMerge

        when {
            mergeEnabled -> showMergedNotification(server, msg, silent = silent, config = config)
            else -> showNormalNotification(msg, silent = silent, config = config)
        }
    }

    /**
     * 取这条消息生效的通知配置
     *   有频道（Chatz）且该频道配过 → 用频道级配置
     *   否则 → 回退应用级配置（Gotify / ntfy 只有这一条路）
     * @return hasCustomConfig 与配置本体
     */
    private fun resolveDndConfig(msg: GotifyMessage): Pair<Boolean, AppDndConfig> {
        val serverId = msg.sourceServerId
        val channelId = msg.channelId
        if (channelId != null && ChannelDndManager.hasCustomConfig(serverId, channelId)) {
            return true to ChannelDndManager.getConfig(serverId, channelId)
        }
        val hasAppConfig = PerAppDndManager.hasCustomConfig(serverId, msg.appid)
        return hasAppConfig to PerAppDndManager.getConfig(serverId, msg.appid)
    }

    /**
     * 免打扰 / 自定义铃声通道的「作用域标记」
     *
     *   有频道 → "c$channelId"；没有频道 → "a$appid"
     *
     * 自定义铃声的通知通道 id 里带上它，保证「某频道的铃声」和「某应用的铃声」
     * 不会因为共用前缀而互删通道。
     */
    private fun dndScopeTag(msg: GotifyMessage): String =
        msg.channelId?.let { "c$it" } ?: "a${msg.appid}"

    private suspend fun loadAppIconBitmap(msg: GotifyMessage): Bitmap? {
        // 优先用消息自带的应用快照（extras.app）：应用按用户隔离后，
        // `GET /application` 只返回自己的应用，本地缓存里查不到「别人应用」的图标，
        // 订阅别人频道时通知栏就会没图标。
        val snapshotUrl = AppIconCache.resolveUrl(
            msg.sourceServerId,
            AppInfo.from(msg.extras)?.image
        )
        // 应用图标按 (serverId, appid) 分桶查找，避免两台服务器的同名 appid 串图标
        val iconUrl = snapshotUrl
            ?: AppIconCache.getIconUrl(msg.sourceServerId, msg.appid)
            ?: (msg.extras?.get("icon") as? String)
            ?: return null

        return try {
            withContext(Dispatchers.IO) {
                Glide.with(this@GotifyService)
                    .asBitmap()
                    .load(iconUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit()
                    .get()
            }
        } catch (e: Exception) {
            AppLog.d(tag, "加载 App 图标失败: ${e.message}")
            null
        }
    }

    /**
     * 通知上的「回复」动作
     *
     * 只给 Chatz 的**频道消息**加：
     *   - Gotify 的发消息需要**应用 token**，而客户端为了读消息存的多半是客户端 token，
     *     两者不通用，加了也发不出去
     *   - 没有频道 id 就没有明确的发送目标
     *
     * ⚠️ RemoteInput 要求 PendingIntent **可变**（系统要往里塞用户输入的内容），
     *    API 31+ 必须显式带 FLAG_MUTABLE，否则回复框填完发不出去。
     */
    private fun replyAction(msg: GotifyMessage): NotificationCompat.Action? {
        if (!ServerCapabilitiesHolder.isChatz(msg.sourceServerId)) return null
        val localChannelId = msg.channelId ?: return null

        val intent = Intent(this, ReplyReceiver::class.java).apply {
            putExtra(ReplyReceiver.EXTRA_SERVER_ID, msg.sourceServerId)
            // 通知链路里是**本地** id，出站要换回远程 id
            putExtra(ReplyReceiver.EXTRA_CHANNEL_ID, LocalIds.remoteChannelIdOf(localChannelId))
            putExtra(ReplyReceiver.EXTRA_APP_ID, msg.appid)
            // 引用关系：服务端没有 reply_to 字段，靠 extras 透传，所以把"回复的是哪条 +
            // 它的摘要"一起交给 receiver（摘要随消息走，换设备/原消息被裁剪也还看得见）
            putExtra(ReplyReceiver.EXTRA_REPLY_TO_ID, LocalIds.remoteIdOf(msg.id))
            putExtra(ReplyReceiver.EXTRA_REPLY_TO_TEXT, MessageText.preview(msg.message, 120))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                })
        val pi = PendingIntent.getBroadcast(this, notificationIdFor(msg.id), intent, flags)

        return NotificationCompat.Action.Builder(0, "回复", pi)
            .addRemoteInput(
                RemoteInput.Builder(ReplyReceiver.KEY_REPLY).setLabel("回复消息").build()
            )
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun showNormalNotification(
        msg: GotifyMessage,
        silent: Boolean,
        config: AppDndConfig
    ) {
        val cleanText = cleanMessageText(msg.message)
        val imageUrl = MessageText.imageUrl(msg.message, msg.extras)
        val priority = msg.priority

        val customSoundUri = config.customSoundUri

        val channelId = if (!silent && !customSoundUri.isNullOrBlank()) {
            ensureCustomSoundChannel(msg.sourceServerId, dndScopeTag(msg), customSoundUri)
        } else {
            when {
                priority < 1 -> "min"
                priority <= 3 -> "low"
                priority <= 7 -> if (silent) "silent_normal" else "normal"
                else -> if (silent) "silent_high" else "high"
            }
        }

        // 发送者：有标题时挂成 subText，没标题时直接当标题用 —— 否则"谁发的"就被
        // 一个无意义的 "Gotify" 挤掉了（webhook 之外的消息都有 sender 快照）
        val senderLabel = MessageSender.from(msg.extras)?.label
        val hasTitle = !msg.title.isNullOrBlank()

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (hasTitle) msg.title else (senderLabel ?: "Gotify"))
            .setContentText(cleanText.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanText))
            .setAutoCancel(true)
            .setContentIntent(createNotificationPendingIntent(msg.id))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        // 标题已经是标题了，把发送者放在副标题里（标题就是发送者时不重复放）
        if (hasTitle && senderLabel != null) builder.setSubText(senderLabel)

        // 直接在通知里回复（只对 Chatz 频道消息有效）
        replyAction(msg)?.let { builder.addAction(it) }

        if (silent) {
            builder.setVibrate(longArrayOf(0)).setSound(null).setDefaults(0)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !customSoundUri.isNullOrBlank()) {
                builder.setSound(Uri.parse(customSoundUri))
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (priority in 4..10) {
                    builder.setVibrate(longArrayOf(0, 300, 200, 300))
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                } else {
                    builder.setVibrate(longArrayOf(0))
                        .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
                }
            }
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        serviceScope.launch {
            val appIcon = loadAppIconBitmap(msg)
            if (appIcon != null) {
                builder.setLargeIcon(appIcon)
            }

            if (imageUrl != null) {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        Glide.with(this@GotifyService)
                            .asBitmap().load(imageUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit().get()
                    }
                    val big = NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap).setSummaryText(cleanText.take(100))
                    builder.setStyle(big).setLargeIcon(bitmap)
                } catch (e: Exception) {
                    AppLog.d(tag, "加载消息图片失败: ${e.message}")
                }
            }

            nm.notify(notificationIdFor(msg.id), builder.build())
        }
    }

    private fun showMergedNotification(
        server: ServerConfig,
        msg: GotifyMessage,
        silent: Boolean,
        config: AppDndConfig
    ) {
        val cleanText = cleanMessageText(msg.message)
        val imageUrl = MessageText.imageUrl(msg.message, msg.extras)

        // 分组粒度 = "一台服务器的一个 app"：多服务器后必须带 serverId，
        // 否则两台服务器上 appid 相同的消息会互相覆盖合并通知
        val groupKey = "${server.id}|${msg.appid}"
        val displayTitle = msg.title ?: defaultServerTitle(server)

        synchronized(notificationCache) {
            val group = notificationCache.getOrPut(groupKey) {
                CachedGroup(mutableListOf(), System.currentTimeMillis())
            }
            group.lastAccessTime = System.currentTimeMillis()
            group.messages.add(0, msg)
            if (group.messages.size > maxCachedPerGroup) {
                group.messages.subList(maxCachedPerGroup, group.messages.size).clear()
            }
        }

        val priority = msg.priority

        val customSoundUri = config.customSoundUri

        val channelId = if (!silent && !customSoundUri.isNullOrBlank()) {
            ensureCustomSoundChannel(msg.sourceServerId, dndScopeTag(msg), customSoundUri)
        } else {
            when {
                priority < 1 -> "min"
                priority <= 3 -> "low"
                priority <= 7 -> if (silent) "silent_normal" else "normal"
                else -> if (silent) "silent_high" else "high"
            }
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = groupKey.hashCode()

        val (currentList, inboxStyle) = synchronized(notificationCache) {
            val group = notificationCache[groupKey]
            val list = group?.messages ?: mutableListOf(msg)
            val style = NotificationCompat.InboxStyle()
            for ((index, cachedMsg) in list.withIndex()) {
                style.addLine("${index + 1}. ${MessageText.preview(cachedMsg.message, 100)}")
            }
            if (list.size > 1) {
                style.setSummaryText("${list.size} 条来自 $displayTitle")
            }
            list.toList() to style
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(displayTitle)
            .setContentText(
                if (currentList.size > 1) "收到 ${currentList.size} 条新消息"
                else cleanText.take(200)
            )
            .setStyle(inboxStyle)
            .setAutoCancel(true)
            .setContentIntent(createNotificationPendingIntent(msg.id))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        // 合并通知的标题是应用/频道名，发送者放副标题（取最新那条的发送者）
        MessageSender.from(msg.extras)?.label?.let { builder.setSubText(it) }

        // 合并通知里回复的是**最新那条**消息所在的频道
        replyAction(msg)?.let { builder.addAction(it) }

        if (silent) {
            builder.setVibrate(longArrayOf(0)).setSound(null).setDefaults(0)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !customSoundUri.isNullOrBlank()) {
                builder.setSound(Uri.parse(customSoundUri))
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (priority in 4..10) {
                    builder.setVibrate(longArrayOf(0, 300, 200, 300))
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                } else {
                    builder.setVibrate(longArrayOf(0))
                        .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
                }
            }
        }

        serviceScope.launch {
            val appIcon = loadAppIconBitmap(msg)
            if (appIcon != null) {
                builder.setLargeIcon(appIcon)
            }

            if (imageUrl != null && currentList.size <= 1) {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        Glide.with(this@GotifyService)
                            .asBitmap().load(imageUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit().get()
                    }
                    builder.setLargeIcon(bitmap)
                    val big = NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap).setSummaryText(cleanText.take(100))
                    builder.setStyle(big)
                } catch (e: Exception) {
                    AppLog.d(tag, "加载消息图片失败: ${e.message}")
                }
            }

            nm.notify(notificationId, builder.build())
        }
    }

    /** 该服务器在通知里的默认显示名（没有自定义名称时按服务类型） */
    private fun defaultServerTitle(server: ServerConfig): String =
        if (server.isNtfy) "Ntfy" else "Gotify"

    /**
     * 自定义铃声通道
     *
     * 通道 id 必须带 serverId + scopeTag：
     *   - serverId：两台服务器上同名 appId 的自定义铃声通道否则会互删（S3-12）
     *   - scopeTag：区分「频道级」与「应用级」作用域（见 [dndScopeTag]），
     *     保证某频道的铃声不会把某应用的铃声通道删掉。
     * 清理同前缀旧通道时同样按 serverId + scopeTag 限定。
     */
    private fun ensureCustomSoundChannel(serverId: String, scopeTag: String, soundUri: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "normal"
        }

        val nm = getSystemService(NotificationManager::class.java)
        val prefix = "app_voice_${serverId}_${scopeTag}_"
        val channelId = "$prefix${soundUri.hashCode()}"

        if (nm.getNotificationChannel(channelId) == null) {
            nm.notificationChannels
                .filter { it.id.startsWith(prefix) && it.id != channelId }
                .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }

            val channel = NotificationChannel(
                channelId,
                "自定义铃声",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setSound(
                    Uri.parse(soundUri),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            nm.createNotificationChannel(channel)
        }
        return channelId
    }

    /**
     * 通知 id 计算：把 Long 主键安全映射到 Int
     *
     * 算法：把 Long 的高 32 位和低 32 位做异或。
     *   - 低 32 位：直接保留 id 的低位特征
     *   - 高 32 位：掺入高位信息，避免 0x100000001 和 1 撞车
     *
     * 多服务器后本地 id 已含 slot（高 8 位），所以这个函数不需要再改。
     */
    private fun notificationIdFor(messageId: Long): Int {
        return (messageId xor (messageId ushr 32)).toInt()
    }

    /**
     * PendingIntent 的 requestCode 同样需要防碰撞
     *
     * 如果两条消息的 id 高 32 位不同但低 32 位相同，直接用 toInt() 会让
     * 两个 PendingIntent 的 requestCode 相等。加上 FLAG_UPDATE_CURRENT，
     * 后创建的消息的 extra（scroll_to_message_id）会覆盖前一个，导致：
     *   - 点通知 A 跳到了消息 B
     */
    private fun createNotificationPendingIntent(messageId: Long): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("scroll_to_message_id", messageId)
        }
        val stackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntent(intent)
        return stackBuilder.getPendingIntent(
            notificationIdFor(messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )!!
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("service") == null) {
                nm.createNotificationChannel(
                    NotificationChannel("service", "Gotify 服务", NotificationManager.IMPORTANCE_MIN))
            }
            createMsgChannel(nm, "min", "最低优先级 (<1)", NotificationManager.IMPORTANCE_MIN, false)
            createMsgChannel(nm, "low", "低优先级 (1-3)", NotificationManager.IMPORTANCE_LOW, false)
            createMsgChannel(nm, "normal", "普通优先级 (4-7)", NotificationManager.IMPORTANCE_HIGH, true)
            createMsgChannel(nm, "high", "高优先级 (8-10)", NotificationManager.IMPORTANCE_HIGH, true)
            createSilentChannel(nm, "silent_normal", "普通优先级-静默", NotificationManager.IMPORTANCE_LOW)
            createSilentChannel(nm, "silent_high", "高优先级-静默", NotificationManager.IMPORTANCE_LOW)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createSilentChannel(nm: NotificationManager, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            enableVibration(false)
            vibrationPattern = null
            setSound(null, null)
            enableLights(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun createMsgChannel(
        nm: NotificationManager, id: String, name: String, importance: Int, vibrate: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            enableLights(true)
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = longArrayOf(0, 250, 250, 250)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isServiceRunning = false
        if (::manager.isInitialized) manager.stopAll()
        serviceJob.cancel()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        updateState(ConnectionState.DISCONNECTED)
        pendingWidgetUpdate?.let {
            widgetHandler.removeCallbacks(it)
            pendingWidgetUpdate = null
        }
        Glide.get(this).clearMemory()
        super.onDestroy()
    }

    /**
     * 广播 Chatz 事件给 UI 层处理
     *
     * 设计：
     *   - 事件类型很多，全部在 GotifyService 里处理会让这个类爆炸
     *   - 广播原始 JSON + 来源 serverId 给 ViewModel，由 ViewModel 按该服务器的 slot
     *     把 id 转成本地 id 后分发到 DAO
     */
    private fun handleChatzEvent(server: ServerConfig, rawJson: String) {
        try {
            val intent = Intent(BROADCAST_CHATZ_EVENT)
            intent.setPackage(packageName)
            intent.putExtra("event_json", rawJson)
            intent.putExtra(EXTRA_SERVER_ID, server.id)
            sendBroadcast(intent)
            AppLog.d(tag, "已广播 Chatz 事件（server=${server.id}）")
        } catch (e: Exception) {
            AppLog.e(tag, "广播 Chatz 事件失败: ${e.message}")
        }
    }

    private fun handleMessageDeleted(messageId: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        synchronized(notificationCache) {
            val iterator = notificationCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val groupKey = entry.key
                val group = entry.value

                val removed = group.messages.removeIf { it.id == messageId }
                if (!removed) continue

                group.lastAccessTime = System.currentTimeMillis()

                if (group.messages.isEmpty()) {
                    iterator.remove()
                    nm.cancel(groupKey.hashCode())
                } else {
                    val inboxStyle = NotificationCompat.InboxStyle()
                    for ((index, cachedMsg) in group.messages.withIndex()) {
                        inboxStyle.addLine(
                            "${index + 1}. ${MessageText.preview(cachedMsg.message, 100)}"
                        )
                    }

                    val latestMsg = group.messages.first()
                    val displayTitle = latestMsg.title
                        ?: if (latestMsg.appid == 0) "Ntfy" else "Gotify"
                    inboxStyle.setSummaryText("${group.messages.size} 条来自 $displayTitle")

                    val priority = latestMsg.priority
                    val channelId = when {
                        priority < 1 -> "min"
                        priority <= 3 -> "low"
                        priority <= 7 -> "normal"
                        else -> "high"
                    }

                    val builder = NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(displayTitle)
                        .setContentText("收到 ${group.messages.size} 条新消息")
                        .setStyle(inboxStyle)
                        .setAutoCancel(true)
                        .setContentIntent(createNotificationPendingIntent(latestMsg.id))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setSilent(true)

                    // 删掉一条后重建合并通知，发送者同样取最新那条
                    MessageSender.from(latestMsg.extras)?.label?.let { builder.setSubText(it) }

                    nm.notify(groupKey.hashCode(), builder.build())
                }
                break
            }
        }

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.messageDao().deleteById(messageId)
                updateWidgetIfNeeded()
            } catch (e: Exception) {
                AppLog.e(tag, "删除消息失败: ${e.message}")
            }
        }
    }

    /**
     * ntfy `message_clear`：整个 topic 被清空
     *
     * 与 [handleMessageDeleted] 的区别是它**没有 id** —— 语义是「这一路来源的消息
     * 全部作废」。所以：
     *   1. 清掉该服务器的全部状态栏通知缓存（不分单条）
     *   2. 删掉本地 DB 里该服务器的全部 ntfy 消息
     *
     * 两个清理都必须**按 serverId 限定**：清空是单台服务器的事件，
     * 不限定会把别的服务器（甚至别的协议）的消息一起删掉。
     */
    private fun handleTopicCleared(server: asia.guojuice.yezigotify.config.ServerConfig) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        synchronized(notificationCache) {
            val iterator = notificationCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val group = entry.value
                // 只摘掉归属这台服务器的、且是 ntfy 的消息（appid == 0 是既有约定）
                val removed = group.messages.removeIf {
                    it.serverId == server.id && it.appid == 0
                }
                if (!removed) continue
                if (group.messages.isEmpty()) {
                    iterator.remove()
                    nm.cancel(entry.key.hashCode())
                }
            }
        }

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val n = db.messageDao().deleteNtfyMessagesByServer(server.id)
                AppLog.d(tag, "[${server.id}] ntfy message_clear：本地清理 $n 条")
                updateWidgetIfNeeded()
            } catch (e: Exception) {
                AppLog.e(tag, "[${server.id}] ntfy 清空本地消息失败: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}