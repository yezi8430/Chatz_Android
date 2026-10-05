package asia.guojuice.yezigotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import asia.guojuice.yezigotify.capabilities.ChatzApi
import asia.guojuice.yezigotify.capabilities.ChatzSendBody
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.db.LocalIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知上的「回复」动作
 *
 * 刻意不走 ViewModel：通知被回复时 App 进程可能已经是死的（用户从状态栏直接回），
 * 这时没有 ViewModel 可用。所以这里自己读配置、直接发请求。
 * 目标服务器 / 频道 / 应用都随通知的 Intent 带过来（见 GotifyService.replyAction）。
 *
 * 发送成功后**不做本地插入** —— 和 App 内发送一样，等消息入库靠服务端 WS 回声
 * （或下次同步）。
 *
 * ⚠️ 需要在 AndroidManifest.xml 里登记（见交付说明）：
 *   `<receiver android:name=".ReplyReceiver" android:exported="false" />`
 *   exported=false 是安全的：投递靠的是我们这个 PendingIntent 自带的身份。
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val remoteChannelId = intent.getIntExtra(EXTRA_CHANNEL_ID, 0)
        val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
        if (text.isEmpty() || serverId.isNullOrEmpty() || remoteChannelId == 0) return

        // goAsync：onReceive 返回后进程可能被回收，拿住这个 token 直到请求做完
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val server = ServerConfigStore.byId(context, serverId)
                if (server == null || server.url.isBlank() || server.token.isBlank()) {
                    AppLog.w(TAG, "服务器配置不可用，放弃回复: $serverId")
                    return@launch
                }

                // 登记"这是我刚发的"（要在请求之前）：回声到达时不再给自己弹通知。
                // 回声里带的是**本地**频道 id，所以这里也要转成本地 id
                SelfSentMessages.mark(
                    serverId,
                    LocalIds.makeChannelId(server.slot, remoteChannelId),
                    text
                )

                val result = ChatzApi.getInstance(server.url, server.token).sendMessage(
                    ChatzSendBody(
                        message = text,
                        channelId = remoteChannelId,
                        // 用原消息的应用署名，回复看起来才像同一个人说的
                        appId = appId.takeIf { it >= 0 },
                        extras = replyExtras(intent)
                    )
                )
                AppLog.d(
                    TAG,
                    "通知快捷回复 -> [$serverId] 频道 $remoteChannelId: " +
                            (if (result == null) "失败" else "ok(id=${result.id})")
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "通知快捷回复失败: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 回复的 extras：带上"回复的是哪条 + 它的摘要"
     *
     * 服务端**没有任何回复/父子关系**（`POST /message` 没有 reply_to，消息表也没有父指针），
     * 而 extras 是 Gotify 专门留给客户端自定义数据的扩展点、会原样透传并存下来，
     * 所以引用关系放这里：
     *   - 本 App 的卡片据此渲染一块引用条（MessageCard.extractReplyQuote）
     *   - 服务端自带的 WebUI 不认识这些 key，只会显示正文，不会出错
     *
     * 摘要随消息一起带上、而不是只带 id 让客户端回查 —— 这样原消息即使不在本地
     * （被裁剪 / 换了设备）也照样显示得出引用内容。
     */
    private fun replyExtras(intent: Intent): Map<String, Any>? {
        val id = intent.getLongExtra(EXTRA_REPLY_TO_ID, 0L)
        val preview = intent.getStringExtra(EXTRA_REPLY_TO_TEXT).orEmpty()
        if (id == 0L && preview.isBlank()) return null
        return buildMap {
            if (id != 0L) put("reply_to", id)
            if (preview.isNotBlank()) put("reply_to_text", preview)
        }
    }

    companion object {
        private const val TAG = "ReplyReceiver"

        /** RemoteInput 的 key，GotifyService 与这里必须一致 */
        const val KEY_REPLY = "reply_text"
        const val EXTRA_SERVER_ID = "server_id"
        /** **远程**频道 id（发送方在 Intent 里已经换过） */
        const val EXTRA_CHANNEL_ID = "remote_channel_id"
        const val EXTRA_APP_ID = "app_id"
        /** 被回复那条消息的**远程** id */
        const val EXTRA_REPLY_TO_ID = "reply_to_id"
        /** 被回复那条消息的正文摘要（截断过） */
        const val EXTRA_REPLY_TO_TEXT = "reply_to_text"
    }
}