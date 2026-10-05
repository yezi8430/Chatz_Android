package asia.guojuice.yezigotify.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.ui.drawer.GlideIcon

/**
 * 发现频道弹窗
 *
 * 展示 /channel/discover 返回的全部公开频道，并允许直接订阅 / 退订。
 *
 * 这是个无状态组件：频道列表、加载态、失败态、正在处理的频道 id 全部由调用方持有，
 * 因为订阅动作要落到 ViewModel，操作完成后还需要重新拉一次列表。
 *
 * @param pendingId 正在请求中的频道 id（该行的按钮显示"处理中"并禁用，避免重复点）
 */
@Composable
fun ChannelDiscoverDialog(
    channels: List<ChatzChannel>,
    loading: Boolean,
    failed: Boolean,
    pendingId: Int?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleSubscribe: (ChatzChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "发现频道",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点右侧按钮订阅或退订",
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    placeholder = { Text("搜索频道名或 ID", fontSize = 14.sp) },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⚠️ min == max ⇒ **固定高度**。
                        //    原来是 `min = 140.dp, max = 420.dp`（一个范围）：列表加载完行数一变，
                        //    这个 Box 的高度就跟着变 ⇒ 整个弹窗尺寸剧变，而 Dialog 进场自带
                        //    缩放/淡入动画，动画正好作用在剧变上 ⇒ 用户看到的「漂移 / 抽搐」。
                        //    修法与「个人信息」弹窗同一套（见 AccountDialogs 里那段注释）：
                        //    高度钉死 + 内容结构从第一帧就完整。
                        .heightIn(min = 300.dp, max = 300.dp)
                ) {
                    // 列表**始终存在**（哪怕一行都没有），结构不随 loading / failed 变化
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            ChannelDiscoverRow(
                                channel = channel,
                                pending = pendingId == channel.id,
                                onToggleSubscribe = onToggleSubscribe
                            )
                        }
                    }

                    // 三种「列表为空」的提示做成**覆盖层**，不再替换掉列表本身
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                        failed -> Text(
                            text = "没有可显示的频道，或加载失败",
                            fontSize = 14.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.Center)
                        )

                        channels.isEmpty() -> Text(
                            text = "还没有可发现的频道",
                            fontSize = 14.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun ChannelDiscoverRow(
    channel: ChatzChannel,
    pending: Boolean,
    onToggleSubscribe: (ChatzChannel) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 频道图标：有图用图，没图用首字母（与抽屉里的样式保持一致）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!channel.image.isNullOrBlank()) {
                GlideIcon(url = channel.image, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.name.take(1).uppercase(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    fontSize = 15.sp,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 锁图标放 ID 前面（有密码时更醒目）
                if (channel.passwordProtected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "需要密码",
                        tint = onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(15.dp)
                    )
                }
                // 频道名允许重名，靠 id 区分（像 QQ 群：群号唯一、群名随便）
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "(ID:${channel.id})",
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.5f)
                )
            }
            if (!channel.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = channel.description,
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        if (channel.subscribed) {
            OutlinedButton(
                onClick = { onToggleSubscribe(channel) },
                enabled = !pending
            ) {
                Text(if (pending) "处理中" else "已订阅", fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = { onToggleSubscribe(channel) },
                enabled = !pending
            ) {
                Text(if (pending) "处理中" else "订阅", fontSize = 13.sp)
            }
        }
    }
}
