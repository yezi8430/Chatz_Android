package asia.guojuice.yezigotify.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.AttachmentUploadResult
import asia.guojuice.yezigotify.DrawerCounts
import asia.guojuice.yezigotify.DraftStore
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.MessageViewModel
import asia.guojuice.yezigotify.OutgoingMessage
import asia.guojuice.yezigotify.UploadedAttachment
import asia.guojuice.yezigotify.capabilities.ChannelSubscribeResult
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.channel.ChannelDiscoverDialog
import asia.guojuice.yezigotify.ui.channel.ChannelManageDialog
import asia.guojuice.yezigotify.ui.channel.ChannelMenuSheet
import asia.guojuice.yezigotify.ui.channel.ChannelPasswordDialog
import asia.guojuice.yezigotify.ui.dnd.AppDndConfigDialog
import asia.guojuice.yezigotify.ui.drawer.DrawerApp
import asia.guojuice.yezigotify.ui.drawer.DrawerContent
import asia.guojuice.yezigotify.ui.drawer.DrawerServer
import asia.guojuice.yezigotify.ui.message.MessageActionSheet
import asia.guojuice.yezigotify.ui.message.MessageListScreen
import asia.guojuice.yezigotify.utils.MessageText
import io.noties.markwon.Markwon
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    // 顶栏
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanding: Boolean,
    onSearchExpandingChange: (Boolean) -> Unit,
    onToggleTheme: () -> Unit,

    // 抽屉
    drawerServerText: String,
    drawerServers: List<DrawerServer>,
    drawerChannels: List<ChatzChannel>,
    drawerAnyChatz: Boolean,
    drawerCurrentChannelId: Int?,
    currentFilterAppId: Int,
    drawerDndEnabled: Boolean,
    drawerCounts: DrawerCounts,
    onSelectFilter: (Int) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onClearMessages: () -> Unit,
    onMarkAllRead: () -> Unit,
    onDndToggle: (Boolean) -> Unit,
    /** 抽屉每次打开时回调：用于重拉应用列表（服务端新建应用后不用重启 App） */
    onDrawerOpened: () -> Unit = {},
    /** 点服务器分组头：展开 / 收起该分组（参数是 serverId） */
    onToggleServer: (String) -> Unit = {},

    // 列表
    viewModel: MessageViewModel,
    imageAsBackground: Boolean,
    markwon: Markwon,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isCardDraggingState: MutableState<Boolean>,
    isListAtTopState: MutableState<Boolean>,
    isLongPressReadyState: MutableState<Boolean>,
    onImageClick: (String, Bitmap?) -> Unit,
    onUrlClick: (String) -> Unit,
    onLongPress: (GotifyMessage) -> Unit,
    /** 点击引用条：跳转到被回复的消息（serverId, 远程 id），转换成本地 id 由 MainActivity 做 */
    onQuoteClick: (String, Long) -> Unit = { _, _ -> },

    // 状态
    isConfigured: Boolean,
    onGoSettings: () -> Unit,

    // 跳转消息
    pendingScrollToMessageId: Long = -1L,
    onScrollCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var actionSheetMessage by remember { mutableStateOf<GotifyMessage?>(null) }
    var appConfigTarget by remember { mutableStateOf<DrawerApp?>(null) }

    // 把（非响应式的）服务器核心信息与（响应式的）频道 / 计数合并成抽屉分组
    val servers = drawerServers.map { s ->
        val serverChannels = drawerChannels.filter { it.sourceServerId == s.serverId }
        s.copy(
            channels = serverChannels,
            totalCount = drawerCounts.byServer[s.serverId] ?: s.totalCount,
            // 有未读 → 分组头的消息数用淡红字提示（与频道徽标的淡红提示同一套语义）
            hasUnread = serverChannels.any { it.id in drawerCounts.channelsWithUnread }
        )
    }

    // ===== 失效筛选兜底 =====
    //
    // 抽屉里"看得见"的入口集合。用于把已经消失的筛选切回「所有消息」：
    // 入口消失（服务器被停用 / 应用分组被频道取代）之后，列表还在按它过滤，
    // 用户会看到"列表被过滤着、但抽屉里根本找不到对应入口"，无从下手。
    //
    // 只在"当前筛选值 >= 0 或 频道非空"时才可能触发，而这两个值都只可能由用户
    // 点击抽屉里的可见入口产生，所以冷启动不会被误判（那时它们都是 -1 / null）。
    val visibleChannelIds = servers.flatMap { it.channels }.map { it.id }.toSet()
    val visibleAppFilterIds = buildSet {
        servers.forEach { s ->
            // 有频道时 DrawerContent 不渲染「应用」分组 → 那些 app filter 也不再可见
            if (s.channels.isEmpty()) s.apps.forEach { add(it.id) }
            s.ntfyTopic?.let { add(LocalIds.makeAppFilterId(s.slot, 0)) }
        }
    }
    LaunchedEffect(servers, drawerCurrentChannelId, currentFilterAppId) {
        val channelGone = drawerCurrentChannelId != null &&
                drawerCurrentChannelId !in visibleChannelIds
        val appGone = currentFilterAppId >= 0 && currentFilterAppId !in visibleAppFilterIds
        if (channelGone || appGone) {
            AppLog.d(
                "MainScreen",
                "筛选入口已消失（channel=$drawerCurrentChannelId, app=$currentFilterAppId），切回「所有消息」"
            )
            onSelectFilter(-1)
        }
    }

    // 频道长按菜单
    var channelMenuTarget by remember { mutableStateOf<ChatzChannel?>(null) }

    // 频道管理弹窗目标（长按菜单 → 管理频道）
    var channelManageTarget by remember { mutableStateOf<ChatzChannel?>(null) }

    // ===== 频道发消息 =====
    //
    // 发送目标 = 当前打开的频道。drawerChannels 里只有 Chatz 频道的行，
    // 所以匹配到就说明是 Chatz 服务器，不用再判类型。
    // 推送路径：服务端创建后经 WS 广播回本机（和其他设备的新消息同一条路径），
    // 因此这里不需要本地插入。
    val sendChannel = drawerCurrentChannelId?.let { cid ->
        drawerChannels.firstOrNull { it.id == cid }
    }

    // 临时发送目标（原地回复用）
    //
    // 在「所有消息」这种没有频道筛选的视图里回复时，发送需要一个明确的频道目标，
    // 但又不该把整个列表切走 —— 于是把这个频道临时挂成发送目标。
    // 用户一旦从抽屉切了别的入口、或把输入框收起，就撤销它。
    var composeTarget by remember { mutableStateOf<ChatzChannel?>(null) }
    val sendTarget = composeTarget ?: sendChannel

    // 任何"切换筛选"的动作都撤销临时目标：用户已经离开那个上下文了
    val selectFilter: (Int) -> Unit = { id ->
        composeTarget = null
        onSelectFilter(id)
    }
    val selectChannel: (Int) -> Unit = { id ->
        composeTarget = null
        onSelectChannel(id)
    }

    var sendingMessage by remember { mutableStateOf(false) }
    var uploadingAttachment by remember { mutableStateOf(false) }

    // 整份草稿（正文 + 附件 + 标题/优先级/标签/引用）提在这里：换发送目标一起清掉，
    // 避免把 A 频道打了一半的内容发到 B 频道。
    // key 用 sendTarget 而不是 sendChannel —— 原地回复时 sendChannel 是 null，
    // 草稿得跟着临时目标走，否则一挂上目标就被重置了。
    // 初始值从本地草稿恢复：切频道/切视图/进程被杀都不该丢
    var sendDraft by remember(sendTarget?.id) {
        val target = sendTarget
        mutableStateOf(
            if (target == null) OutgoingMessage()
            else DraftStore.load(context, target.sourceServerId, target.id) ?: OutgoingMessage()
        )
    }
    var showSendOptions by remember { mutableStateOf(false) }

    // 草稿落盘（防抖 500ms，别每敲一个字就写一次 prefs）。
    // 空草稿 → 清掉存的那份（发送成功后 sendDraft 被置空，走的就是这一支）
    LaunchedEffect(sendTarget?.id, sendDraft) {
        val target = sendTarget ?: return@LaunchedEffect
        val isEmpty = sendDraft.text.isBlank() &&
                sendDraft.attachment == null && !sendDraft.isReply
        if (isEmpty) {
            DraftStore.clear(context, target.sourceServerId, target.id)
            return@LaunchedEffect
        }
        delay(500)
        DraftStore.save(context, target.sourceServerId, target.id, sendDraft)
    }

    // 展开态刻意提在这里（而不住在 ChannelSendFab 里）：长按「回复」时要在别的视图里
    // 把它打开，子组件自己拿着的状态外部够不着
    var sendExpanded by remember(sendTarget?.id) { mutableStateOf(false) }

    // 待应用的回复：长按「回复」时先记下来，等发送目标就位后再套进草稿。
    // 刻意**不**被 sendTarget?.id 重置 —— 下面第 1 步会让草稿重置，先记下来才不被冲掉
    var pendingReply by remember { mutableStateOf<PendingReply?>(null) }

    // 跳转过来的那条消息高亮一会儿，让用户知道"就是这条"
    //
    // 拆成两个 effect 是有意的：pendingScrollToMessageId 在滚动完成后会被重置成 -1，
    // 若把"设置高亮 + 延时清除"写在一个 effect 里，那次重置会**取消延时**，
    // 高亮就永远留在屏幕上了
    var highlightedMessageId by remember { mutableStateOf(-1L) }
    LaunchedEffect(pendingScrollToMessageId) {
        if (pendingScrollToMessageId > 0) highlightedMessageId = pendingScrollToMessageId
    }
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId > 0) {
            delay(3000)
            highlightedMessageId = -1L
        }
    }

    // 引用分两步落地：
    //   1) 先把发送目标指到回复所在的频道（已经在该频道视图里时天然成立）
    //   2) 目标就位后再套引用、展开输入框
    // 不能合成一步 —— 第 1 步会改变 sendTarget，而草稿是 remember(sendTarget?.id) 的、
    // 会随之重置，同一帧里写进去的草稿会被冲掉。所以第 1 步先 return，等 key 变了再回来
    LaunchedEffect(sendTarget?.id, pendingReply) {
        val reply = pendingReply ?: return@LaunchedEffect
        val channel = drawerChannels.firstOrNull { it.id == reply.channelId }
        if (channel == null) {
            pendingReply = null
            return@LaunchedEffect
        }
        if (sendTarget?.id != channel.id) {
            composeTarget = channel
            return@LaunchedEffect
        }
        sendDraft = sendDraft.copy(
            replyToRemoteId = reply.remoteId,
            replyToText = reply.text
        )
        sendExpanded = true
        pendingReply = null
    }

    // 附件上传：先落到目标频道所在的那台服务器，成功后输入框上方出现预览条，
    // 用户可配一句话再发送（正文里那段链接由 sendChannelMessage 拼）
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 用 sendTarget 而不是 sendChannel：原地回复时 sendChannel 是 null，
        // 那样附件会静默不上传
        val target = sendTarget
        if (uri != null && target != null) {
            uploadingAttachment = true
            viewModel.uploadAttachment(target.sourceServerId, uri) { result ->
                uploadingAttachment = false
                when (result) {
                    is AttachmentUploadResult.Ok -> {
                        // 只记下来、让预览条出现即可。正文里那段链接由 sendChannelMessage
                        // 统一拼（markdown 形式）—— 服务端自带的 WebUI 只在正文上跑
                        // marked，正文里没有链接它就渲染不出图片
                        sendDraft = sendDraft.copy(attachment = result.attachment)
                    }
                    AttachmentUploadResult.TooLarge -> Toast.makeText(
                        context, "文件太大（上限 20MB）", Toast.LENGTH_SHORT
                    ).show()
                    AttachmentUploadResult.Unreadable -> Toast.makeText(
                        context, "无法读取该文件", Toast.LENGTH_SHORT
                    ).show()
                    AttachmentUploadResult.Failed -> Toast.makeText(
                        context, "上传失败，请检查网络", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // 频道级「通知设置」弹窗目标
    var channelDndTarget by remember { mutableStateOf<ChatzChannel?>(null) }

    // 发现频道弹窗
    var showDiscover by remember { mutableStateOf(false) }
    var discoverChannels by remember { mutableStateOf<List<ChatzChannel>>(emptyList()) }
    var discoverLoading by remember { mutableStateOf(false) }
    var discoverFailed by remember { mutableStateOf(false) }
    var discoverPendingId by remember { mutableStateOf<Int?>(null) }
    // 点订阅「设了密码」的频道时，先弹密码框、输入后再订阅
    var passwordPromptTarget by remember { mutableStateOf<ChatzChannel?>(null) }
    // 发现频道搜索关键词（空 = 全量）
    var discoverSearchQuery by remember { mutableStateOf("") }

    var topBarVisible by remember { mutableStateOf(true) }

    // 打开发现页 / 操作完成后重新拉取列表；q 非空 = 按名字/ID 搜索
    val loadDiscover: (String?) -> Unit = { q ->
        discoverLoading = true
        discoverFailed = false
        scope.launch {
            val list = viewModel.loadDiscoverChannels(q = q)
            discoverChannels = list
            discoverFailed = list.isEmpty()
            discoverLoading = false
        }
    }

    // 搜索防抖：输入停下 300ms 后再拉；空关键词直接全量
    LaunchedEffect(discoverSearchQuery) {
        if (!showDiscover) return@LaunchedEffect
        val kw = discoverSearchQuery.trim()
        if (kw.isNotEmpty()) delay(300)
        loadDiscover(kw.takeIf { it.isNotEmpty() })
    }

    val currentSearchExpanding by rememberUpdatedState(isSearchExpanding)
    val currentConfigured by rememberUpdatedState(isConfigured)

    val topBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (currentSearchExpanding || !currentConfigured) return Offset.Zero

                val dy = available.y
                if (dy < -5f) {
                    topBarVisible = false
                } else if (dy > 5f) {
                    topBarVisible = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            if (isSearchExpanding) onSearchExpandingChange(false)
            // 每次打开抽屉都重拉应用列表：服务端新建应用后不必重启 App
            onDrawerOpened()
            // 频道同理：抽屉里的频道行来自 Room 响应式 flow，只认本地 DB，
            // 而服务端的变更靠 WS 事件送达（App 在后台时会丢）。打开抽屉补拉一次，
            // 新建 / 改名 / 换图标的频道立刻可见，不必杀进程。
            viewModel.refreshChannelList()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = isSearchExpanding && !drawerState.isOpen) {
        onSearchExpandingChange(false)
    }

    LaunchedEffect(isSearchExpanding, isConfigured) {
        if (isSearchExpanding || !isConfigured) {
            topBarVisible = true
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
            .distinctUntilChanged()
            .collect { atTop ->
                if (atTop) topBarVisible = true
            }
    }

    val listProgress = remember { Animatable(1f) }
    var isFirstComposition by remember { mutableStateOf(true) }

    LaunchedEffect(currentFilterAppId, drawerCurrentChannelId) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        listProgress.snapTo(0f)
        listProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(280.dp),
                    drawerShape = RoundedCornerShape(bottomEnd = 16.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.background
                ) {
                    DrawerContent(
                        serverText = drawerServerText,
                        servers = servers,
                        anyServerIsChatz = drawerAnyChatz,
                        currentFilterAppId = currentFilterAppId,
                        currentChannelId = drawerCurrentChannelId,
                        dndEnabled = drawerDndEnabled,
                        drawerCounts = drawerCounts,
                        onSelectFilter = { id ->
                            selectFilter(id)
                            scope.launch { drawerState.close() }
                        },
                        onSelectChannel = { id ->
                            selectChannel(id)
                            scope.launch { drawerState.close() }
                        },
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                        onClearMessages = {
                            scope.launch { drawerState.close() }
                            onClearMessages()
                        },
                        onMarkAllRead = {                // ← 新增
                            scope.launch { drawerState.close() }
                            onMarkAllRead()
                        },
                        onLongPressChannel = { ch ->
                            scope.launch { drawerState.close() }
                            channelMenuTarget = ch
                        },
                        onOpenDiscover = {
                            scope.launch { drawerState.close() }
                            showDiscover = true
                            discoverSearchQuery = ""
                            loadDiscover(null)
                        },
                        onDndToggle = onDndToggle,
                        // 展开/收起分组不关抽屉：要连着点几个分组
                        onToggleServer = onToggleServer,
                        onLongPressApp = { app ->
                            scope.launch { drawerState.close() }
                            appConfigTarget = app
                        }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    AnimatedVisibility(
                        visible = topBarVisible,
                        enter = expandVertically(
                            animationSpec = tween(200),
                            expandFrom = Alignment.Top
                        ) + fadeIn(tween(200)),
                        exit = shrinkVertically(
                            animationSpec = tween(200),
                            shrinkTowards = Alignment.Top
                        ) + fadeOut(tween(150))
                    ) {
                        MainTopBar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            isSearchExpanding = isSearchExpanding,
                            onSearchExpandingChange = onSearchExpandingChange,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            // 直接透传 MainScreen 的入参：toggleTheme 的实现（改主题 + 存
                            // 偏好）在 MainActivity 里，MainScreen 不持有它
                            onToggleTheme = onToggleTheme
                        )
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .nestedScroll(topBarScrollConnection)
                ) {
                    if (!isConfigured) {
                        NotConfiguredScreen(onGoSettings = onGoSettings)
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = listProgress.value
                                    scaleX = 0.85f + 0.15f * p
                                    scaleY = 0.85f + 0.15f * p
                                    translationY = 32f * (1f - p)
                                }
                        ) {
                            MessageListScreen(
                                viewModel = viewModel,
                                appId = currentFilterAppId,
                                // 悬浮发送入口浮在列表之上（不挤压列表），所以给它留一段
                                // 底部滚动留白，免得最后一条消息被那个按钮永久盖住
                                extraBottomPadding =
                                    if (isConfigured && sendTarget != null) 80.dp else 0.dp,
                                searchQuery = searchQuery,
                                imageAsBackground = imageAsBackground,
                                markwon = markwon,
                                isRefreshing = isRefreshing,
                                onRefresh = onRefresh,
                                isCardDraggingState = isCardDraggingState,
                                isListAtTopState = isListAtTopState,
                                isLongPressReadyState = isLongPressReadyState,
                                listState = listState,
                                onImageClick = onImageClick,
                                onUrlClick = onUrlClick,
                                onLongPress = { msg -> actionSheetMessage = msg },
                                onQuoteClick = onQuoteClick,
                                highlightedMessageId = highlightedMessageId,
                                pendingScrollToMessageId = pendingScrollToMessageId,
                                onScrollCompleted = onScrollCompleted
                            )
                        }
                    }
                }
            }
        }

        // ===== 悬浮发消息入口（左下角） =====
        //
        // 刻意不挂在 Scaffold.bottomBar 上：bottomBar 会把消息列表**挤压**上去（观感上
        // "底部被吃掉一块"）。这里作为外层 Box 的叠层浮在列表之上，列表高度不变。
        //
        // 出现条件 = 有明确的发送目标。两种来源（见上面的 sendTarget）：
        //   - 当前筛选就是某个频道
        //   - 在别的视图里长按「回复」→ 临时挂上一个目标（原地回复，不切走列表）
        // 抽屉打开时整块收起：这一层浮在 Scaffold 之上，而抽屉的点击遮罩只铺右侧
        // （见下面 padding(start = 280.dp) 的那个 Box），盖不住左下角 ——
        // 不收起的话它会一直浮在抽屉面板上，还能点。
        val drawerOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
        if (isConfigured && sendTarget != null && !drawerOpen) {
            ChannelSendFab(
                channelId = sendTarget.id,
                channelName = sendTarget.name,
                draft = sendDraft,
                onDraftChange = { sendDraft = it },
                expanded = sendExpanded,
                onExpandedChange = { want ->
                    sendExpanded = want
                    // 收起即放弃这次"原地回复"的临时目标，否则「所有消息」里会留下一个
                    // 只能发到某个频道的悬浮钮（草稿会随 sendTarget 变化自动重置）
                    if (!want) composeTarget = null
                },
                uploading = uploadingAttachment,
                sending = sendingMessage,
                onPickFile = { attachmentPicker.launch(arrayOf("*/*")) },
                onCancelUpload = {
                    viewModel.cancelUpload()
                    // 被取消的那次上传不会再回调 onDone，所以这里自己把状态收回去
                    uploadingAttachment = false
                },
                onOpenOptions = { showSendOptions = true },
                onSend = {
                    sendingMessage = true
                    val draft = sendDraft
                    viewModel.sendChannelMessage(sendTarget, draft) { result ->
                        sendingMessage = false
                        when {
                            result == null -> Toast.makeText(
                                context,
                                "发送失败，请检查网络",
                                Toast.LENGTH_SHORT
                            ).show()

                            result.dropped -> Toast.makeText(
                                context,
                                "消息被路由规则丢弃了",
                                Toast.LENGTH_SHORT
                            ).show()

                            // 成功了才清草稿：失败时保留正文/附件/选项，免得白填一遍
                            else -> {
                                sendDraft = OutgoingMessage()
                                // 发完要把存的那份草稿也清掉：上面那个防抖保存会因为
                                // sendTarget 变成 null 而提前返回，来不及清
                                DraftStore.clear(
                                    context, sendTarget.sourceServerId, sendTarget.id
                                )
                                // 原地回复发完就撤销临时目标，输入框随之收起。
                                // 在频道视图里 composeTarget 本来就是 null，这句是空操作
                                composeTarget = null
                            }
                        }
                    }
                }
            )
        }

        // ===== 发送选项（标题 / 优先级 / 标签） =====
        if (showSendOptions) {
            SendOptionsDialog(
                initial = sendDraft,
                onDismiss = { showSendOptions = false },
                onConfirm = { title, priority, tags ->
                    sendDraft = sendDraft.copy(title = title, priority = priority, tags = tags)
                    showSendOptions = false
                }
            )
        }

        if (drawerState.targetValue == DrawerValue.Open) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 280.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            scope.launch { drawerState.close() }
                        }
                    }
            )
        }

        // 长按菜单里的「回复」：只有能定位到频道时才提供（发送必须有明确的频道目标）。
        // 频道不在抽屉可见列表里（比如已退订）就不显示这一项。
        // 抽成带显式类型的局部变量，免得 if-else 里返回 null / lambda 时类型推断出错
        val replyAction: ((GotifyMessage) -> Unit)? = actionSheetMessage
            ?.let { m -> drawerChannels.firstOrNull { it.id == m.channelId }?.let { ch -> m to ch } }
            ?.let { (m, ch) ->
                { _: GotifyMessage ->
                    actionSheetMessage = null
                    // 只记下来：由上面的 LaunchedEffect 决定是"直接套进草稿"（已经在
                    // 这个频道里）还是"挂个临时发送目标原地回复"（在别的视图里）。
                    // 两种都不切走当前列表
                    pendingReply = PendingReply(
                        channelId = ch.id,
                        // 出站用远程 id：本地 id 高位带了 slot
                        remoteId = LocalIds.remoteIdOf(m.id),
                        text = replyPreview(m)
                    )
                }
            }

        MessageActionSheet(
            message = actionSheetMessage,
            onDismiss = { actionSheetMessage = null },
            onCopyContent = { msg -> onLongPress(msg) },
            onCopyTitle = { msg ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Gotify标题", msg.title ?: "")
                )
                Toast.makeText(context, "已复制标题", Toast.LENGTH_SHORT).show()
            },
            onCopyBody = { msg ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Gotify正文", msg.message)
                )
                Toast.makeText(context, "已复制正文", Toast.LENGTH_SHORT).show()
            },
            onToggleRead = { msg ->
                if (msg.isRead) {
                    viewModel.markUnread(msg)
                } else {
                    viewModel.markRead(msg)
                }
                actionSheetMessage = null
            },
            // 只有能定位到频道时才提供「回复」，由上面算好的 replyAction 决定
            onReply = replyAction
        )

        appConfigTarget?.let { app ->
            AppDndConfigDialog(
                serverId = app.serverId,
                appId = app.appId,
                title = app.name,
                onDismiss = { appConfigTarget = null }
            )
        }

        // ===== 频道级通知设置（Chatz：按频道配勿扰 / 铃声 / 合并） =====
        channelDndTarget?.let { ch ->
            AppDndConfigDialog(
                serverId = ch.sourceServerId,
                channelId = ch.id,
                title = ch.name,
                onDismiss = { channelDndTarget = null }
            )
        }

        // ===== 频道长按菜单：静音 / 取消订阅 =====
        channelMenuTarget?.let { ch ->
            // 「管理频道」只在有权限时显示（用户管自己创建的，超管管全部），
            // 与网页端一致 —— 无权限不显示，而不是点了再提示
            val canManage = viewModel.canManageChannel(ch)
            ChannelMenuSheet(
                channel = ch,
                onMarkAllRead = {
                    channelMenuTarget = null
                    viewModel.markAllRead(ch)
                    Toast.makeText(context, "该频道已全部标为已读", Toast.LENGTH_SHORT).show()
                },
                onOpenDndSettings = {
                    channelMenuTarget = null
                    channelDndTarget = ch
                },
                onToggleMute = {
                    channelMenuTarget = null
                    viewModel.setChannelMuted(ch, !ch.muted) { ok ->
                        if (!ok) {
                            Toast.makeText(
                                context,
                                "操作失败，请检查网络",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onManage = if (canManage) {
                    {
                        channelMenuTarget = null
                        channelManageTarget = ch
                    }
                } else null,
                onUnsubscribe = {
                    channelMenuTarget = null
                    viewModel.unsubscribeChannel(ch) { ok ->
                        if (!ok) {
                            Toast.makeText(
                                context,
                                "退订失败，请检查网络",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    // 退订的正好是当前打开的频道 → 回到"所有消息"
                    // （走 selectFilter：顺带撤销可能挂在它上面的临时发送目标）
                    if (drawerCurrentChannelId == ch.id) {
                        selectFilter(-1)
                    }
                },
                onDismiss = { channelMenuTarget = null }
            )
        }

        // ===== 管理频道（改名 / 描述 / 公开性 / 图标）=====
        channelManageTarget?.let { ch ->
            val cfg = ServerConfigStore.byId(context, ch.sourceServerId)
            ChannelManageDialog(
                serverUrl = cfg?.url.orEmpty(),
                token = cfg?.token.orEmpty(),
                channel = ch,
                onDismiss = { channelManageTarget = null },
                onSaved = {
                    channelManageTarget = null
                    // 抽屉里的频道行来自本地 DB 的响应式流，主动补拉一次让它立刻更新
                    viewModel.refreshChannelList()
                }
            )
        }

        // ===== 发现频道 =====
        if (showDiscover) {
            ChannelDiscoverDialog(
                channels = discoverChannels,
                loading = discoverLoading,
                failed = discoverFailed,
                pendingId = discoverPendingId,
                searchQuery = discoverSearchQuery,
                onSearchChange = { discoverSearchQuery = it },
                onToggleSubscribe = { ch ->
                    discoverPendingId = ch.id
                    if (ch.subscribed) {
                        viewModel.unsubscribeChannel(ch) { ok ->
                            discoverPendingId = null
                            if (ok) {
                                loadDiscover(discoverSearchQuery.takeIf { it.isNotBlank() })
                            } else {
                                Toast.makeText(context, "退订失败，请检查网络", Toast.LENGTH_SHORT).show()
                            }
                            // 退订的正好是当前打开的频道 → 回到"所有消息"
                            // （走 selectFilter：顺带撤销可能挂在它上面的临时发送目标）
                            if (drawerCurrentChannelId == ch.id) {
                                selectFilter(-1)
                            }
                        }
                    } else if (ch.passwordProtected && !viewModel.canManageChannel(ch)) {
                        // 受保护频道 + 非创建者/超管：先要密码再订阅
                        discoverPendingId = null
                        passwordPromptTarget = ch
                    } else {
                        // 无密码，或创建者/超管（服务端免密）：直接订阅
                        viewModel.subscribeChannel(ch) { result ->
                            discoverPendingId = null
                            if (result is ChannelSubscribeResult.Success) {
                                loadDiscover(discoverSearchQuery.takeIf { it.isNotBlank() })
                            } else {
                                Toast.makeText(context, "订阅失败，请检查网络", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDismiss = { showDiscover = false }
            )
        }

        // ===== 受保护频道的密码输入 =====
        passwordPromptTarget?.let { ch ->
            ChannelPasswordDialog(
                channel = ch,
                subscribe = { pwd, onResult ->
                    viewModel.subscribeChannel(ch, pwd) { result ->
                        if (result is ChannelSubscribeResult.Success) {
                            loadDiscover(discoverSearchQuery.takeIf { it.isNotBlank() })
                        }
                        onResult(result)
                    }
                },
                onDismiss = { passwordPromptTarget = null }
            )
        }
    }
}

@Composable
private fun NotConfiguredScreen(onGoSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "请先前往「设置」配置服务器",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGoSettings) {
                Text("去设置")
            }
        }
    }
}

/**
 * 悬浮发消息入口（左下角）
 *
 * 收起态只是一个圆形按钮（几乎不占地方），点开才展开成「附件 + 输入框 + 发送」；
 * 展开时铺一层透明层，点输入框以外的任何位置就收起（与聊天类应用的直觉一致）。
 *
 * 草稿 / 待发送附件 / 发送中 / 上传中这些状态都提在 MainScreen：
 * 附件上传完要把地址写回输入框，发送又要同时拿到草稿和附件，两边都在那边手里最省事。
 *
 * 发送成功后服务端会经 WS 广播回本机，所以这里不做本地插入；发送失败、或消息被
 * 路由规则丢弃时**保留输入内容和附件**，免得用户白打一遍。
 */
@Composable
private fun ChannelSendFab(
    channelId: Int,
    channelName: String,
    draft: OutgoingMessage,
    onDraftChange: (OutgoingMessage) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    uploading: Boolean,
    sending: Boolean,
    onPickFile: () -> Unit,
    onCancelUpload: () -> Unit,
    onOpenOptions: () -> Unit,
    onSend: () -> Unit
) {
    // 展开态由 MainScreen 持有（长按「回复」需要在外部打开它），这里只负责展示
    val focusRequester = remember(channelId) { FocusRequester() }

    // 展开就自动聚焦：否则用户点开按钮后还要再点一下输入框才能打字
    LaunchedEffect(expanded) {
        if (expanded) runCatching { focusRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 点空白收起。只在展开态铺开；空 Box 没有 pointerInput 时不消费触摸，
        // 所以不会挡住下面的列表
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onExpandedChange(false) }
                    }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        ) {
            val canSend = (draft.text.isNotBlank() || draft.attachment != null) && !sending
            // 标题/优先级/标签被设置过 → 「⋯」点亮，提示这条不是纯文本
            val hasOptions = !draft.title.isNullOrBlank() ||
                    draft.priority != null || draft.tags.isNotEmpty()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 不透明底 + 阴影：BasicTextField 没有自己的容器色，
                    // 直接浮在消息卡片上会看不清。
                    // 圆角 16dp 与收起态那个 M3 FAB 的默认形状对齐
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                draft.attachment?.let { att ->
                    AttachmentPreview(
                        attachment = att,
                        onClear = { onDraftChange(draft.copy(attachment = null)) }
                    )
                }

                // 回复预览：让用户看清自己正在回复哪条，并可取消
                if (draft.isReply) {
                    ReplyDraftStrip(
                        text = draft.replyToText.orEmpty(),
                        onClear = {
                            onDraftChange(
                                draft.copy(replyToRemoteId = null, replyToText = null)
                            )
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 上传中它变成"取消"按钮（转圈即上传中）；空态是"添加附件"。
                    // 20MB 走移动网络可能要等很久，不给取消只能干等
                    CompactIconButton(
                        onClick = { if (uploading) onCancelUpload() else onPickFile() }
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加附件",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 用 BasicTextField 而不是 OutlinedTextField：后者自带 56dp 最小高度
                    // 加一圈边框，在这么小的浮层里既占地方、又和面板边框重复
                    BasicTextField(
                        value = draft.text,
                        onValueChange = { onDraftChange(draft.copy(text = it)) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 40.dp)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (draft.text.isEmpty()) {
                                    Text(
                                        text = "发送到 #$channelName",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme
                                            .onSurface.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )

                    // 「⋯」：标题 / 优先级 / 标签（塞进弹窗，不撑大这条）
                    CompactIconButton(onClick = onOpenOptions) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "消息选项",
                            tint = if (hasOptions) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    CompactIconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "发送",
                            // 只发附件、不写字也要能发（正文由 ViewModel 用附件的
                            // markdown 链接兜底）
                            tint = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                // 14dp：和展开面板的左边缘（10dp 外边距 + 4dp 内边距）对齐
                .padding(start = 14.dp, bottom = 14.dp)
        ) {
            FloatingActionButton(
                onClick = { onExpandedChange(true) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Send, contentDescription = "发消息")
            }
        }
    }
}

/**
 * 待应用的回复（长按消息 → 回复）
 *
 * [remoteId] 是**远程** id —— extras 里的 reply_to 用远程 id（出站口径），
 * 本地 id 只在进程内用，两者别混。
 */
private data class PendingReply(val channelId: Int, val remoteId: Long, val text: String)

/**
 * 引用摘要
 *
 * 剥图 + 截断 120 字，与通知栏回复（GotifyService.replyAction）走**同一个实现**
 * （MessageText），免得同一句话从两条路发出去、引用内容却不一样长。
 * 摘要随消息一起存到服务端，所以原消息被裁剪了别的设备也还看得见。
 */
private fun replyPreview(msg: GotifyMessage): String = MessageText.preview(msg.message, 120)

/**
 * 发送栏里的回复预览
 *
 * 与卡片上的引用条同一个视觉（左侧竖线 + 摘要），多一个取消按钮。
 */
@Composable
private fun ReplyDraftStrip(text: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (text.isBlank()) "回复" else text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        CompactIconButton(onClick = onClear, size = 28.dp) {
            Icon(
                Icons.Default.Close,
                contentDescription = "取消回复",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 待发送附件的预览条
 *
 * 显示在输入框上方。此时附件**已经上传完成**（消息里那个 URL 就是它），
 * 这条只是给用户一个"带了什么"的确认和移除入口。
 */
@Composable
private fun AttachmentPreview(
    attachment: UploadedAttachment,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (attachment.isImage) "图片" else "文件",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = attachment.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        CompactIconButton(onClick = onClear, size = 28.dp) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除附件",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 紧凑图标按钮
 *
 * 不能用 M3 的 IconButton：它内部有 minimumInteractiveComponentSize()，
 * 外面传多小的 size 都会被撑到 48dp —— 在发送浮层这条细栏里塞不下。
 * 这里是自绘的圆形触控区（默认 40dp，仍接近无障碍建议的 48dp）。
 */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 发送选项（标题 / 优先级 / 标签）
 *
 * 单独放弹窗、不塞进发送栏：那条刚被压到最紧凑，再塞两个输入框就撑回去了。
 * 优先级默认 5（= 服务端默认），8~10 会穿透频道静音照常弹通知。
 */
@Composable
private fun SendOptionsDialog(
    initial: OutgoingMessage,
    onDismiss: () -> Unit,
    onConfirm: (title: String?, priority: Int?, tags: List<String>) -> Unit
) {
    var title by remember { mutableStateOf(initial.title.orEmpty()) }
    var priority by remember { mutableStateOf((initial.priority ?: 5).toFloat()) }
    var tagsText by remember { mutableStateOf(initial.tags.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息选项") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    singleLine = true
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "优先级 ${priority.roundToInt()}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = priority,
                    onValueChange = { priority = it.roundToInt().toFloat() },
                    valueRange = 0f..10f,
                    steps = 9
                )
                Text(
                    text = "8~10 会穿透频道静音，照常弹通知",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标签（逗号分隔，最多 20 个）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        title.trim().takeIf { it.isNotEmpty() },
                        priority.roundToInt(),
                        tagsText.split(',', '，', '、', ' ')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .take(20)
                    )
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}