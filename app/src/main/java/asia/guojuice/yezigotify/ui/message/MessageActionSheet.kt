package asia.guojuice.yezigotify.ui.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.utils.MessageText
import kotlinx.coroutines.launch

/**
 * 长按消息弹出的底部菜单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: GotifyMessage?,
    onDismiss: () -> Unit,
    onCopyContent: (GotifyMessage) -> Unit,
    onCopyTitle: (GotifyMessage) -> Unit,
    onCopyBody: (GotifyMessage) -> Unit,
    onToggleRead: (GotifyMessage) -> Unit,
    /**
     * 回复这条消息；null = 不显示「回复」项
     *
     * 由调用方决定能不能回复（发送必须有明确的频道目标），所以这里用可空来表示"不可用"。
     */
    onReply: ((GotifyMessage) -> Unit)? = null,
) {
    if (message == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val scaleAnim = remember(message.id) { Animatable(0.85f) }

    LaunchedEffect(message.id) {
        scaleAnim.snapTo(0.85f)
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    fun dismissSheet(action: (() -> Unit)? = null) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            action?.invoke()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
        ) {
            // ===== 消息预览 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text(
                    text = message.title ?: "Gotify",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val preview = MessageText.stripImageMarkdown(message.message)
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // ===== 操作项 =====

            // 回复（只有 Chatz 频道消息才有意义，可用性由调用方判断）
            if (onReply != null) {
                ActionRow(
                    icon = ReplyIcon,
                    label = "回复",
                    onClick = { dismissSheet { onReply?.invoke(message) } }
                )
            }

            // 标已读/未读
            //
            // 「已读状态」是 **Chatz 扩展**：非 Chatz 服务器（Gotify / ntfy）的消息
            // is_read 恒为 0，服务端也没有这个语义，点了只能改本地、永远同步不出去。
            // 所以只在 Chatz 服务器上显示这一项。
            if (ServerCapabilitiesHolder.isChatz(message.sourceServerId)) {
                ActionRow(
                    icon = if (message.isRead) MarkUnreadIcon else MarkReadIcon,
                    label = if (message.isRead) "标为未读" else "标为已读",
                    onClick = { dismissSheet { onToggleRead(message) } }
                )
            }

            // 复制内容（标题 + 正文）
            ActionRow(
                icon = ContentCopyIcon,
                label = "复制内容",
                onClick = { dismissSheet { onCopyContent(message) } }
            )
            ActionRow(
                icon = TitleIcon,
                label = "复制标题",
                onClick = { dismissSheet { onCopyTitle(message) } }
            )
            ActionRow(
                icon = BodyIcon,
                label = "复制正文",
                onClick = { dismissSheet { onCopyBody(message) } }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            fontSize = 16.sp
        )
    }
}

// ==================== 自定义图标 ====================

/**
 * 回复：Material reply
 */
private val ReplyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Reply",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 9f)
            verticalLineTo(5f)
            lineTo(3f, 12f)
            lineTo(10f, 19f)
            verticalLineTo(14.9f)
            curveTo(15f, 14.9f, 18.5f, 16.5f, 21f, 20f)
            curveTo(20f, 15f, 17f, 10f, 10f, 9f)
            close()
        }
    }.build()
}

/**
 * 标为已读：实心圆 + 白色对勾（Material check_circle）
 */
private val MarkReadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MarkRead",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(10f, 17f)
            lineTo(5f, 12f)
            lineTo(6.41f, 10.59f)
            lineTo(10f, 14.17f)
            lineTo(17.59f, 6.58f)
            lineTo(19f, 8f)
            lineTo(10f, 17f)
            close()
        }
    }.build()
}

/**
 * 标为未读：空心圆（Material radio_button_unchecked）
 */
private val MarkUnreadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MarkUnread",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
            reflectiveCurveToRelative(3.59f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.59f, 8f, 8f)
            reflectiveCurveTo(16.41f, 20f, 12f, 20f)
            close()
        }
    }.build()
}

/**
 * ContentCopy 图标
 */
private val ContentCopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveToRelative(3f, 4f)
            horizontalLineTo(8f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveToRelative(0f, 16f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }.build()
}

/**
 * Title 图标
 */
private val TitleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Title",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 4f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(5.5f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(3f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            verticalLineTo(4f)
            close()
        }
    }.build()
}

/**
 * Body 图标
 */
private val BodyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Body",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 5f)
            horizontalLineTo(21f)
            verticalLineTo(7f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 10.5f)
            horizontalLineTo(21f)
            verticalLineTo(12.5f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 16f)
            horizontalLineTo(14f)
            verticalLineTo(18f)
            horizontalLineTo(3f)
            close()
        }
    }.build()
}