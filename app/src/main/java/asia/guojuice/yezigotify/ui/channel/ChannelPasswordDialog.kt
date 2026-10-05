package asia.guojuice.yezigotify.ui.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.capabilities.ChannelSubscribeResult
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 订阅受保护频道时的密码输入框
 *
 * 密码是「订阅门槛」：只有订阅这一步需要输一次，订上之后不用再输。
 *
 * 密码错了**不关框**：提示「密码错误」并清空让用户原地重试；
 * 服务端说「尝试次数过多」时锁定「订阅」按钮并倒计时。
 * 密码不落盘、不缓存。
 */
@Composable
fun ChannelPasswordDialog(
    channel: ChatzChannel,
    subscribe: (password: String, onResult: (ChannelSubscribeResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedSeconds by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要密码") },
        text = {
            Column {
                Text(
                    text = "「${channel.name}」设置了订阅密码，输入密码后才能订阅",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = {
                        pwd = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("频道密码") },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    subscribe(pwd) { result ->
                        busy = false
                        when (result) {
                            is ChannelSubscribeResult.Success -> onDismiss()
                            is ChannelSubscribeResult.WrongPassword -> {
                                pwd = ""
                                error = "密码错误"
                            }
                            is ChannelSubscribeResult.RateLimited -> {
                                error = "尝试次数过多，请稍后再试"
                                // 倒计时协程：用 rememberCoroutineScope，不挂在 LaunchedEffect 的
                                // key 上（否则在 while 里改 lockedSeconds 会反复重启协程）
                                val total = result.retryAfter
                                scope.launch {
                                    lockedSeconds = total
                                    var s = total
                                    while (s > 0) {
                                        delay(1000)
                                        s--
                                        lockedSeconds = s
                                    }
                                }
                            }
                            is ChannelSubscribeResult.Failed -> error = "网络异常，请重试"
                        }
                    }
                },
                enabled = pwd.isNotEmpty() && !busy && lockedSeconds == 0
            ) {
                Text(
                    when {
                        lockedSeconds > 0 -> "稍后再试（${lockedSeconds}s）"
                        busy -> "订阅中…"
                        else -> "订阅"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
