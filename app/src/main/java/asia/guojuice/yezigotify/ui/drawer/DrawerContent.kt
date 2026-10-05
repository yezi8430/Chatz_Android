package asia.guojuice.yezigotify.ui.drawer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import asia.guojuice.yezigotify.DrawerCounts
import asia.guojuice.yezigotify.MessageViewModel
import asia.guojuice.yezigotify.R
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.settings.settingsSwitchColors
import asia.guojuice.yezigotify.utils.ThemeColorManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import asia.guojuice.yezigotify.ui.message.ImageBitmapCache
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 抽屉里的一个应用条目
 *
 * id 是**合成 filter id**（`LocalIds.makeAppFilterId(slot, appId)`），slot 0 时等于原始 appid；
 * appId 是服务端原始 appid（per-app 免打扰等本地配置用它）。
 */
data class DrawerApp(
    val id: Int,
    val appId: Int,
    val serverId: String,
    val name: String,
    val iconUrl: String? = null
)

/**
 * 抽屉里的一个服务器分组
 *
 * channels 直接复用 [ChatzChannel]：它已自带 id(本地 channelId) / serverId / remoteId /
 * muted / image，无需再定义一层会丢字段的包装类。
 */
data class DrawerServer(
    val serverId: String,
    val slot: Int,
    val label: String,
    val kindLabel: String,
    val connected: Boolean,
    val apps: List<DrawerApp>,
    val channels: List<ChatzChannel>,
    val ntfyTopic: String?,
    val totalCount: Int,
    /** 该分组是否展开（收起时只显示分组头）。状态由 MainActivity 持有并持久化 */
    val expanded: Boolean = true,
    /**
     * 该服务器是否有未读消息
     *
     * 只在"已订阅且在展示"的频道里判断：非 Chatz 服务器（Gotify / ntfy）没有已读概念，
     * 它们的消息不会进 `DrawerCounts.channelsWithUnread`，所以这里天然不会误亮。
     */
    val hasUnread: Boolean = false
)

@Composable
fun DrawerContent(
    serverText: String,
    servers: List<DrawerServer>,
    /** 任一服务器被判定为 Chatz 才显示「未读」入口与频道相关动作 */
    anyServerIsChatz: Boolean,
    currentFilterAppId: Int,
    currentChannelId: Int?,
    dndEnabled: Boolean,
    drawerCounts: DrawerCounts = DrawerCounts.EMPTY,
    onSelectFilter: (Int) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onClearMessages: () -> Unit,
    onMarkAllRead: () -> Unit,
    onDndToggle: (Boolean) -> Unit,
    onLongPressApp: (DrawerApp) -> Unit,
    onLongPressChannel: (ChatzChannel) -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    /** 点服务器分组头：展开 / 收起该分组 */
    onToggleServer: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val themeColor = MaterialTheme.colorScheme.primaryContainer
    val onThemeColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(bottomEnd = 16.dp),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== Header =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(themeColor)
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Column {
                    Text(
                        text = "Chatz",
                        color = onThemeColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = serverText,
                        color = onThemeColor.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // ===== 所有消息 =====
                        // 徽标 = 本地消息总数（0 时不显示）
                        item(key = "all") {
                            DrawerItem(
                                text = "所有消息",
                                iconUrl = null,
                                fallbackIconRes = null,
                                selected = currentFilterAppId == -1 && currentChannelId == null,
                                onClick = { onSelectFilter(-1) },
                                count = drawerCounts.total
                            )
                        }
                        // ===== 未读（Chatz 专有：已读状态是 Chatz 扩展） =====
                        if (anyServerIsChatz) {
                            item(key = "unread") {
                                DrawerItem(
                                    text = "未读",
                                    iconUrl = null,
                                    fallbackIconRes = null,
                                    selected = currentFilterAppId == MessageViewModel.FILTER_UNREAD,
                                    onClick = { onSelectFilter(MessageViewModel.FILTER_UNREAD) },
                                    count = drawerCounts.unread
                                )
                            }
                        }

                        // ===== 收藏 =====
                        item(key = "starred") {
                            DrawerItem(
                                text = "收藏",
                                iconUrl = null,
                                fallbackIconRes = null,
                                selected = currentFilterAppId == -2,
                                onClick = { onSelectFilter(-2) },
                                count = drawerCounts.starred,
                                alwaysShowCount = true,
                                iconContent = {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFC107),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            )
                        }

                        // ===== 每个服务器一个分组 =====
                        // 分组可收起：收起时只发射分组头，内容整段不发射（LazyColumn 不渲染）
                        servers.forEach serverLoop@ { server ->
                            item(key = "server_header_${server.serverId}") {
                                DrawerServerHeader(
                                    label = server.label,
                                    kindLabel = server.kindLabel,
                                    connected = server.connected,
                                    count = server.totalCount,
                                    hasUnread = server.hasUnread,
                                    expanded = server.expanded,
                                    onToggle = { onToggleServer(server.serverId) }
                                )
                            }

                            if (!server.expanded) return@serverLoop

                            if (!server.ntfyTopic.isNullOrBlank()) {
                                val ntfyFilterId = LocalIds.makeAppFilterId(server.slot, 0)
                                item(key = "ntfy_${server.serverId}") {
                                    DrawerItem(
                                        text = server.ntfyTopic,
                                        iconUrl = null,
                                        fallbackIconRes = R.drawable.ic_ntfy_default,
                                        selected = currentFilterAppId == ntfyFilterId,
                                        onClick = { onSelectFilter(ntfyFilterId) },
                                        count = drawerCounts.byApp[ntfyFilterId] ?: 0
                                    )
                                }
                            } else {
                                if (server.channels.isNotEmpty()) {
                                    item(key = "channels_header_${server.serverId}") {
                                        DrawerSectionHeader("频道")
                                    }
                                    itemsIndexed(
                                        items = server.channels,
                                        key = { _, ch -> "channel_${server.serverId}_${ch.id}" }
                                    ) { _, ch ->
                                        DrawerItem(
                                            text = ch.name,
                                            iconUrl = ch.image,
                                            fallbackIconRes = null,
                                            selected = currentChannelId == ch.id,
                                            onClick = { onSelectChannel(ch.id) },
                                            onLongClick = { onLongPressChannel(ch) },
                                            count = drawerCounts.byChannel[ch.id] ?: 0,
                                            highlightCount = drawerCounts.channelsWithUnread.contains(ch.id),
                                            circleIcon = true,
                                            muted = ch.muted,
                                            iconContent = if (ch.image.isNullOrBlank()) {
                                                { DrawerLetterAvatar(ch.name) }
                                            } else null
                                        )
                                    }
                                }

                                // 有频道（Chatz）→ 只列频道，不再列应用（按频道配通知）；
                                // 没有频道（纯 Gotify）→ 必须保留应用分组，否则只剩"所有消息"无法按应用筛
                                else if (server.apps.isNotEmpty()) {
                                    item(key = "apps_header_${server.serverId}") {
                                        DrawerSectionHeader("应用")
                                    }
                                    itemsIndexed(
                                        items = server.apps,
                                        key = { _, app -> "app_${server.serverId}_${app.id}" }
                                    ) { _, app ->
                                        DrawerItem(
                                            text = app.name,
                                            iconUrl = app.iconUrl,
                                            // 应用也走圆形；没有图标时用首字母头像（与频道一致）
                                            // 图标列表还没加载完时同样落到首字母，不会出现空块
                                            fallbackIconRes = null,
                                            selected = currentFilterAppId == app.id,
                                            onClick = { onSelectFilter(app.id) },
                                            onLongClick = { onLongPressApp(app) },
                                            count = drawerCounts.byApp[app.id] ?: 0,
                                            circleIcon = true,
                                            iconContent = if (app.iconUrl.isNullOrBlank()) {
                                                { DrawerLetterAvatar(app.name) }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    // 不加上下留白：让首条贴着卡片顶边，它的 16dp 圆角涟漪正好能与卡片的
                    // 16dp 圆角对齐；上面那组（DrawerItem）也是贴边的，两边才一致
                    Column {
                        // 频道 / 已读都是 Chatz 扩展，没有已启用的 Chatz 服务器时不显示这些入口
                        if (anyServerIsChatz) {
                            ActionItem(
                                text = "发现频道",
                                icon = {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = onOpenDiscover
                            )
                            ActionItem(
                                text = "全部已读",
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_read),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = onMarkAllRead
                            )
                        }
                        ActionItem(
                            text = "设置",
                            icon = {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            onClick = onOpenSettings
                        )
                        ActionItem(
                            text = "清空消息",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            onClick = onClearMessages
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "勿扰模式",
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = dndEnabled,
                        onCheckedChange = onDndToggle,
                        colors = settingsSwitchColors()
                    )
                }
            }
        }
    }
}


/**
 * 服务器分组头：连接状态点 + 名称 + 类型标签 + 消息总数 + 展开箭头
 *
 * 整行可点：展开 / 收起该分组。收起后这一行仍保留（含总数徽标与连接状态点），
 * 所以"哪台有多少消息、有没有连上"一眼可见，只是不再列出频道/应用条目。
 */
@Composable
private fun DrawerServerHeader(
    label: String,
    kindLabel: String,
    connected: Boolean,
    count: Int,
    hasUnread: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // 展开时箭头朝下、收起时朝右（rotate 到 -90）
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(180),
        label = "drawerServerArrow"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            // 上下对称：这一行现在是可点的分组头，收起时它自己就是一行，
            // 用原来的 12/4 非对称内边距会看起来偏上、不居中
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (connected) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // 空串 = 标题已经自带类型了（labelOf 在 displayName 为空时会拼成「Chatz(域名)」），
        // 这时再显示一遍类型就是重复 —— 见 ServerDisplay.showsKindBadge 的说明
        if (kindLabel.isNotBlank()) {
            Text(
                text = kindLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (count > 9999) "9999+" else count.toString(),
                fontSize = 11.sp,
                fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                // 有未读 → 红字。
                // ⚠️ 深色模式下**不能再压 alpha**：深色底本身对比度就吃紧，
                //    error 色再叠一层透明度就糊成一团，用户反馈"看不太出来"。
                //    （浅色底上保留压淡，避免纯红太刺眼。）
                color = if (hasUnread) {
                    unreadAccent()
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier
                .size(20.dp)
                .rotate(arrowRotation)
        )
    }
}

/**
 * 首字母头像（频道 / 应用在没有图片时用）
 *
 * 圆形 + 主题色底 + 名称首字母，尺寸对齐 [DrawerItem] 的图标位（28dp）。
 */
@Composable
private fun DrawerLetterAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * 分组标题
 */
@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

/**
 * 抽屉单项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    text: String,
    iconUrl: String?,
    fallbackIconRes: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int = 0,
    alwaysShowCount: Boolean = false,
    circleIcon: Boolean = false,
    muted: Boolean = false,
    /** 徽标显示成淡红（用来标记"这个频道有未读"） */
    highlightCount: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    iconContent: (@Composable () -> Unit)? = null
) {
    val themeColorInt = MaterialTheme.colorScheme.primaryContainer.toArgb()

    val selectedBgInt = if (ThemeColorManager.isLightColor(themeColorInt)) {
        ColorUtils.blendARGB(themeColorInt, android.graphics.Color.BLACK, 0.20f)
    } else {
        ColorUtils.blendARGB(themeColorInt, android.graphics.Color.WHITE, 0.20f)
    }
    val selectedBg = Color(selectedBgInt)
    val selectedTextColor = Color(ThemeColorManager.contrastTextColor(selectedBgInt))
    val normalTextColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) selectedBg else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            iconContent != null -> {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    iconContent()
                }
                Spacer(Modifier.width(12.dp))
            }

            iconUrl != null || fallbackIconRes != null -> {
                val iconShape = if (circleIcon) CircleShape else RoundedCornerShape(6.dp)

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(iconShape),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        iconUrl != null -> {
                            if (circleIcon) {
                                GlideIcon(
                                    url = iconUrl,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                GlideIcon(
                                    url = iconUrl,
                                    modifier = Modifier.size(22.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        fallbackIconRes != null -> {
                            Image(
                                painter = painterResource(fallbackIconRes),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }

            else -> {
                Spacer(Modifier.width(40.dp))
            }
        }

        Text(
            text = text,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            color = if (selected) selectedTextColor else normalTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 已静音标记（不用图标：Icons 核心集里没有静音图标，避免引入不存在 drawable）
        if (muted) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "不接收通知",
                fontSize = 11.sp,
                color = if (selected) {
                    selectedTextColor.copy(alpha = 0.8f)
                } else {
                    normalTextColor.copy(alpha = 0.55f)
                }
            )
        }

        if (count > 0 || alwaysShowCount) {
            Spacer(Modifier.width(8.dp))
            CountChip(
                count = count,
                selected = selected,
                selectedTextColor = selectedTextColor,
                normalTextColor = normalTextColor,
                highlight = highlightCount
            )
        }
    }
}

/**
 * 未读提示用的强调色
 *
 * ⚠️ 深色模式下**不压 alpha**：深色底本身对比度就吃紧，
 *    error 色再叠一层透明度会糊成一团 —— 用户反馈抽屉里的未读数看不清。
 *    浅色底上保留压淡，避免纯红太刺眼。
 */
@Composable
private fun unreadAccent(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.error.copy(alpha = 0.75f)

@Composable
private fun CountChip(
    count: Int,
    selected: Boolean,
    selectedTextColor: Color,
    normalTextColor: Color,
    /** 淡红态：用于"该频道有未读"的提示 */
    highlight: Boolean = false
) {
    val errorColor = MaterialTheme.colorScheme.error

    val bgColor = when {
        // 未读徽标的底要够实才看得出是"红"：0.15/0.30 那档在深色下几乎看不出色相。
        // 深色给到 0.55（混出来是明确的红底），浅色 0.30。
        highlight -> errorColor.copy(alpha = if (isSystemInDarkTheme()) 0.55f else 0.30f)
        selected -> selectedTextColor.copy(alpha = 0.15f)
        else -> normalTextColor.copy(alpha = 0.08f)
    }
    // 未读时数字本身也带强调色 —— 只靠淡红底的话，深色模式下依然不起眼。
    // 深色用白字（红底上最跳），浅色用红字（淡红底上对比足够）。
    val textColor = when {
        selected -> selectedTextColor
        highlight -> if (isSystemInDarkTheme()) Color.White else errorColor
        else -> normalTextColor.copy(alpha = 0.7f)
    }

    val displayText = if (count > 9999) "9999+" else count.toString()

    Box(
        modifier = Modifier
            .widthIn(min = 28.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ActionItem(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 这一整段刻意与 DrawerItem 保持同一套度量，否则上下两个卡片看起来不是一套：
            //   1) clip 放在 clickable **之前**，涟漪才会被裁成 16dp 圆角
            //      （少了这一步，这里的涟漪是直角的、还铺满整宽）
            //   2) 内边距 16/12（原来是 20/14，会比上面那组每行都高一些）
            //   3) 图标占 28dp 方框 + 12dp 间距，文字起始位置才和上面那组对齐
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 抽屉里的图标加载（Glide + 内存缓存）
 *
 * internal：发现页（ui/channel）也要用同一套加载逻辑，
 * 避免在两个文件里各写一份 Glide CustomTarget。
 */
@Composable
internal fun GlideIcon(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = ImageBitmapCache.getIcon(url),
        key1 = url
    ) {
        if (value != null) return@produceState

        val result = suspendCancellableCoroutine<androidx.compose.ui.graphics.ImageBitmap?> { cont ->
            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    val ib = resource.asImageBitmap()
                    ImageBitmapCache.putIcon(url, ib)
                    if (cont.isActive) cont.resume(ib)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (cont.isActive) cont.resume(null)
                }

                /**
                 * 加载失败也必须 resume
                 *
                 * CustomTarget 对 onLoadFailed 是空实现，若不重写，失败时协程会
                 * 永远挂起（onLoadCleared 在部分失败路径下不会回调），
                 * 表现就是图标静默留白 + 协程泄漏。
                 */
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            Glide.with(context)
                .asBitmap()
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(target)

            cont.invokeOnCancellation {
                try {
                    Glide.with(context).clear(target)
                } catch (_: Exception) {
                }
            }
        }

        value = result
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}