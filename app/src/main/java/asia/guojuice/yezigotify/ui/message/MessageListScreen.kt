package asia.guojuice.yezigotify.ui.message

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.MessageViewModel
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.utils.ThemeColorManager
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.isSystemInDarkTheme
import asia.guojuice.yezigotify.utils.AppThemeState

private const val OVERRIDE_TTL_MS = 1_500L
private const val OVERRIDE_SWEEP_INTERVAL_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    viewModel: MessageViewModel,
    appId: Int,
    /**
     * 列表底部的额外滚动留白
     *
     * 悬浮发送入口是**叠在列表之上**的（不挤压列表），所以要把这段留白让出来，
     * 否则滚到最后一条时它会被那个按钮永久盖住。没有发送入口时传 0。
     */
    extraBottomPadding: Dp = 0.dp,
    searchQuery: String,
    imageAsBackground: Boolean,
    markwon: Markwon,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isCardDraggingState: MutableState<Boolean>,
    isListAtTopState: MutableState<Boolean>,
    isLongPressReadyState: MutableState<Boolean>,
    listState: LazyListState = rememberLazyListState(),
    onImageClick: (String, Bitmap?) -> Unit = { _, _ -> },
    onUrlClick: (String) -> Unit = {},
    onLongPress: (GotifyMessage) -> Unit = {},
    /** 点击引用条：跳转到被回复的消息（serverId, 远程 id） */
    onQuoteClick: (String, Long) -> Unit = { _, _ -> },
    /** 刚跳转过来的那条消息（本地 id）会被高亮几秒，-1 = 无 */
    highlightedMessageId: Long = -1L,
    pendingScrollToMessageId: Long = -1L,
    onScrollCompleted: () -> Unit = {}
) {
    // 频道列表（用于消息卡片图标选择：频道图标 or 首字母）
    val channelList by viewModel.channelsFlow.collectAsState(initial = emptyList())
    val channelMap = remember(channelList) {
        channelList.associateBy { it.id }
    }

    val pagedItems = viewModel.messagesFlow.collectAsLazyPagingItems()

    // 服务端推来「全部已读」等"数据变了但当前页看不出变化"的操作时，强制重拉列表。
    //
    // 为什么必须显式 refresh：Paging 只在结果集**真的变了**时才重绘，
    // 而服务端的全部已读是全库标记，本地当前页那几条往往本来就已读 ⇒
    // 重拉后数据一致 ⇒ 不重绘（详见 ViewModel 里 listRefreshTick 的注释）。
    // 用 first 跳过初始值 0，否则进页面会白白多拉一次。
    val listRefreshTick by viewModel.listRefreshTick.collectAsState()
    LaunchedEffect(listRefreshTick) {
        if (listRefreshTick > 0L) pagedItems.refresh()
    }

    // 当前标签筛选（点卡片上的标签 chip 后非空）
    // 捕获成局部 val：委托属性（by collectAsState）无法智能转换，且避免重复读取
    val tagFilter = viewModel.currentTag.collectAsState().value

    // ── 筛选生效时把列表滚回顶部 ──────────────────────────────
    // 现在横幅已经移到列表**外面**（固定头），不管滚到哪儿都看得见了；
    // 这里滚回顶部是为了让你从**第一条**筛选结果开始看 ——
    // 否则结果是新的、滚动位置还是旧的，很可能停在半空中像是没东西。
    //
    // 只在筛选**变为非空**时滚；清除筛选（变 null）不动，免得打断正在看的位置。
    LaunchedEffect(tagFilter) {
        if (tagFilter != null) {
            listState.animateScrollToItem(0)
        }
    }

    var searchResults by remember { mutableStateOf<List<GotifyMessage>?>(null) }

    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(250)
            debouncedQuery = searchQuery
        }
    }

    LaunchedEffect(debouncedQuery) {
        searchResults = if (debouncedQuery.isBlank()) {
            null
        } else {
            viewModel.searchMessages(debouncedQuery)
        }
    }

    val itemCount: Int = searchResults?.size ?: pagedItems.itemCount
    val getItem: (Int) -> GotifyMessage? = { index ->
        val sr = searchResults
        if (sr != null) {
            sr.getOrNull(index)
        } else {
            if (index in 0 until pagedItems.itemCount) pagedItems.peek(index) else null
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val deletingIds = remember { mutableStateMapOf<Long, Unit>() }

    val starredOverride = remember {
        mutableStateMapOf<Long, Pair<Boolean, Long>>()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(OVERRIDE_SWEEP_INTERVAL_MS)
            val cutoff = System.currentTimeMillis() - OVERRIDE_TTL_MS
            val stale = starredOverride.entries
                .filter { it.value.second < cutoff }
                .map { it.key }
            stale.forEach { starredOverride.remove(it) }
        }
    }

    val nowMinuteState = remember { mutableLongStateOf(System.currentTimeMillis()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMinuteState.longValue = System.currentTimeMillis()
                val elapsed = System.currentTimeMillis() % 60_000L
                delay(60_000L - elapsed)
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.collect { atTop ->
            isListAtTopState.value = atTop
        }
    }

    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    var lastFirstId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(listState, searchResults) {
        if (searchResults != null) return@LaunchedEffect
        snapshotFlow {
            if (pagedItems.itemCount > 0) pagedItems.peek(0)?.id else null
        }
            .distinctUntilChanged()
            .collect { newId ->
                val oldId = lastFirstId
                lastFirstId = newId
                if (oldId != null && newId != null && newId != oldId) {
                    if (listState.firstVisibleItemIndex <= 2) {
                        runCatching { listState.animateScrollToItem(0) }
                    }
                }
            }
    }

    //  从通知跳转到指定消息（修复远距离 index 无法跳转的问题）
    LaunchedEffect(pendingScrollToMessageId, searchResults) {
        if (pendingScrollToMessageId == -1L) return@LaunchedEffect
        if (searchResults != null) return@LaunchedEffect

        val hasContent = withTimeoutOrNull(3000L) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 0 }
        }
        if (hasContent == null) {
            onScrollCompleted()
            return@LaunchedEffect
        }

        val index = viewModel.getMessageIndex(pendingScrollToMessageId, appId)
        if (index == null || index < 0) {
            onScrollCompleted()
            return@LaunchedEffect
        }

        val currentLoadedCount = listState.layoutInfo.totalItemsCount

        if (index < currentLoadedCount) {
            runCatching { listState.animateScrollToItem(index) }
        } else {
            runCatching { listState.scrollToItem(index) }

            withTimeoutOrNull(2000L) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > index }
            }

            runCatching { listState.animateScrollToItem(index) }
        }

        onScrollCompleted()
    }

    // 列表整体位移补偿 + 回弹
    //
    // ⚠️ 这个位移是**叠在列表之上**的（见下面 LazyColumn 的 translationY），
    //    而 LazyColumn 又套了 clipToBounds()。所以只要 contentOffset 停在非 0 值，
    //    列表就整体偏移、底部被裁掉一块 —— 表现为"消息卡片下面被切了一半"。
    //
    // 曾经踩过的坑：切换 Token 会触发一次全量同步，itemCount 与 peek(0).id 会几乎
    // 同时变化，本 effect 被取消重启。上一次的 animateTo（spring 阻尼回弹，约 1 秒）
    // 被中断时值会**冻结在中间态**，而新的这次往往走 `if (!wasIncrease) return` 提前
    // 返回，没人再把它归零 → 偏移永久留在屏幕上。
    //
    // 因此这里强制保证：**任何一次 effect 重启都要回到 0**，只有真的要播回弹时
    // 才短暂离开 0。finally 再兜一次，异常/取消也不留下偏移。
    val contentOffset = remember { Animatable(0f) }
    var prevItemCount by remember { mutableStateOf(itemCount) }
    var lastTopId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(itemCount, searchResults) {
        // 进场先归零：清掉上一次被打断可能留下的残留偏移
        if (contentOffset.value != 0f) contentOffset.snapTo(0f)

        val currentTopId = if (searchResults == null && pagedItems.itemCount > 0) {
            pagedItems.peek(0)?.id
        } else {
            null
        }

        val topIdChanged = lastTopId != null &&
                currentTopId != null &&
                currentTopId != lastTopId

        val wasIncrease = searchResults == null &&
                itemCount > prevItemCount &&
                prevItemCount > 0 &&
                topIdChanged

        lastTopId = currentTopId
        prevItemCount = itemCount

        if (!wasIncrease) return@LaunchedEffect
        if (listState.firstVisibleItemIndex > 1) return@LaunchedEffect

        withFrameNanos { }

        val firstInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            ?: return@LaunchedEffect
        val newHeight = firstInfo.size
        if (newHeight <= 0) return@LaunchedEffect

        try {
            contentOffset.snapTo(-newHeight.toFloat())
            contentOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.5f
                )
            )
        } finally {
            // 被取消 / 抛异常时也要归零，否则偏移会永久留在列表上（底部被裁）
            if (contentOffset.value != 0f) contentOffset.snapTo(0f)
        }
    }

    val themeColor = Color(ThemeColorManager.getEffectiveColor())
    val pullIndicatorColor by animateColorAsState(
        targetValue = if (isLongPressReadyState.value) Color.Red else themeColor,
        animationSpec = if (isLongPressReadyState.value) {
            tween(1500)
        } else {
            tween(150)
        },
        label = "pullIndicatorColor"
    )

    val pullState = rememberPullToRefreshState()

    val handleDelete: (GotifyMessage) -> Unit = remember(viewModel) {
        { msg ->
            if (!deletingIds.containsKey(msg.id)) {
                deletingIds[msg.id] = Unit
                scope.launch {
                    viewModel.deleteLocal(msg)
                    delay(100)

                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Short
                    )

                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreMessage(msg)
                    } else {
                        if (msg.appid != 0) {
                            viewModel.deleteFromServer(msg)
                        }
                    }

                    deletingIds.remove(msg.id)
                }
            }
        }
    }

    val handleStar: (GotifyMessage) -> Unit = remember(viewModel, scope) {
        { msg ->
            starredOverride[msg.id] = true to System.currentTimeMillis()

            // ① 提示**立刻**弹 —— 必须排在下面那个 delay 前面。
            //
            //    ⚠️ 顺序很要紧，因为 `showSnackbar` 是**挂起函数**，会一直挂到提示自己
            //    消失（SnackbarDuration.Short ≈ 4s）。所以两个协程里的动作谁在前谁"先跑完"：
            //      · 提示写在 delay 后面 → 提示被推迟。**原来就是这样**：
            //        被下面那个用于清理乐观覆盖的 OVERRIDE_TTL_MS(1.5s) 连累，
            //        用户点完感觉"一两秒没反应"。
            //      · 提示写在 delay 前面 → 只把"清理覆盖"推迟，而清理只是删一个 map 项，
            //        而且还有每 30s 的 sweep 兜底（见 OVERRIDE_SWEEP_INTERVAL_MS）。
            //    显然该牺牲后者。
            //
            //    也不用等网络：toggleStar 内部就是「先写本地 → 推服务端 → 失败后台重试」，
            //    提示文案本来就不反映成功与否，等它没有意义。
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "已收藏",
                    duration = SnackbarDuration.Short
                )
            }

            // ② 真正的写入（内部已是乐观更新 + 失败自动重试）
            viewModel.toggleStar(msg, true)

            // ③ 乐观覆盖的清理：和提示无关，单独一个协程，别阻塞 UI
            scope.launch {
                delay(OVERRIDE_TTL_MS)
                val entry = starredOverride[msg.id]
                if (entry != null &&
                    entry.first == true &&
                    System.currentTimeMillis() - entry.second >= OVERRIDE_TTL_MS
                ) {
                    starredOverride.remove(msg.id)
                }
            }
        }
    }

    val handleUnstar: (GotifyMessage) -> Unit = remember(viewModel, scope) {
        { msg ->
            starredOverride[msg.id] = false to System.currentTimeMillis()

            // 同上：提示先弹，清理覆盖的 delay 放后面
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = "已取消收藏",
                    duration = SnackbarDuration.Short
                )
            }

            viewModel.toggleStar(msg, false)

            scope.launch {
                delay(OVERRIDE_TTL_MS)
                val entry = starredOverride[msg.id]
                if (entry != null &&
                    entry.first == false &&
                    System.currentTimeMillis() - entry.second >= OVERRIDE_TTL_MS
                ) {
                    starredOverride.remove(msg.id)
                }
            }
        }
    }

    val themeColorInt = ThemeColorManager.getEffectiveColor()

    val isDarkMode = if (AppThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AppThemeState.darkMode.value
    }

    val fabColorInt = if (isDarkMode) {
        ColorUtils.blendARGB(themeColorInt, android.graphics.Color.WHITE, 0.30f)
    } else {
        ColorUtils.blendARGB(themeColorInt, android.graphics.Color.BLACK, 0.30f)
    }
    val fabColor = Color(fabColorInt)
    val fabContentColor = Color(ThemeColorManager.contrastTextColor(fabColorInt))

    var lastScrollActivity by remember { mutableLongStateOf(0L) }
    var fabAutoHidden by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect {
            lastScrollActivity = System.currentTimeMillis()
        }
    }

    LaunchedEffect(lastScrollActivity, isAtTop, itemCount) {
        if (isAtTop || itemCount == 0) {
            fabAutoHidden = false
            return@LaunchedEffect
        }
        fabAutoHidden = false
        delay(3000)
        fabAutoHidden = true
    }

    val fabVisible = !isAtTop && itemCount > 0 && !fabAutoHidden

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var startX = 0f
                    var startY = 0f
                    var wasPressed = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue

                        if (change.pressed) {
                            if (!wasPressed) {
                                startX = change.position.x
                                startY = change.position.y
                                wasPressed = true
                            } else {
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY

                                val isVertical = abs(dy) > abs(dx) * 1.5f
                                val hasMoved = abs(dy) > 10f

                                if (isVertical && hasMoved) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastScrollActivity > 100) {
                                        lastScrollActivity = now
                                    }
                                }
                            }
                        } else {
                            wasPressed = false
                        }
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 标签筛选条：放在列表**外面**当固定头 =====
            // 不用 `stickyHeader`：那个 API 在 foundation 1.11.3（本项目 BOM 2026.06.00）
            // 里已经不存在了 —— 拆开 AAR 验过，LazyDslKt 只有 item / items / itemsIndexed。
            // 放在外面效果一样（永远可见），而且不依赖任何新 API。
            // ⚠️ 必须包 AnimatedVisibility：筛选条现在在列表**外面**，
            //    直接 if 显示会把整个列表「啪」地往下推一截、消失时又猛地弹回来，很突兀。
            //    展开折叠 + 淡入淡出才顺。
            //
            // ⚠️ lastTag 记住**最后一次的非空标签**：退出动画播放期间 tagFilter 已经是 null 了，
            //    要是直接用 tagFilter 渲染，动画就作用在一块空内容上 —— 等于白播。
            //    shownTag 用 `tagFilter ?: lastTag` 取，进入那一帧也不会闪一下空标题。
            var lastTag by remember { mutableStateOf("") }
            LaunchedEffect(tagFilter) {
                tagFilter?.let { lastTag = it }
            }
            val shownTag = tagFilter ?: lastTag

            AnimatedVisibility(
                visible = tagFilter != null,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                TagFilterBanner(
                    tag = shownTag,
                    onClear = { viewModel.setCurrentTag(null) }
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = pullState,
                // ⚠️ 外面套了 Column，这里不能再 fillMaxSize()，改用 weight 吃掉剩余高度
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        color = if (isRefreshing) themeColor else pullIndicatorColor
                    )
                }
            ) {
                LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = contentOffset.value
                    }
                    .clipToBounds(),
                contentPadding = PaddingValues(top = 4.dp, bottom = extraBottomPadding)
            ) {
                if (itemCount == 0) {
                    item(key = "empty_state") {
                        EmptyState(
                            appId = appId,
                            isSearching = searchResults != null,
                            tagFilter = tagFilter
                        )
                    }
                }
                items(
                    count = itemCount,
                    key = { index ->
                        val msg = getItem(index)
                        "${msg?.id ?: (-1L - index)}"
                    }
                ) { index ->
                    val msg = getItem(index)
                    if (msg != null) {
                        Box(
                            modifier = Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = tween(durationMillis = 250)
                            )
                        ) {
                            SwipeToDeleteCard(
                                msg = msg,
                                channels = channelMap,
                                starredOverride = starredOverride[msg.id]?.first,
                                imageAsBackground = imageAsBackground,
                                markwon = markwon,
                                nowMinuteState = nowMinuteState,
                                isCardDraggingState = isCardDraggingState,
                                isScrolling = { listState.isScrollInProgress },
                                onImageClick = onImageClick,
                                onUrlClick = onUrlClick,
                                onLongPress = onLongPress,
                                onQuoteClick = onQuoteClick,
                                highlighted = msg.id == highlightedMessageId,
                                onTagClick = { tag -> viewModel.setCurrentTag(tag) },
                                onDelete = handleDelete,
                                onStar = handleStar,
                                onUnstar = handleUnstar,
                                onToggleCollapse = {
                                    val oldInfo = listState.layoutInfo.visibleItemsInfo
                                        .find { it.index == index }
                                    val oldBottom = oldInfo?.let { it.offset + it.size }

                                    if (oldBottom != null) {
                                        scope.launch {
                                            var stableCount = 0
                                            var frameCount = 0

                                            while (frameCount < 60 && stableCount < 5) {
                                                withFrameNanos { }
                                                frameCount++

                                                val cardInfo = listState.layoutInfo.visibleItemsInfo
                                                    .find { it.index == index }

                                                if (cardInfo != null) {
                                                    val currentBottom =
                                                        cardInfo.offset + cardInfo.size
                                                    val delta = currentBottom - oldBottom
                                                    if (delta != 0) {
                                                        listState.scrollBy(delta.toFloat())
                                                        stableCount = 0
                                                    } else {
                                                        stableCount++
                                                    }
                                                } else {
                                                    val firstIndex = listState.firstVisibleItemIndex
                                                    if (index < firstIndex) {
                                                        listState.scrollBy(-200f)
                                                    } else {
                                                        listState.scrollBy(200f)
                                                    }
                                                    stableCount = 0
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }                // items(...) 块
            }                    // LazyColumn
            }                    // PullToRefreshBox
        }                        // Column（筛选条 + 列表）

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
        ) {
            AnimatedVisibility(
                visible = fabVisible,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.8f)
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val currentIndex = listState.firstVisibleItemIndex
                                val totalCount = listState.layoutInfo.totalItemsCount

                                if (currentIndex <= 5 || totalCount <= 6) {
                                    smoothScrollToTop(listState, durationMs = 400)
                                } else {
                                    listState.scrollToItem(5)
                                    smoothScrollToTop(listState, durationMs = 500)
                                }
                            }
                        }
                    },
                    containerColor = fabColor,
                    contentColor = fabContentColor
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "回到顶部"
                    )
                }
            }
        }
    }
}

/**
 * 标签筛选横幅
 *
 * 点消息卡片上的标签 chip 后出现在列表顶部，提示当前筛选条件并提供清除入口。
 *
 * ⚠️ **它现在在列表外面**（调用处把它放在 `LazyColumn` 之上当固定头），不随列表滚动。
 *    底色做成不透明的 `surface` + 12% 强调色，是为了以后万一改成吸顶/浮层也安全 ——
 *    那时它会压在滚动的消息上面，半透明会把卡片透出来（2026-10-02 踩过）。
 */
@Composable
private fun TagFilterBanner(tag: String, onClear: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(surface)
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "标签筛选：#$tag",
                fontSize = 13.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "清除",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(
    appId: Int,
    isSearching: Boolean,
    tagFilter: String? = null
) {
    // appId 是**合成筛选 id**（LocalIds.makeAppFilterId(slot, appid)），所以不能再用
    // `appId == 0` 判断 ntfy —— 只有 slot 0 的 ntfy 才会等于 0，第二台之后的 ntfy
    // 会落到 else 分支显示成"该应用还没有消息"。这里按合成 id 里的 slot 反查服务器类型。
    val context = LocalContext.current
    val isNtfyFilter = appId >= 0 && remember(appId) {
        ServerConfigStore.bySlot(context, LocalIds.slotOfAppFilter(appId))?.isNtfy == true
    }

    val (icon, title, subtitle) = when {
        isSearching -> Triple(
            Icons.Default.Search,
            "没有匹配的消息",
            "试试其他关键词"
        )
        tagFilter != null -> Triple(
            Icons.Default.Notifications,
            "没有带 #$tagFilter 标签的消息",
            "点上方横幅可取消筛选"
        )
        appId == -2 -> Triple(
            Icons.Filled.Star,
            "还没有收藏的消息",
            "左滑消息卡片即可收藏"
        )
        isNtfyFilter -> Triple(
            Icons.Default.Notifications,
            "该主题还没有消息",
            "等待 ntfy 推送"
        )
        appId == -1 -> Triple(
            Icons.Default.Notifications,
            "还没有收到消息",
            "下拉刷新试试"
        )
        appId == -3 -> Triple(
            Icons.Default.Notifications,
            "没有未读消息",
            "全部已读完"
        )
        else -> Triple(
            Icons.Default.Notifications,
            "该应用还没有消息",
            "换个 App 看看"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// ==================== 底部工具 ====================

private suspend fun smoothScrollToTop(
    listState: LazyListState,
    durationMs: Long = 500
) {
    val startIndex = listState.firstVisibleItemIndex
    val startOffset = listState.firstVisibleItemScrollOffset
    if (startIndex == 0 && startOffset == 0) return

    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val avgItemHeight = if (visibleItems.isNotEmpty()) {
        visibleItems.sumOf { it.size } / visibleItems.size
    } else {
        150
    }
    val remainingPx = (startIndex * avgItemHeight + startOffset).toFloat()

    val steps = 30
    val frameMs = durationMs / steps
    var lastScrollPx = 0f

    for (i in 1..steps) {
        val t = i.toFloat() / steps
        val eased = easeInOutQuad(t)
        val targetPx = remainingPx * eased
        val deltaPx = targetPx - lastScrollPx
        listState.scrollBy(-deltaPx)
        lastScrollPx = targetPx
        delay(frameMs)
    }

    listState.scrollToItem(0)
}

private fun easeInOutQuad(t: Float): Float {
    return if (t < 0.5f) {
        2f * t * t
    } else {
        1f - (-2f * t + 2f).let { it * it } / 2f
    }
}

@Composable
private fun SwipeToDeleteCard(
    msg: GotifyMessage,
    channels: Map<Int, ChatzChannel>,
    starredOverride: Boolean?,
    imageAsBackground: Boolean,
    markwon: Markwon,
    nowMinuteState: State<Long>,
    isCardDraggingState: MutableState<Boolean>,
    isScrolling: () -> Boolean,
    onImageClick: (String, Bitmap?) -> Unit,
    onUrlClick: (String) -> Unit,
    onLongPress: (GotifyMessage) -> Unit,
    onQuoteClick: (String, Long) -> Unit,
    /** 是否为"刚跳转过来的那条"（高亮几秒） */
    highlighted: Boolean,
    onTagClick: (String) -> Unit,
    onDelete: (GotifyMessage) -> Unit,
    onStar: (GotifyMessage) -> Unit,
    onUnstar: (GotifyMessage) -> Unit,
    onToggleCollapse: () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 120.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { 800.dp.toPx() } }
    val minDirectionPx = remember(density) { with(density) { 22.dp.toPx() } }
    val minHorizontalPx = remember(density) { with(density) { 30.dp.toPx() } }
    val horizontalRatio = 1.5f

    val triggerRatio = 0.7f
    val triggerPx = thresholdPx * triggerRatio

    val isStarred = starredOverride ?: msg.starred
    val canSwipeLeft = !isStarred

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var handled by remember(msg.id, isStarred) { mutableStateOf(false) }

    val leftProgress by remember {
        derivedStateOf { (-offsetX.value / thresholdPx).coerceIn(0f, 1f) }
    }
    val rightProgress by remember {
        derivedStateOf { (offsetX.value / thresholdPx).coerceIn(0f, 1f) }
    }

    val minOffsetX = if (canSwipeLeft) -maxDragPx else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(msg.id, isStarred) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y

                    var isDragging = false
                    var directionLocked = false

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue

                        if (change.pressed) {
                            val totalDx = change.position.x - startX
                            val totalDy = change.position.y - startY

                            if (!isDragging && !directionLocked) {
                                val absDx = abs(totalDx)
                                val absDy = abs(totalDy)

                                if (absDx < minDirectionPx && absDy < minDirectionPx) {
                                    // 位移不够，继续等
                                } else {
                                    val isHorizontal = absDx > absDy * horizontalRatio

                                    if (!isHorizontal) {
                                        directionLocked = true
                                    } else {
                                        if (isScrolling()) {
                                            directionLocked = true
                                        } else if (absDx > minHorizontalPx) {
                                            val goingLeft = totalDx < 0
                                            if (!goingLeft || canSwipeLeft) {
                                                isDragging = true
                                                isCardDraggingState.value = true
                                            } else {
                                                directionLocked = true
                                            }
                                        }
                                    }
                                }
                            }

                            if (isDragging) {
                                val dx = change.position.x - change.previousPosition.x
                                val newVal = (offsetX.value + dx)
                                    .coerceIn(minOffsetX, maxDragPx)
                                scope.launch { offsetX.snapTo(newVal) }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isDragging) {
                        isCardDraggingState.value = false

                        val currentOffset = offsetX.value
                        when {
                            currentOffset > triggerPx && !handled -> {
                                handled = true
                                if (isStarred) {
                                    onUnstar(msg)
                                    scope.launch {
                                        offsetX.animateTo(0f, spring(stiffness = 800f))
                                    }
                                } else {
                                    scope.launch {
                                        offsetX.animateTo(maxDragPx, tween(180))
                                        onDelete(msg)
                                    }
                                }
                            }
                            currentOffset < -triggerPx && !handled -> {
                                handled = true
                                onStar(msg)
                                scope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = 800f))
                                }
                            }
                            else -> {
                                scope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = 800f))
                                }
                            }
                        }
                    }
                }
            }
    ) {
        when {
            offsetX.value > 0f -> {
                val bgColor = if (isStarred) {
                    lerp(Color(0xFFBDBDBD), Color(0xFFFFC107), rightProgress)
                } else {
                    lerp(Color(0xFFBDBDBD), Color(0xFFF44336), rightProgress)
                }
                val reached = offsetX.value >= triggerPx
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .padding(start = 24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = when {
                            isStarred && reached -> "松手取消收藏"
                            isStarred -> "继续滑动"
                            reached -> "松手删除"
                            else -> "继续滑动"
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            offsetX.value < 0f -> {
                val bgColor = lerp(Color(0xFFBDBDBD), Color(0xFFFFC107), leftProgress)
                val reached = offsetX.value <= -triggerPx
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .padding(end = 24.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = if (reached) "松手收藏" else "继续滑动",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Box(
            modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
        ) {
            MessageCard(
                msg = if (starredOverride != null) msg.copy(starred = isStarred) else msg,
                imageAsBackground = imageAsBackground,
                markwon = markwon,
                nowMinuteState = nowMinuteState,
                channels = channels,
                isScrolling = isScrolling,
                onImageClick = onImageClick,
                onUrlClick = onUrlClick,
                onLongPress = { onLongPress(msg) },
                onQuoteClick = onQuoteClick,
                highlighted = highlighted,
                onTagClick = onTagClick,
                onToggleCollapse = onToggleCollapse
            )
        }
    }
}