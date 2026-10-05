package asia.guojuice.yezigotify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.connection.ConnectionState
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.dnd.DoNotDisturbManager
import asia.guojuice.yezigotify.ui.drawer.DrawerApp
import asia.guojuice.yezigotify.ui.drawer.DrawerServer
import asia.guojuice.yezigotify.ui.main.MainScreen
import asia.guojuice.yezigotify.ui.preview.ImagePreviewDialogContent
import asia.guojuice.yezigotify.ui.theme.YezigotifyTheme
import asia.guojuice.yezigotify.utils.AppThemeState
import asia.guojuice.yezigotify.utils.BubbleHelper
import asia.guojuice.yezigotify.utils.ServerDisplay
import asia.guojuice.yezigotify.utils.ThemeColorManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.gson.Gson
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val TOP_BAR_HEIGHT_DP = 56

        /** 抽屉里被展开的服务器分组（serverId 集合）；key 不存在 = 默认只展开第一台 */
        private const val KEY_DRAWER_EXPANDED = "drawer_expanded_servers"

        /**
         * 重拉应用列表的最小间隔
         *
         * 抽屉是高频操作，每拉开一次就对每台协议服务器发一轮 `/application` 是白白的
         * 流量/电量，所以做个节流；30 秒足够挡住"连续开合抽屉"这类无效请求，
         * 又不至于让服务端新建的应用迟迟看不到（下拉刷新会跳过节流）。
         */
        private const val APP_LIST_RELOAD_MIN_INTERVAL = 30 * 1000L
    }

    // ===== Compose state =====
    private val filterAppIdState = mutableStateOf(-1)
    private val currentChannelIdState = mutableStateOf<Int?>(null)
    private val isRefreshingState = mutableStateOf(false)
    private val imageAsBackgroundState = mutableStateOf(false)
    private val searchQueryState = mutableStateOf("")
    private val isSearchExpandingState = mutableStateOf(false)
    private val isCardDraggingState = mutableStateOf(false)
    private val isListAtTopState = mutableStateOf(true)
    private val isLongPressReadyState = mutableStateOf(false)
    private val isConfiguredState = mutableStateOf(false)
    /** 是否有任一台**已启用**的服务器被判定为 Chatz（Chatz 专有入口的显隐） */
    private val drawerAnyChatzState = mutableStateOf(false)

    /** 上次重拉应用列表的时间戳（只放进程内：重启后归零，而那时缓存也是空的，本来就要拉） */
    private var lastAppListReloadAt = 0L

    private val pendingScrollToMessageIdState = mutableStateOf(-1L)

    // 抽屉 Compose state
    private val drawerServerTextState = mutableStateOf("")
    private val drawerServersState = mutableStateOf<List<DrawerServer>>(emptyList())
    private val drawerDndState = mutableStateOf(false)
    private var dndStateListenerForDrawer: ((Boolean) -> Unit)? = null

    // Deps
    private lateinit var prefs: SharedPreferences
    private lateinit var viewModel: MessageViewModel
    private lateinit var bubbleHelper: BubbleHelper
    private lateinit var connectionBubbleHelper: BubbleHelper
    private lateinit var markwon: Markwon

    // State
    private var isNightMode: Boolean = false
    private var isColdStart = true
    private var lastDisplayedState: ConnectionState? = null
    private var hasLoadedOnce = false

    /**
     * 上一次 onResume 时记录的配置版本（ServerConfigStore.revision）与各服务器快照
     *
     * 地址与 token 分开存：两者变化要走的处理**不一样** ——
     *   地址变 = 换服务器 → 清该服务器消息与频道
     *   token 变 = 换身份 → 只作废该服务器已读（消息/频道/收藏保留）
     */
    private var lastRevision: Int? = null
    private var lastServerUrls: Map<String, String> = emptyMap()
    private var lastServerTokens: Map<String, String> = emptyMap()

    private var touchStartX = 0f
    private var touchStartY = 0f

    private var topBarColorCallback: ThemeColorManager.ColorCallback? = null

    // ========== Broadcast ==========

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GotifyService.BROADCAST_CONNECTION_STATE) {
                val name = intent.getStringExtra(GotifyService.EXTRA_STATE) ?: return
                applyConnectionState(ConnectionState.valueOf(name))
                // 每台服务器的连接状态点要跟着刷新（serverStates 由 Service 同步过来）
                refreshDrawerData()
            }
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GotifyService.BROADCAST_NEW_MESSAGE) {
                val json = intent.getStringExtra("message") ?: return
                try {
                    val msg = Gson().fromJson(json, GotifyMessage::class.java)
                    addMessageToList(msg)
                } catch (e: Exception) {
                    AppLog.e("MainActivity", "广播解析失败: ${e.message}")
                }
            }
        }
    }

    private val chatzEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GotifyService.BROADCAST_CHATZ_EVENT) {
                val json = intent.getStringExtra("event_json") ?: return
                // 取不到 serverId（旧广播）时回落 legacy 协议服务器（slot 0）
                val serverId = intent.getStringExtra(GotifyService.EXTRA_SERVER_ID)
                viewModel.handleChatzWsEvent(json, serverId)
            }
        }
    }

    // ========== Lifecycle ==========

    override fun onCreate(savedInstanceState: Bundle?) {

        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        ThemeColorManager.registerWallpaperListener(this)
        if (ThemeColorManager.isDynamicColorEnabled()) {
            ThemeColorManager.updateDynamicColorFromWallpaper(this)
        } else {
            ThemeColorManager.updateColorAndNotify()
        }

        bubbleHelper = BubbleHelper(this, lifecycleScope)
        connectionBubbleHelper = BubbleHelper(this, lifecycleScope)
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        ).get(MessageViewModel::class.java)
        markwon = Markwon.create(this)

        prefs = getSharedPreferences("gotify", MODE_PRIVATE)
        applyDarkModeFromPrefs()

        isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        initDefaults()
        // 首个 onResume 的基线：避免冷启动误判"配置变化"
        lastRevision = ServerConfigStore.revision(this)
        lastServerUrls = ServerConfigStore.all(this).associate { it.id to it.url }
        lastServerTokens = ServerConfigStore.all(this).associate { it.id to it.token }

        dndStateListenerForDrawer = { enabled ->
            runOnUiThread { drawerDndState.value = enabled }
        }
        dndStateListenerForDrawer?.let { DoNotDisturbManager.addListener(it) }
        drawerDndState.value = DoNotDisturbManager.isEnabled()
        refreshDrawerData()

        topBarColorCallback = ThemeColorManager.register { color ->
            applyTopBarColor(color)
            refreshWidgets()
            prefs.edit().putInt("top_bar_color", color).apply()
        }

        val connFilter = IntentFilter(GotifyService.BROADCAST_CONNECTION_STATE)
        ContextCompat.registerReceiver(
            this, connectionReceiver, connFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        AppThemeState.followSystem.value = prefs.getBoolean("follow_system_dark_mode", false)
        AppThemeState.darkMode.value = prefs.getBoolean("dark_mode", false)

        setContent {
            YezigotifyTheme {
                val drawerCounts by viewModel.drawerCounts.collectAsState(
                    initial = DrawerCounts.EMPTY
                )
                val channelsRaw by viewModel.channelsFlow.collectAsState(
                    initial = emptyList()
                )

                MainScreen(
                    // 顶栏
                    searchQuery = searchQueryState.value,
                    onSearchQueryChange = { searchQueryState.value = it },
                    isSearchExpanding = isSearchExpandingState.value,
                    onSearchExpandingChange = { isSearchExpandingState.value = it },
                    onToggleTheme = { toggleTheme() },

                    // 抽屉
                    drawerServerText = drawerServerTextState.value,
                    drawerServers = drawerServersState.value,
                    drawerChannels = channelsRaw,
                    drawerAnyChatz = drawerAnyChatzState.value,
                    drawerCurrentChannelId = currentChannelIdState.value,
                    currentFilterAppId = filterAppIdState.value,
                    drawerDndEnabled = drawerDndState.value,
                    drawerCounts = drawerCounts,
                    onSelectFilter = { id -> applyAppFilter(id) },
                    onSelectChannel = { id -> applyChannelFilter(id) },
                    onOpenSettings = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                    onClearMessages = { viewModel.clearAll() },
                    onMarkAllRead = {
                        // 当前在某个频道 → 只标记该频道；否则全部（所有 Chatz 服务器）
                        val channelId = currentChannelIdState.value
                        val ch = channelId?.let { cid ->
                            channelsRaw.firstOrNull { it.id == cid }
                        }
                        viewModel.markAllRead(ch)
                        bubbleHelper.show(
                            if (ch != null) "当前频道已全部标为已读" else "已全部标为已读"
                        )
                    },
                    onDndToggle = { enabled ->
                        DoNotDisturbManager.setEnabled(enabled)
                        bubbleHelper.show(if (enabled) "勿扰模式已开启" else "勿扰模式已关闭")
                    },
                    onDrawerOpened = { reloadAppListAndRefreshDrawer() },
                    onToggleServer = { serverId -> toggleDrawerServer(serverId) },

                    // 列表
                    viewModel = viewModel,
                    imageAsBackground = imageAsBackgroundState.value,
                    markwon = markwon,
                    isRefreshing = isRefreshingState.value,
                    onRefresh = {
                        isRefreshingState.value = true
                        syncAndRefresh(forceFullSync = false)
                        // 用户主动刷新 → 跳过节流，顺带把应用列表也重拉一遍
                        reloadAppListAndRefreshDrawer(force = true)
                    },
                    isCardDraggingState = isCardDraggingState,
                    isListAtTopState = isListAtTopState,
                    isLongPressReadyState = isLongPressReadyState,
                    onImageClick = { url, bmp -> showComposeImagePreview(url, bmp) },
                    onUrlClick = { url ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "无法打开链接",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onLongPress = { msg ->
                        val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Gotify消息",
                                "标题: ${msg.title}\n消息: ${msg.message}"
                            )
                        )
                        bubbleHelper.show("已复制消息内容")
                    },

                    // 状态
                    isConfigured = isConfiguredState.value,
                    onGoSettings = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },

                    // 跳转消息
                    pendingScrollToMessageId = pendingScrollToMessageIdState.value,
                    onScrollCompleted = {
                        pendingScrollToMessageIdState.value = -1L
                    },

                    // 点引用条 → 跳到被回复的那条消息
                    onQuoteClick = { serverId, remoteReplyId ->
                        // extras 里存的是**远程** id，跳转要的是**本地** id（高位带 slot），
                        // 而 slot 只有服务器配置知道，所以这一步必须在这里做
                        val slot = ServerConfigStore.byId(this@MainActivity, serverId)?.slot ?: 0
                        // 先清掉会过滤掉目标消息的筛选：收藏/未读/应用筛选会让目标不在
                        // 当前列表里，那时 getMessageIndex 会算出一个错位的下标。
                        // 频道筛选保留 —— 回复总是发在原消息所在的频道，目标一定还在列表里
                        viewModel.setCurrentTag(null)
                        filterAppIdState.value = -1
                        pendingScrollToMessageIdState.value =
                            LocalIds.makeMessageId(slot, remoteReplyId)
                    }
                )
            }
        }

        checkFirstLaunch()
        handleIntentScroll(intent)
        checkNotificationPermission()
        checkBatteryOptimization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentScroll(intent)
    }

    override fun onResume() {
        super.onResume()

        startService(Intent(this, GotifyService::class.java).apply {
            action = GotifyService.ACTION_CLEAR_NOTIFICATIONS
        })

        if (ThemeColorManager.isDynamicColorEnabled()) {
            ThemeColorManager.updateDynamicColorFromWallpaper(this)
        }
        applyDarkModeFromPrefs()
        applyNightModeUI()
        if (lastDisplayedState == null) {
            lastDisplayedState = GotifyService.currentState
        }
        imageAsBackgroundState.value = prefs.getBoolean("image_as_background", false)

        registerMessageReceivers()
        applyTopBarColor(ThemeColorManager.getEffectiveColor())

        // ============================================================
        // 配置变化检测：比较 ServerConfigStore.revision（持久化，跨进程重启后仍有效）
        //
        //   地址变 → 换服务器：必须**按 serverId** 清该服务器的本地 DB
        //            （新旧服务器消息 id 会冲突；只清变动的那台，S1 风险）
        //   仅 token 变 → 同服务器重新登录/换凭据：
        //            **什么都不做**，绝不清消息、也**不清本地已读**。
        //            `token 字符串变了` ≠ `身份变了`（服务端运维动作如应用 Token
        //            改格式、重新部署都会让 token 变而身份不变），此时清已读就是
        //            误伤 —— 曾经因此表现为「覆盖安装后历史消息全部变未读」。
        //            跨身份串读的问题由 read_synced 机制在同步时权威纠正，
        //            不需要本地先清零（详见下方 if 里的注释）。
        // ============================================================
        val rev = ServerConfigStore.revision(this)
        val currentUrls = ServerConfigStore.all(this).associate { it.id to it.url }
        val configChanged = lastRevision != null && rev != lastRevision

        // currentTokens / lastServerTokens 目前**不参与任何判断**，只维持快照新鲜。
        //
        // 历史用途是检测「凭据变化 → 作废已读」，该逻辑已于 2026-09-29 停用
        // （token 变了不等于身份变了，见下方 if 里的长注释）。
        // 将来若要做「按身份比对」（调 /auth/me 比 userId），改的应该是这组快照的
        // 内容（存 userId 而不是 token），而不是恢复旧判断 —— 所以这里先留着。
        val currentTokens = ServerConfigStore.all(this).associate { it.id to it.token }

        if (configChanged) {
            for ((id, url) in currentUrls) {
                val oldUrl = lastServerUrls[id]
                // 只在「地址真的变了」（= 换了另一台服务器）时清该服务器的本地数据。
                //
                // ⚠️ 刻意不再处理「地址没变但 token 变了」的情况。
                //
                // 曾经这里会调 resetReadStateForTokenChange(id)，把该服务器全部消息的
                // is_read 清 0。设计意图是「换 token = 换身份，旧身份的已读会永久压住
                // 服务端真实状态」。但这条清理有一个致命问题：
                //
                //   `token 字符串变了` ≠ `身份变了`。服务端的运维动作（应用 Token 批量
                //   改格式、重新部署、配置短暂读空后回写……）都会让快照里的 token 与
                //   当前值不同，而身份根本没变 —— 本地已读却被无条件清空，用户看到的
                //   就是「覆盖安装后历史消息全部变未读」。
                //
                // 而它想解决的「跨身份串读」已经被 read_synced 机制根治
                // （见 AppDatabase.MIGRATION_9_10 与 MessageDao.IdReadPair）：服务端返回的
                // 状态会按 `read_synced = 1` 权威覆盖本地，不需要先在本地粗暴清零。
                // 真换账号时，本地已读会在紧随其后的全量同步里被服务端纠正回来。
                if (oldUrl != null && oldUrl != url) {
                    AppLog.d("MainActivity", "URL 变化，清空本地 DB: $id: $oldUrl → $url")
                    viewModel.clearAllForServerSwitch(id)
                }
            }
        }
        lastRevision = rev
        lastServerUrls = currentUrls
        lastServerTokens = currentTokens

        if (configChanged) {
            hasLoadedOnce = false
            // 独立启动 Service（内部会按新配置差分重载）
            startGotifyService()

            // App 图标加载（与同步并行）
            AppIconCache.setOnLoadedCallback {
                runOnUiThread { ensureDrawerMenuLoaded() }
            }
            loadAppIconsForAll()

            // 先探测所有服务器，再全量同步
            lifecycleScope.launch {
                probeAllServers()
                refreshDrawerData()
                viewModel.refreshChatzServerIds()
                // 配置变化 → 必须全量（增量无法发现 id 更小的旧消息）
                syncAndRefresh(forceFullSync = true)
            }
        } else if (!hasLoadedOnce) {
            ensureDrawerMenuLoaded()
            if (!hasLoadedOnce) {
                syncAndRefresh()
                startGotifyService()
            }
        } else {
            checkAndAutoFullSync()
            // 频道列表：这条路径原先只刷了应用列表和应用图标，没碰频道 ——
            // 于是服务端新建 / 改名 / 换图标的频道，只能杀进程重启才看得到。
            // WS 事件在 App 后台时会丢，所以这里必须主动补一次。
            viewModel.refreshChannelList()
            // 含已停用的卡：停用那台的消息仍在列表里，图标不能退化成首字母
            val needLoad = iconSourceServers().any {
                AppIconCache.getAppList(it.id).isNullOrEmpty()
            }
            if (needLoad) {
                AppIconCache.setOnLoadedCallback {
                    runOnUiThread { refreshDrawerData() }
                }
                loadAppIconsForAll()
            } else {
                refreshDrawerData()
            }
            if (!GotifyService.isServiceRunning) startGotifyService()
        }

        isConfiguredState.value = isAnyServiceConfigured()

        lifecycleScope.launch {
            delay(800)
            if (isFinishing) return@launch
            val currentState = GotifyService.currentState
            if (currentState != lastDisplayedState) {
                applyConnectionState(currentState)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppIconCache.clearOnLoadedCallback()
        try {
            unregisterReceiver(messageReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(chatzEventReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val followSystem = prefs.getBoolean("follow_system_dark_mode", true)
        if (!followSystem) return
        val newNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (newNight != isNightMode) {
            isNightMode = newNight
            prefs.edit().putBoolean("dark_mode", isNightMode).apply()
            applyNightModeUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppIconCache.clearOnLoadedCallback()
        ThemeColorManager.unregisterWallpaperListener(this)
        topBarColorCallback?.let { ThemeColorManager.unregister(it) }
        dndStateListenerForDrawer?.let { DoNotDisturbManager.removeListener(it) }
        dndStateListenerForDrawer = null
        if (::bubbleHelper.isInitialized) bubbleHelper.cancel()
        longPressHandler.removeCallbacksAndMessages(null)
        longPressScheduled = false
        try {
            unregisterReceiver(connectionReceiver)
        } catch (_: Exception) {
        }
    }

    // ========== 主题 / 颜色 ==========

    private fun toggleTheme() {
        val newDark = !isNightMode
        with(prefs.edit()) {
            putBoolean("dark_mode", newDark)
            putBoolean("follow_system_dark_mode", false)
            apply()
        }
        AppCompatDelegate.setDefaultNightMode(
            if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        isNightMode = newDark
        applyNightModeUI()
    }

    private fun applyConnectionState(state: ConnectionState) {
        if (state == lastDisplayedState) return
        val previousState = lastDisplayedState
        lastDisplayedState = state
        when (state) {
            ConnectionState.CONNECTED -> {
                connectionBubbleHelper.show("已连接", durationMs = 3000)
                if (previousState == ConnectionState.RECONNECTING ||
                    previousState == ConnectionState.DISCONNECTED
                ) {
                    if (!isColdStart) syncAndRefresh() else isColdStart = false
                }
            }

            ConnectionState.RECONNECTING -> {
                connectionBubbleHelper.show(
                    "重连中...",
                    textColor = Color.parseColor("#E53935")
                )
            }

            ConnectionState.PARTIAL -> {
                // 多服务器：有的连上、有的没连上 —— 不重连、不弹错误提示
                connectionBubbleHelper.show("部分服务器未连接", durationMs = 3000)
            }

            ConnectionState.DISCONNECTED -> {
                connectionBubbleHelper.show("服务未运行")
            }
        }
    }

    private fun applyNightModeUI() {
        AppThemeState.followSystem.value = prefs.getBoolean("follow_system_dark_mode", false)
        AppThemeState.darkMode.value = prefs.getBoolean("dark_mode", false)
        applyTopBarColor(ThemeColorManager.getEffectiveColor())
    }

    private fun applyDarkModeFromPrefs() {
        val followSystem = prefs.getBoolean("follow_system_dark_mode", false)
        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            val isDark = prefs.getBoolean("dark_mode", false)
            AppCompatDelegate.setDefaultNightMode(
                if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun applyTopBarColor(color: Int) {
        window.statusBarColor = color
        val brightness =
            Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114
        val isLight = brightness > 186
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            isLight
    }

    // ========== 数据 / 服务 ==========

    private fun refreshDrawerData() {
        val newServerText = ServerDisplay.getServerDisplayText(this)
        if (drawerServerTextState.value != newServerText) {
            drawerServerTextState.value = newServerText
        }

        // 每台已启用且可用的服务器组装一个分组
        // 连接状态点：优先用该台的真实状态，拿不到时回落到汇总态
        val serverStates = GotifyService.serverStates
        val aggregateConnected = GotifyService.currentState == ConnectionState.CONNECTED

        val enabledServers = ServerConfigStore.all(this).filter { it.enabled && it.isUsable }

        // Chatz 专有入口（未读 / 发现频道 / 全部已读）的显隐依据。
        // ⚠️ 只看**已启用**的服务器：停用一张 Chatz 卡后它的能力探测结果仍留在缓存里，
        // 不按启用状态过滤的话这些入口会赖着不走。
        drawerAnyChatzState.value =
            ServerCapabilitiesHolder.anyChatz(enabledServers.map { it.id })

        // 分组展开态：默认**只展开第一台**（只有一台时等同于完全展开，
        // 多台时其余收起）；用户点过分组头之后以持久化的记录为准
        val expandedIds = readDrawerExpandedIds(enabledServers.map { it.id })

        val newServers = enabledServers
            .map { cfg -> cfg to (cfg.id in expandedIds) }
            .map { (cfg, expanded) ->
                if (cfg.isNtfy) {
                    DrawerServer(
                        serverId = cfg.id,
                        slot = cfg.slot,
                        label = ServerDisplay.labelOf(cfg),
                        kindLabel = ServerDisplay.kindBadge(cfg),
                        connected = serverStates[cfg.id] == ConnectionState.CONNECTED
                                || (serverStates[cfg.id] == null && aggregateConnected),
                        apps = emptyList(),
                        channels = emptyList(),   // 由 MainScreen 从响应式 channelsFlow 合并
                        ntfyTopic = cfg.topic.takeIf { it.isNotBlank() },
                        totalCount = 0,           // 由 MainScreen 从 drawerCounts.byServer 合并
                        expanded = expanded
                    )
                } else {
                    val appList = AppIconCache.getAppList(cfg.id) ?: emptyList()
                    val apps = appList.distinctBy { it.id }.map { app ->
                        DrawerApp(
                            // 合成 filter id（slot 0 时等于原始 appid）
                            id = LocalIds.makeAppFilterId(cfg.slot, app.id),
                            appId = app.id,
                            serverId = cfg.id,
                            name = app.name,
                            iconUrl = AppIconCache.getIconUrl(cfg.id, app.id)
                        )
                    }
                    DrawerServer(
                        serverId = cfg.id,
                        slot = cfg.slot,
                        label = ServerDisplay.labelOf(cfg),
                        kindLabel = ServerDisplay.kindBadge(cfg),
                        connected = serverStates[cfg.id] == ConnectionState.CONNECTED
                                || (serverStates[cfg.id] == null && aggregateConnected),
                        apps = apps,
                        channels = emptyList(),
                        ntfyTopic = null,
                        totalCount = 0,
                        expanded = expanded
                    )
                }
            }
        if (drawerServersState.value != newServers) {
            drawerServersState.value = newServers
        }

        // 失效筛选的兜底不在这里做：MainActivity 这份数据里 channels 是空的
        // （频道由 MainScreen 从响应式流合并进去），算不出"抽屉里真正可见的入口"。
        // 统一由 MainScreen 的 LaunchedEffect 处理 —— 免得两处判断打架。

        val newDnd = DoNotDisturbManager.isEnabled()
        if (drawerDndState.value != newDnd) {
            drawerDndState.value = newDnd
        }
    }

    /**
     * 抽屉分组的展开态（持久化在 prefs）
     *
     *   - 从未点过分组头（key 不存在）→ 默认**只展开第一台**。
     *     这里刻意不写盘：渲染路径上不该写 prefs；等用户第一次点击才落盘。
     *   - 一旦落盘（哪怕是空集合）→ 以记录为准 —— 所以"全部收起"也是可持久化的状态。
     */
    private fun readDrawerExpandedIds(allServerIds: List<String>): Set<String> {
        if (!prefs.contains(KEY_DRAWER_EXPANDED)) return allServerIds.take(1).toSet()
        return prefs.getStringSet(KEY_DRAWER_EXPANDED, emptySet())?.toSet().orEmpty()
    }

    /** 点分组头：展开 / 收起该服务器分组，并落盘 */
    private fun toggleDrawerServer(serverId: String) {
        val allIds = ServerConfigStore.all(this)
            .filter { it.enabled && it.isUsable }
            .map { it.id }
        val next = HashSet(readDrawerExpandedIds(allIds))
        // remove 返回 true = 原本就是展开的 → 这次收起；否则展开
        if (!next.remove(serverId)) next.add(serverId)
        prefs.edit().putStringSet(KEY_DRAWER_EXPANDED, next).apply()
        refreshDrawerData()
    }

    /** 只处理有 url + token 的协议服务器（ntfy 没有应用列表，也不走 HTTP 探测的 app 端点） */
    private fun enabledProtocolServers() =
        ServerConfigStore.enabledProtocolServers(this)
            .filter { it.url.isNotBlank() && it.token.isNotBlank() }

    /**
     * 拉应用图标用的服务器列表：**包含已停用的卡**
     *
     * 为什么不能用 [enabledProtocolServers]：停用只是"不再连 WebSocket"，消息仍然
     * 留在列表里（用户要求不清内容）。而 AppIconCache 是**纯内存缓存**，进程重启后
     * 必须重新拉一次；只给已启用的服务器拉，停用那台的消息在列表里就会退化成
     * 首字母头像 —— 表现就是"关掉开关后重启，应用图标全没了"。
     */
    private fun iconSourceServers() =
        ServerConfigStore.all(this).filter { it.isProtocolServer && it.isUsable }

    private fun loadAppIconsForAll() {
        iconSourceServers().forEach { cfg ->
            AppIconCache.loadAppIcons(cfg.id, cfg.url, cfg.token)
        }
        // 刚确保过一遍新鲜度 → 记一下时间，免得紧接着打开抽屉又立刻重拉一轮
        lastAppListReloadAt = System.currentTimeMillis()
    }

    /**
     * 重拉应用列表并刷新抽屉
     *
     * 应用列表在一个 App 进程里原本只会拉一次（AppIconCache 里「已加载过同地址就返回」
     * 的短路），所以服务端新建 / 删除应用后必须杀进程重开才看得到。这里主动重拉一次；
     * 因为不清空旧数据（新响应到了才整体替换），抽屉不会先闪空一下再填上。
     *
     * **带节流**：抽屉是高频操作，每拉开一次就对每台服务器发一轮 `/application`
     * 是白白的流量。距上次刷新不足 [APP_LIST_RELOAD_MIN_INTERVAL] 就直接用内存缓存
     * 重画（下拉刷新走 `force = true`，不受节流限制）。
     *
     * 进程重启后 [lastAppListReloadAt] 归零、同时 AppIconCache 也是空的，
     * 那时本来就必须拉一次，所以不会被节流误拦。
     *
     * @param force true = 用户主动刷新（下拉），跳过节流
     */
    private fun reloadAppListAndRefreshDrawer(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAppListReloadAt < APP_LIST_RELOAD_MIN_INTERVAL) {
            // 还在节流窗口内 → 直接用缓存重画，不发请求
            refreshDrawerData()
            return
        }

        // 含已停用的卡：停用那台的消息仍在列表里，图标也得跟着刷新
        val servers = iconSourceServers()
        if (servers.isEmpty()) {
            refreshDrawerData()
            return
        }
        lastAppListReloadAt = now
        AppIconCache.setOnLoadedCallback {
            runOnUiThread { refreshDrawerData() }
        }
        servers.forEach { cfg ->
            AppIconCache.reloadAppIcons(cfg.id, cfg.url, cfg.token)
        }
    }

    private suspend fun probeAllServers() {
        for (cfg in enabledProtocolServers()) {
            ServerCapabilitiesHolder.refresh(this, cfg.id, cfg.url, cfg.token)
        }
    }

    private fun registerMessageReceivers() {
        ContextCompat.registerReceiver(
            this, messageReceiver, IntentFilter(GotifyService.BROADCAST_NEW_MESSAGE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, chatzEventReceiver, IntentFilter(GotifyService.BROADCAST_CHATZ_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun showComposeImagePreview(imageUrl: String, bitmap: Bitmap?) {
        if (isDestroyed || isFinishing) return

        val dialog = Dialog(this, R.style.Theme_Yezigotify)
        val composeView = ComposeView(this).apply {
            setContent {
                YezigotifyTheme {
                    ImagePreviewDialogContent(
                        imageUrl = imageUrl,
                        existingBitmap = bitmap,
                        onDismiss = { dialog.dismiss() }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        dialog.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        dialog.show()
    }

    private fun initDefaults() {
        // 注意：这里**不再**预写 serverUrl / clientToken。
        // 那是旧单源配置时代的初始化，现在配置由 ServerConfigStore 管理，
        // 这两个 key 只作为升级迁移的输入，不该由客户端凭空创建。
        val editor = prefs.edit()
        if (!prefs.contains("top_bar_color")) editor.putInt(
            "top_bar_color",
            Color.parseColor("#A5D6A7")
        )
        if (!prefs.contains("last_full_sync_time")) editor.putLong(
            "last_full_sync_time",
            System.currentTimeMillis()
        )
        editor.apply()
    }

    private fun isAnyServiceConfigured(): Boolean =
        ServerConfigStore.hasAnyEnabled(this)

    private fun ensureDrawerMenuLoaded() {
        if (hasLoadedOnce) return

        val protoServers = enabledProtocolServers()
        if (protoServers.isEmpty()) {
            // 只配了 ntfy（或无协议服务器）：直接算一次抽屉数据
            if (ServerConfigStore.hasAnyEnabled(this)) {
                hasLoadedOnce = true
                refreshDrawerData()
            }
            return
        }

        val anyLoaded = protoServers.any { !AppIconCache.getAppList(it.id).isNullOrEmpty() }
        if (anyLoaded) {
            hasLoadedOnce = true
            refreshDrawerData()
        } else {
            AppIconCache.setOnLoadedCallback {
                runOnUiThread {
                    if (!hasLoadedOnce) {
                        hasLoadedOnce = true
                        refreshDrawerData()
                    }
                }
            }
            loadAppIconsForAll()
        }
    }

    private fun checkFirstLaunch() {
        isConfiguredState.value = isAnyServiceConfigured()
        if (isConfiguredState.value) {
            ensureDrawerMenuLoaded()
            if (!GotifyService.isServiceRunning) startGotifyService()
        }
    }

    private fun addMessageToList(msg: GotifyMessage) {
        viewModel.addMessage(msg)
    }

    private fun syncAndRefresh(forceFullSync: Boolean = false) {
        val startTime = System.currentTimeMillis()
        viewModel.syncAllServers(forceFullSync = forceFullSync) { newCount ->
            // 被跳过（已有同步在跑）：不弹提示（"已是最新消息"会是误报）。
            // 这里也**不动** isRefreshingState —— 正在跑的那次同步结束时一定会把它
            // 置回 false（VM 的 report 用 try/finally 保证恰好回调一次），
            // 所以下拉指示器会一直转到真正同步完，符合直觉。
            if (newCount == MessageViewModel.SYNC_SKIPPED) {
                return@syncAllServers
            }
            if (forceFullSync) {
                prefs.edit().putLong("last_full_sync_time", System.currentTimeMillis()).apply()
            }
            // newCount 小于 0 是哨兵值（-1 = 每台都失败），不能当成条数显示
            val message = when {
                newCount < 0 -> "同步失败，请检查网络"
                newCount == 0 -> "已是最新消息"
                else -> "已更新 ${newCount}条消息"
            }
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = (1500L - elapsed).coerceAtLeast(0L)
            lifecycleScope.launch {
                if (remaining > 0) delay(remaining)
                isRefreshingState.value = false
                bubbleHelper.show(message)
            }
        }
    }

    private fun applyAppFilter(appId: Int) {
        filterAppIdState.value = appId
        currentChannelIdState.value = null
        viewModel.setCurrentAppId(appId)
    }

    private fun applyChannelFilter(channelId: Int) {
        currentChannelIdState.value = channelId
        filterAppIdState.value = -1
        viewModel.setCurrentChannelId(channelId)
    }

    private fun startGotifyService() {
        // 配置已由 ServerConfigStore 统一管理，不再传 SERVER_URL / CLIENT_TOKEN extra
        val intent = Intent(this, GotifyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    // ========== 触摸事件 ==========

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    @Volatile
    private var longPressScheduled = false

    private val longPressRunnable = Runnable {
        longPressScheduled = false
        longPressTriggered = true
        bubbleHelper.show("松手执行全量同步")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, 120))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        if (isSearchExpandingState.value && ev.action == MotionEvent.ACTION_DOWN) {
            val statusBarHeightPx = run {
                val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            }
            val topBarHeightPx = (TOP_BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()
            val topBarBottom = statusBarHeightPx + topBarHeightPx

            if (ev.rawY > topBarBottom) {
                isSearchExpandingState.value = false
                searchQueryState.value = ""
            }
        }

        val density = resources.displayMetrics.density
        val minPullPx = 15 * density
        val hintThresholdPx = 5 * density

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                longPressTriggered = false
                longPressScheduled = false
                longPressHandler.removeCallbacks(longPressRunnable)
                isLongPressReadyState.value = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchStartX
                val dy = ev.y - touchStartY

                val isPullingDownLoosely = isListAtTopState.value &&
                        dy > hintThresholdPx &&
                        Math.abs(dy) > Math.abs(dx)

                if (isPullingDownLoosely &&
                    !prefs.getBoolean("full_sync_hint_shown", false)
                ) {
                    prefs.edit().putBoolean("full_sync_hint_shown", true).apply()
                    bubbleHelper.show(
                        message = "下拉并按住 1.5 秒可全量同步",
                        durationMs = 3500,
                        topMarginDp = 110f
                    )
                }

                val isPullDown = isListAtTopState.value &&
                        !isCardDraggingState.value &&
                        dy > minPullPx &&
                        Math.abs(dy) > Math.abs(dx) * 1.5f

                if (isPullDown) {
                    isLongPressReadyState.value = true
                    if (!longPressTriggered && !longPressScheduled) {
                        longPressScheduled = true
                        longPressHandler.postDelayed(longPressRunnable, 1500)
                    }
                } else {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressScheduled = false
                    isLongPressReadyState.value = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressScheduled = false
                if (longPressTriggered) {
                    isRefreshingState.value = true
                    syncAndRefresh(forceFullSync = true)
                    // 和下拉刷新一样是用户主动行为 → 应用列表也跳过节流重拉
                    reloadAppListAndRefreshDrawer(force = true)
                }
                isLongPressReadyState.value = false
                longPressTriggered = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ========== 其他 ==========

    private fun handleIntentScroll(intent: Intent) {
        var targetId = intent.getLongExtra("scroll_to_message_id", -1L)
        if (targetId == -1L) {
            val uri = intent.data
            if (uri != null && uri.scheme == "gotify") {
                targetId = uri.lastPathSegment?.toLongOrNull() ?: -1L
            }
        }
        if (targetId != -1L) {
            filterAppIdState.value = -1
            currentChannelIdState.value = null
            pendingScrollToMessageIdState.value = targetId
        }
    }

    private fun checkAndAutoFullSync() {
        val autoSyncDays = prefs.getString("auto_sync_days", "7")?.toIntOrNull() ?: 7
        if (autoSyncDays <= 0) return
        val lastFullSync = prefs.getLong("last_full_sync_time", 0L)
        val daysSince = (System.currentTimeMillis() - lastFullSync) / (24 * 60 * 60 * 1000L)
        if (daysSince >= autoSyncDays) {
            bubbleHelper.show("自动全量同步中...")
            syncAndRefresh(forceFullSync = true)
        }
    }

    @SuppressLint("ServiceCast")
    private fun checkBatteryOptimization() {
        val hasPrompted = prefs.getBoolean("battery_prompted", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            if (isIgnoring) {
                prefs.edit().putBoolean("battery_prompted", false).apply()
                return
            }
            if (!hasPrompted) {
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle("后台保活建议")
                    .setMessage("为了保证熄屏后消息不断连，建议关闭电池优化。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            appInfo.data = Uri.parse("package:$packageName")
                            startActivity(appInfo)
                        }
                    }
                    .setNegativeButton("不再提示") { _, _ ->
                        prefs.edit().putBoolean("battery_prompted", true).apply()
                    }
                    .create()
                val density = resources.displayMetrics.density
                val cornerRadius = 16 * density
                val shapeAppearance = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, cornerRadius).build()
                dialog.window?.let { window ->
                    val background = MaterialShapeDrawable(shapeAppearance).apply {
                        setTint(
                            MaterialColors.getColor(
                                this@MainActivity,
                                com.google.android.material.R.attr.colorSurface, Color.WHITE
                            )
                        )
                        elevation = 4 * density
                    }
                    window.setBackgroundDrawable(background)
                }
                dialog.show()
            }
        }
    }

    private fun refreshWidgets() {
        val intent = Intent("asia.guojuice.yezigotify.WIDGET_UPDATE")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限被拒绝，请到系统设置中手动开启通知", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}