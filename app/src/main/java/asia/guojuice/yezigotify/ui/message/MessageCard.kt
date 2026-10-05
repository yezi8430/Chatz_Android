package asia.guojuice.yezigotify.ui.message

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import asia.guojuice.yezigotify.AppIconCache
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.R
import asia.guojuice.yezigotify.capabilities.ChatzAggChild
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.utils.AppInfo
import asia.guojuice.yezigotify.utils.MessageSender
import asia.guojuice.yezigotify.utils.MessageText
import asia.guojuice.yezigotify.utils.SenderInfo
import asia.guojuice.yezigotify.utils.roleLabel
import asia.guojuice.yezigotify.utils.ThemeColorManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.noties.markwon.Markwon
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import androidx.compose.animation.core.Animatable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageCard(
    msg: GotifyMessage,
    imageAsBackground: Boolean,
    markwon: Markwon,
    nowMinuteState: State<Long>,
    /**
     * 频道映射（channelId → ChatzChannel）
     */
    channels: Map<Int, ChatzChannel> = emptyMap(),
    onImageClick: (String, Bitmap?) -> Unit = { _, _ -> },
    onUrlClick: (String) -> Unit = {},
    onLongPress: () -> Unit = {},
    /** 点击标签 chip：按该标签筛选列表 */
    onTagClick: (String) -> Unit = {},
    /**
     * 点击引用条：跳转到被回复的那条消息
     *
     * 参数是（来源服务器 id, 被回复消息的**远程** id）—— slot 只有 MainActivity 那边
     * 能从服务器配置读到，所以换成本地 id 的活交给它做。
     */
    onQuoteClick: (serverId: String, remoteReplyId: Long) -> Unit = { _, _ -> },
    /** 是否为"刚跳过来的那条"（描一圈边框提示） */
    highlighted: Boolean = false,
    @Suppress("UNUSED_PARAMETER") isScrolling: () -> Boolean = { false },
    onToggleCollapse: () -> Unit = {}
) {
    val themeColorInt = ThemeColorManager.getEffectiveColor()
    val themeColor = Color(themeColorInt)
    val isLight = ThemeColorManager.isLightColor(themeColorInt)

    val imageUrl = remember(msg.id) { extractImageUrl(msg) }
    val attachment = remember(msg.id) { extractAttachment(msg.extras) }
    val replyQuote = remember(msg.id) { extractReplyQuote(msg.extras) }
    val sender = remember(msg.id) { MessageSender.from(msg.extras) }
    // 应用快照（服务端写入 extras.app）：应用按用户隔离后，图标/名字不能再指望应用列表
    val appInfo = remember(msg.id) { AppInfo.from(msg.extras) }

    val useBackgroundImage = imageAsBackground && imageUrl != null

    // ===== 已读/未读视觉 =====
    //
    // 未读：完全正常显示
    // 已读：文字淡化 + 标题字重变 Normal
    //
    // ⚠️ 淡化系数从 0.6 提到 0.78。
    //    0.6 是照搬 WebUI 的 .msg-card.read，但**两边的卡片底色根本不是一回事**：
    //    WebUI 那边卡片接近白色，深灰字压上去照样清楚；
    //    客户端卡片底是**用户主题色**（动态取色，可能是青绿这类中等明度），
    //    再乘 0.6 后正文只剩 0.51、页脚只剩 0.39 的 alpha，对比度就不够了
    //    —— 用户反馈"已读卡片字体有点浅"。
    //    已读本来还有"标题字重 SemiBold → Normal"在区分，淡化不必压这么狠。
    val isRead = msg.isRead
    val readDim = if (isRead) 0.78f else 1.0f

    // 基础色（未应用"已读淡化"）
    val baseTitleColor = if (useBackgroundImage) Color.White else {
        if (isLight) Color.Black else Color.White
    }
    val baseBodyColor = if (useBackgroundImage) Color.White else {
        if (isLight) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)
    }
    val baseFooterColor = if (useBackgroundImage) Color.White.copy(alpha = 0.75f) else {
        if (isLight) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.65f)
    }

    // 应用"已读淡化"后的色
    val titleColor = baseTitleColor.copy(alpha = baseTitleColor.alpha * readDim)
    val bodyColor = baseBodyColor.copy(alpha = baseBodyColor.alpha * readDim)
    val footerColor = baseFooterColor.copy(alpha = baseFooterColor.alpha * readDim)
    val titleWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold

    // ===== 首字母头像配色 =====
    val letterBgColor: Color = if (useBackgroundImage) {
        Color(ColorUtils.blendARGB(
            android.graphics.Color.BLACK,
            android.graphics.Color.WHITE,
            0.15f
        ))
    } else {
        val mixTarget = if (isLight) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
        Color(ColorUtils.blendARGB(themeColorInt, mixTarget, 0.15f))
    }

    val letterTextColor = if (useBackgroundImage) Color.White else {
        if (isLight) Color.Black else Color.White
    }

    val timeText by remember(msg.date) {
        derivedStateOf { formatTime(msg.date, nowMinuteState.value) }
    }

    val handleClick: () -> Unit = {
        when {
            imageUrl != null -> onImageClick(imageUrl, null)
            else -> {
                val url = extractFirstUrl(msg.message)
                if (url != null) onUrlClick(url)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (useBackgroundImage) Color.Black else themeColor)
            .combinedClickable(
                onClick = handleClick,
                onLongClick = { onLongPress() }
            )
    ) {
        if (useBackgroundImage) {
            val isApi31Plus = android.os.Build.VERSION.SDK_INT >= 31
            AsyncImage(
                url = imageUrl!!,
                modifier = Modifier.matchParentSize().then(
                    if (isApi31Plus) Modifier.blur(12.dp) else Modifier
                ),
                contentScale = ContentScale.Crop,
                blurScale = if (isApi31Plus) 0 else 8
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(
                    serverId = msg.sourceServerId,
                    appId = msg.appid,
                    channel = msg.channelId?.let { channels[it] },
                    ntfyIcon = msg.extras?.get("icon") as? String,
                    letterTextColor = letterTextColor,
                    letterBgColor = letterBgColor,
                    appSnapshot = appInfo,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = msg.title ?: "Gotify",
                    color = titleColor,
                    fontSize = 15.sp,
                    fontWeight = titleWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(14.dp))
            }

            // ===== 发送者：昵称 @用户名 [+ 管理员] =====
            // 服务端在消息创建时把快照写进 extras.sender（webhook 发的消息没有，不显示）
            if (sender != null) {
                Spacer(Modifier.height(4.dp))
                SenderLine(sender = sender, textColor = footerColor, accent = themeColor)
            }

            Spacer(Modifier.height(8.dp))

            // ===== 引用条：这条是"回复某条消息" =====
            // 服务端没有 reply_to 字段，引用关系放在 extras 里（见 ReplyReceiver.replyExtras）
            if (replyQuote != null) {
                ReplyQuoteStrip(
                    text = replyQuote.text,
                    color = footerColor,
                    // 没有远程 id 就不可点（老消息可能只有摘要）
                    onJump = replyQuote.remoteId?.let { rid ->
                        { onQuoteClick(msg.sourceServerId, rid) }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            val cleanText = remember(msg.id, imageUrl, attachment) {
                val attachUrl = imageUrl ?: attachment?.url
                if (attachUrl == null) {
                    MessageText.stripImageMarkdown(msg.message)
                } else {
                    // 正文里那段指向附件本身的 markdown 链接（或旧消息里的裸 URL）也去掉，
                    // 附件在卡片上已经有专属呈现（图片 / 附件条），不必再显示一遍。
                    // ⚠️ 只剥**展示层**：存储的正文必须保留 —— 服务端自带的 WebUI 正是靠
                    // 正文里的 markdown 链接才渲染得出图片。
                    val url = Regex.escape(attachUrl)
                    msg.message
                        .replace(Regex("!\\[[^\\]]*\\]\\($url\\)"), "")
                        .replace(Regex("\\[[^\\]]*\\]\\($url\\)"), "")
                        .replace(attachUrl, "")
                        .trim()
                }
            }
            CollapsibleMarkdownText(
                markdown = cleanText,
                markwon = markwon,
                textColor = bodyColor,
                modifier = Modifier.fillMaxWidth(),
                collapsedLines = 3,
                onToggleCollapse = onToggleCollapse
            )

            if (imageUrl != null && !imageAsBackground) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    url = imageUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onImageClick(imageUrl, null) },
                    contentScale = ContentScale.Crop
                )
            }

            // ===== 文件附件（非图片） =====
            // 图片不用这条：它走上面的 extras.image 链路，两份都渲染会重复
            if (attachment != null) {
                Spacer(Modifier.height(8.dp))
                AttachmentChip(
                    name = attachment.name,
                    size = attachment.size,
                    color = bodyColor,
                    onClick = { onUrlClick(attachment.url) }
                )
            }

            // ===== 聚合消息（× N） =====
            if (msg.isAggregated) {
                Spacer(Modifier.height(8.dp))
                AggregateBlock(
                    msg = msg,
                    markwon = markwon,
                    bodyColor = bodyColor,
                    footerColor = footerColor,
                    nowMinuteState = nowMinuteState,
                    onImageClick = onImageClick,
                    onToggleCollapse = onToggleCollapse
                )
            }

            Spacer(Modifier.height(8.dp))

            // ===== Footer：优先级 + 标签 + 时间 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "优先级: ${msg.priority}",
                    color = footerColor,
                    fontSize = 12.sp
                )

                // 标签 chips（最多展示 3 个）；点击可按该标签筛选
                msg.tags?.take(3)?.forEach { tag ->
                    Spacer(Modifier.width(6.dp))
                    TagChip(
                        text = tag,
                        color = footerColor,
                        onClick = { onTagClick(tag) }
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = timeText,
                    color = footerColor,
                    fontSize = 12.sp
                )
            }
        }

        // ===== 跳转高亮 =====
        // 刚跳过来的那条描一圈主题色边框、几秒后淡出：否则"跳过去"只表现为列表滚了一下，
        // 用户不知道到底跳到了哪条（通知点击和点引用条都走这里）
        val highlightAlpha by animateFloatAsState(
            targetValue = if (highlighted) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "highlightAlpha"
        )
        if (highlightAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = 2.dp,
                        color = themeColor.copy(alpha = 0.9f * highlightAlpha),
                        shape = RoundedCornerShape(12.dp)
                    )
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            val starAlpha by animateFloatAsState(
                targetValue = if (msg.starred) 1f else 0f,
                animationSpec = tween(durationMillis = 500),
                label = "starAlpha"
            )
            val starScale by animateFloatAsState(
                targetValue = if (msg.starred) 1f else 0.5f,
                animationSpec = keyframes {
                    durationMillis = 500
                    0.5f at 0
                    1.35f at 350 using FastOutLinearInEasing
                    1.0f at 500
                },
                label = "starScale"
            )
            val dotAlpha by animateFloatAsState(
                targetValue = if (msg.starred) 0f else 1f,
                animationSpec = tween(durationMillis = 500),
                label = "dotAlpha"
            )
            val dotScale by animateFloatAsState(
                targetValue = if (msg.starred) 0.5f else 1f,
                animationSpec = tween(durationMillis = 500),
                label = "dotScale"
            )
            val rippleProgress = remember { Animatable(0f) }
            LaunchedEffect(msg.starred) {
                if (msg.starred) {
                    rippleProgress.snapTo(0f)
                    rippleProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 600)
                    )
                } else {
                    rippleProgress.snapTo(1f)
                }
            }

            if (rippleProgress.value > 0f && rippleProgress.value < 1f) {
                val p = rippleProgress.value
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = 0.3f + 1.7f * p
                            scaleY = 0.3f + 1.7f * p
                            alpha = 0.5f * (1f - p)
                        }
                        .clip(CircleShape)
                        .background(Color(0xFFFFC107))
                )
            }

            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = if (msg.starred) "已收藏" else null,
                tint = Color(0xFFFFC107),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        alpha = starAlpha
                        scaleX = starScale
                        scaleY = starScale
                    }
            )

            // 优先级点：已读时淡化（保留色相）
            val priorityDotColor = if (isRead) {
                priorityColor(msg.priority).copy(alpha = 0.5f)
            } else {
                priorityColor(msg.priority)
            }

            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        alpha = dotAlpha
                        scaleX = dotScale
                        scaleY = dotScale
                    }
                    .clip(CircleShape)
                    .background(priorityDotColor)
                    .border(
                        width = 1.dp,
                        color = priorityDotBorder(msg.priority),
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * 标签 chip
 *
 * 可点击：点一下按该标签筛选列表。
 * chip 自己的 clickable 会消费掉点击事件，不会冒泡到卡片的 combinedClickable
 * （所以点标签不会误触发卡片的"打开图片/打开链接"）。
 */
@Composable
private fun TagChip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "#$text",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 80.dp)
        )
    }
}

/**
 * 聚合消息折叠块
 *
 * 服务端把「同频道 + 同应用 + 同标题」在时间窗口内的消息合并成一条，
 * 主消息带 aggCount（总数）和 aggChildren（子消息，按时间正序，最多 100 条）。
 *
 * 展示规则对齐 WebUI：
 *   - 折叠态：显示「× N」徽标 + 提示文案
 *   - 展开态：子消息倒序（最新在前），编号从 aggCount 递减
 */
@Composable
private fun AggregateBlock(
    msg: GotifyMessage,
    markwon: Markwon,
    bodyColor: Color,
    footerColor: Color,
    nowMinuteState: State<Long>,
    onImageClick: (String, Bitmap?) -> Unit,
    onToggleCollapse: () -> Unit
) {
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val children = msg.aggChildren.orEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(footerColor.copy(alpha = 0.12f))
                .clickable {
                    val wasExpanded = expanded
                    expanded = !expanded
                    // 收起时补偿滚动，避免下方内容跳动（与「展开全文」一致）
                    if (wasExpanded) onToggleCollapse()
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(footerColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "× ${msg.aggCount}",
                    color = footerColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "点击收起" else "点击展开合并的消息",
                color = footerColor,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = footerColor,
                modifier = Modifier.size(16.dp)
            )
        }

        if (expanded) {
            val ordered = children.asReversed()
            if (ordered.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "没有可展开的子消息",
                    color = footerColor,
                    fontSize = 12.sp
                )
            } else {
                ordered.forEachIndexed { idx, child ->
                    if (idx == 0) {
                        Spacer(Modifier.height(10.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .height(1.dp)
                                .background(footerColor.copy(alpha = 0.15f))
                        )
                    }
                    AggregateChildItem(
                        child = child,
                        number = msg.aggCount - idx,
                        parentTitle = msg.title,
                        markwon = markwon,
                        bodyColor = bodyColor,
                        footerColor = footerColor,
                        nowMinuteState = nowMinuteState,
                        onImageClick = onImageClick
                    )
                }
            }
        }
    }
}

/**
 * 聚合块里的单条子消息
 */
@Composable
private fun AggregateChildItem(
    child: ChatzAggChild,
    number: Int,
    parentTitle: String?,
    markwon: Markwon,
    bodyColor: Color,
    footerColor: Color,
    nowMinuteState: State<Long>,
    onImageClick: (String, Bitmap?) -> Unit
) {
    val childImageUrl = remember(child) { MessageText.imageUrlFromExtras(child.extras) }
    val cleanText = remember(child) { MessageText.stripImageMarkdown(child.message) }
    // 聚合子消息各自也可能是不同的人发的，所以发送者也逐条显示
    val childSender = remember(child) { MessageSender.from(child.extras) }
    val showTitle = !child.title.isNullOrBlank() && child.title != parentTitle
    val childTime by remember(child.date) {
        derivedStateOf { formatTime(child.date, nowMinuteState.value) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#$number",
                color = footerColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = childTime,
                color = footerColor,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "P${child.priority}",
                color = footerColor,
                fontSize = 11.sp
            )
        }

        if (childSender != null) {
            Spacer(Modifier.height(4.dp))
            SenderLine(sender = childSender, textColor = footerColor, accent = bodyColor)
        }

        if (showTitle) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = child.title.orEmpty(),
                color = bodyColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (cleanText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CollapsibleMarkdownText(
                markdown = cleanText,
                markwon = markwon,
                textColor = bodyColor,
                modifier = Modifier.fillMaxWidth(),
                collapsedLines = 3
            )
        }

        if (childImageUrl != null) {
            Spacer(Modifier.height(6.dp))
            AsyncImage(
                url = childImageUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onImageClick(childImageUrl, null) },
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 图片 bitmap 内存缓存
 */
internal object ImageBitmapCache {

    private const val ICON_MAX_COUNT = 200

    private val maxImageBytes: Int = run {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val budget = maxMemory / 8
        budget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private val iconCache =
        android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(ICON_MAX_COUNT)

    private val imageCache = object :
        android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(maxImageBytes) {
        override fun sizeOf(
            key: String,
            value: androidx.compose.ui.graphics.ImageBitmap
        ): Int {
            val bytes = value.width.toLong() * value.height.toLong() * 4L
            return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    fun getIcon(url: String): androidx.compose.ui.graphics.ImageBitmap? =
        iconCache.get(url)

    fun putIcon(url: String, bitmap: androidx.compose.ui.graphics.ImageBitmap) {
        iconCache.put(url, bitmap)
    }

    fun getImage(key: String): androidx.compose.ui.graphics.ImageBitmap? =
        imageCache.get(key)

    fun putImage(key: String, bitmap: androidx.compose.ui.graphics.ImageBitmap) {
        imageCache.put(key, bitmap)
    }
}

private fun priorityDotBorder(priority: Int): Color {
    val dot = priorityColor(priority)
    val luminance = 0.299f * dot.red + 0.587f * dot.green + 0.114f * dot.blue
    return if (luminance > 0.5f) Color(0x66000000) else Color(0x66FFFFFF)
}

// ==================== 应用/频道 图标 ====================

/**
 * 消息卡片左上角图标
 *
 * 形状规则：全部圆形
 *   - 频道：上传图标，没有图标时用频道名首字母
 *   - 应用：服务端图标，没有图标时用应用名首字母
 *   - ntfy（appid == 0）：图标来自消息 extras，没有时用默认图标
 */
@Composable
private fun AppIconImage(
    serverId: String,
    appId: Int,
    channel: ChatzChannel?,
    ntfyIcon: String?,
    letterTextColor: Color,
    letterBgColor: Color,
    modifier: Modifier = Modifier,
    /**
     * 消息自带的应用快照（`extras.app`）。
     *
     * ⚠️ 有这个就**优先用它** —— 应用已按用户隔离，`GET /application` 只返回自己的，
     *    本地缓存里根本不会有「别人应用」；不靠快照的话，订阅别人频道时卡片只剩默认图标。
     *    老服务端不发这个字段 → null → 自动回退到本地缓存（行为与改造前一致）。
     */
    appSnapshot: AppInfo? = null
) {
    val cacheVersion = AppIconCache.loadVersion.value

    // ===== 1. 频道优先（圆形） =====
    if (channel != null) {
        if (!channel.image.isNullOrBlank()) {
            AsyncIcon(
                url = channel.image,
                modifier = modifier.clip(CircleShape)
            )
            return
        }
        if (channel.name.isNotBlank()) {
            LetterAvatar(
                name = channel.name,
                textColor = letterTextColor,
                bgColor = letterBgColor,
                modifier = modifier.clip(CircleShape)
            )
            return
        }
    }

    // ===== 2. 应用图标 / ntfy（圆形，与频道一致） =====
    val appIconModifier = modifier.clip(CircleShape)

    // 优先消息自带的应用快照（隔离后本地缓存查不到别人的应用）；没有才回退本地缓存
    val snapshotIconUrl = remember(serverId, appSnapshot?.image, cacheVersion) {
        AppIconCache.resolveUrl(serverId, appSnapshot?.image)
    }
    val iconUrl = snapshotIconUrl
        ?: remember(serverId, appId, ntfyIcon, cacheVersion) {
            AppIconCache.getIconUrl(serverId, appId) ?: ntfyIcon
        }

    // 应用名：快照优先；其次本地缓存。ntfy 的 appid == 0 保持用默认图标而不是首字母
    val appName = appSnapshot?.name?.takeIf { it.isNotBlank() }
        ?: remember(serverId, appId, cacheVersion) {
            if (appId == 0) null
            else AppIconCache.getAppList(serverId)?.firstOrNull { it.id == appId }?.name
        }

    if (iconUrl.isNullOrBlank()) {
        if (!appName.isNullOrBlank()) {
            // 没有图标 → 用首字母头像（与频道一致）
            LetterAvatar(
                name = appName,
                textColor = letterTextColor,
                bgColor = letterBgColor,
                modifier = appIconModifier
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_ntfy_default),
                contentDescription = null,
                modifier = appIconModifier,
                contentScale = ContentScale.Crop
            )
        }
        return
    }

    AsyncIcon(url = iconUrl, modifier = appIconModifier)
}

@Composable
private fun LetterAvatar(
    name: String,
    textColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
private fun AsyncIcon(
    url: String,
    modifier: Modifier = Modifier
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

                /** 失败也必须 resume，否则协程永久挂起（见 DrawerContent.GlideIcon 同款说明） */
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

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

// ==================== 图片 ====================

@Composable
private fun AsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    blurScale: Int = 0
) {
    val context = LocalContext.current
    val cacheKey = if (blurScale > 0) "${url}_blur$blurScale" else url

    var bitmap by remember(cacheKey) {
        mutableStateOf(ImageBitmapCache.getImage(cacheKey))
    }

    val imageAlpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "imageAlpha"
    )

    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        try {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val loaded = Glide.with(context).asBitmap().load(url).submit().get()
                if (blurScale > 1) fastBlur(loaded, blurScale) else loaded
            }
            val imgBitmap = bmp.asImageBitmap()
            bitmap = imgBitmap
            ImageBitmapCache.putImage(cacheKey, imgBitmap)
        } catch (e: Exception) {
            bitmap = null
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier.graphicsLayer {
                this.alpha = imageAlpha
            },
            contentScale = contentScale
        )
    }
}

private fun fastBlur(source: Bitmap, scale: Int): Bitmap {
    if (scale <= 1) return source
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return source

    val sw = (w / scale).coerceAtLeast(1)
    val sh = (h / scale).coerceAtLeast(1)

    val small = Bitmap.createScaledBitmap(source, sw, sh, true)
    val blurred = Bitmap.createScaledBitmap(small, w, h, true)

    if (small !== blurred && !small.isRecycled) {
        small.recycle()
    }
    return blurred
}

// ==================== 可折叠 Markdown ====================

@Composable
private fun CollapsibleMarkdownText(
    markdown: String,
    markwon: Markwon,
    textColor: Color,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 3,
    onToggleCollapse: () -> Unit = {}
) {
    var isExpanded by remember(markdown) { mutableStateOf(false) }
    var isOverflowing by remember(markdown) { mutableStateOf(false) }

    val contentAlpha = remember { Animatable(1f) }
    var isFirstComposition by remember { mutableStateOf(true) }

    LaunchedEffect(isExpanded) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        contentAlpha.snapTo(0f)
        contentAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 150)
        )
    }

    Column(modifier = modifier) {
        if (isExpanded) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha.value },
                factory = { ctx ->
                    TextView(ctx).apply {
                        textSize = 14f
                        Linkify.addLinks(this, Linkify.WEB_URLS)
                    }
                },
                update = { tv ->
                    tv.setTextColor(textColor.toArgb())
                    tv.text = markwon.toMarkdown(markdown)
                    tv.maxLines = Int.MAX_VALUE
                    tv.ellipsize = null
                }
            )
        } else {
            val plainText = remember(markdown) { stripMarkdown(markdown) }
            Text(
                text = plainText,
                color = textColor,
                fontSize = 14.sp,
                maxLines = collapsedLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = contentAlpha.value },
                onTextLayout = { layoutResult ->
                    val overflow = layoutResult.hasVisualOverflow
                    if (overflow != isOverflowing) {
                        isOverflowing = overflow
                    }
                }
            )
        }

        if (isOverflowing || isExpanded) {
            Text(
                text = if (isExpanded) "收起" else "展开全文",
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable {
                        val wasExpanded = isExpanded
                        isExpanded = !isExpanded
                        if (wasExpanded) {
                            onToggleCollapse()
                        }
                    }
            )
        }
    }
}

private fun stripMarkdown(md: String): String {
    if (md.isEmpty()) return md
    var s = md
    s = s.replace(Regex("```[\\s\\S]*?```")) { m ->
        m.value.removeSurrounding("```").trim()
    }
    s = s.replace(Regex("`([^`\n]+)`"), "$1")
    s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^\\)]*\\)"), "$1")
    s = s.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
    s = s.replace(Regex("\\*\\*([^*\n]+?)\\*\\*"), "$1")
    s = s.replace(Regex("__([^_\n]+?)__"), "$1")
    s = s.replace(Regex("\\*([^*\n]+?)\\*"), "$1")
    s = s.replace(Regex("_([^_\n]+?)_"), "$1")
    s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "· ")
    return s.trim()
}

private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}

// ==================== 工具函数 ====================

private fun priorityColor(priority: Int): Color {
    return when {
        priority < 1 -> Color(0xFF4CAF50)
        priority <= 3 -> Color(0xFFFFEB3B)
        priority <= 7 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
}

/** 卡片要展示的图片地址（extras 优先、正文 markdown 兜底），规则统一在 MessageText */
private fun extractImageUrl(msg: GotifyMessage): String? =
    MessageText.imageUrl(msg.message, msg.extras)

/**
 * 从 extras 里取文件附件（非图片）
 *
 * 约定：`extras["attachment"] = { url, name, size }` —— 由本 App 发消息时写入。
 * 图片不用这条：它走 extras.image，由上面的 imageUrl 链路渲染。
 * 和图片一样，url 必须是**绝对地址**（客户端只认 http 开头）。
 */
private fun extractAttachment(extras: Map<String, Any>?): FileAttachment? {
    val m = extras?.get("attachment") as? Map<*, *> ?: return null
    val url = (m["url"] as? String)?.takeIf { it.startsWith("http") } ?: return null
    return FileAttachment(
        url = url,
        name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: "附件",
        size = (m["size"] as? Number)?.toLong() ?: 0L
    )
}

/** 一条消息里的文件附件（见 [extractAttachment]） */
private data class FileAttachment(val url: String, val name: String, val size: Long)

/**
 * 一条消息引用的"被回复的原文"摘要
 *
 * 约定：`extras["reply_to_text"]` = 摘要（`extras["reply_to"]` = 被回复那条的远程 id，
 * 目前只在协议层保留，见 ReplyReceiver.replyExtras）—— 由本 App 在通知栏快捷回复时写入，
 * 服务端不认识这两个 key，只是原样透传。
 * 摘要直接随消息走，所以原消息即使不在本地也能显示引用内容。
 */
private data class ReplyQuote(val remoteId: Long?, val text: String)

private fun extractReplyQuote(extras: Map<String, Any>?): ReplyQuote? {
    val text = (extras?.get("reply_to_text") as? String)?.takeIf { it.isNotBlank() } ?: return null
    return ReplyQuote((extras["reply_to"] as? Number)?.toLong(), text)
}

/**
 * 发送者一行：`昵称 @用户名`，管理员 / 超级管理员再挂一个标签
 *
 * 解析和 label 的拼法都在 utils.MessageSender —— 通知栏、聚合子消息、卡片共用一份。
 * 徽标文案走 utils.roleLabel（isSuper 优先），别在这里写死 ——
 * 服务端 2026-09-30 把权限拆成两级后，isAdmin 已经不等于"全知"了。
 */
@Composable
private fun SenderLine(sender: SenderInfo, textColor: Color, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = sender.label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        roleLabel(sender.isAdmin, sender.isSuper)?.let { label ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/** 引用条：左侧一根竖线 + 被回复内容的摘要；传了 [onJump] 就可点，点击跳转原消息 */
@Composable
private fun ReplyQuoteStrip(
    text: String,
    color: Color,
    onJump: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.10f))
            .then(if (onJump != null) Modifier.clickable(onClick = onJump) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .background(color.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = color.copy(alpha = 0.85f),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 文件附件卡片
 *
 * 刻意不放图标：本项目只引入了 material-icons-core，里面没有「文件 / 回形针」这类图标。
 * 点击走 onUrlClick（ACTION_VIEW）—— 服务端给附件带的是 Content-Disposition: attachment，
 * 所以浏览器是下载而不是内联打开。
 */
@Composable
private fun AttachmentChip(
    name: String,
    size: Long,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = name,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (size > 0) "${formatFileSize(size)} · 点击打开" else "点击打开",
            color = color.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun extractFirstUrl(text: String): String? {
    val regex = Regex("""(?:(?:https?://)|(?:www\.))[\w\-]+(?:\.[\w\-]+)+(?::\d+)?(?:/[^\s)\]]*)?""")
    return regex.find(text)?.value?.let {
        if (it.startsWith("http")) it else "https://$it"
    }
}

private val UTC_SDF = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun formatTime(isoTime: String, nowMillis: Long = System.currentTimeMillis()): String {
    return try {
        val cleanTime = isoTime
            .replace(Regex("\\.\\d+Z"), "Z")
            .replace(Regex(" \\d+Z"), "Z")
        val date = UTC_SDF.parse(cleanTime) ?: return isoTime
        val now = nowMillis
        val diff = now - date.time
        when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}分钟前"
            isSameDay(now, date.time) -> format(date, "HH:mm")
            isSameDay(now - TimeUnit.DAYS.toMillis(1), date.time) -> "昨天 ${format(date, "HH:mm")}"
            isSameYear(now, date.time) -> format(date, "M月d日 HH:mm")
            else -> format(date, "yyyy/M/d HH:mm")
        }
    } catch (e: Exception) {
        isoTime
    }
}

private fun format(date: java.util.Date, pattern: String): String {
    return SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }.format(date)
}

private fun isSameDay(t1: Long, t2: Long): Boolean {
    val c = Calendar.getInstance()
    c.timeInMillis = t1
    val y1 = c.get(Calendar.YEAR)
    val d1 = c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = t2
    return y1 == c.get(Calendar.YEAR) && d1 == c.get(Calendar.DAY_OF_YEAR)
}

private fun isSameYear(t1: Long, t2: Long): Boolean {
    val c = Calendar.getInstance()
    c.timeInMillis = t1
    val y1 = c.get(Calendar.YEAR)
    c.timeInMillis = t2
    return y1 == c.get(Calendar.YEAR)
}