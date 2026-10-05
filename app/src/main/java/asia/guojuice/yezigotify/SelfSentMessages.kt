package asia.guojuice.yezigotify

import java.util.concurrent.ConcurrentHashMap

/**
 * 本机刚发出去的消息指纹
 *
 * 服务端会把每条新消息（**包括自己刚发的那条**）经 WS 广播回来。收到时按这里判断
 * "这是我发的" → 不再给自己弹通知（你刚在通知栏里回完一条，不该又被它弹一次）。
 *
 * 为什么用 (serverId, 本地channelId, 正文) 指纹，而不是消息 id：
 *   服务端是**先 WS 广播、后回 HTTP 响应**（messageCreate 里 broadcast 在 return 之前），
 *   所以回声很可能比响应先到 —— 那一刻还没有 id 可用。
 *   指纹在请求**发出之前**就登记好，回声先到后到都能命中。
 *
 * 代价：同一频道 30 秒内若真有另一条**正文完全相同**的消息，那条也不会弹通知。
 * 现实里基本不会发生，后果也只是少弹一次。
 */
object SelfSentMessages {

    private const val TTL_MS = 30_000L

    private data class Fingerprint(val serverId: String, val localChannelId: Int, val bodyHash: Int)

    private val recent = ConcurrentHashMap<Fingerprint, Long>()

    /** 必须在**发送前**调用 */
    fun mark(serverId: String, localChannelId: Int, body: String) {
        purge()
        recent[Fingerprint(serverId, localChannelId, body.hashCode())] = System.currentTimeMillis()
    }

    /**
     * WS 收到消息时调用
     *
     * @param localChannelId 注意要传**本地** id（连接层 normalize 之后的值）
     * @return true = 这是本机刚发的，已消费（同一条只抑制一次）
     */
    fun consume(serverId: String, localChannelId: Int?, body: String): Boolean {
        val ch = localChannelId ?: return false
        val at = recent.remove(Fingerprint(serverId, ch, body.hashCode())) ?: return false
        return System.currentTimeMillis() - at <= TTL_MS
    }

    private fun purge() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        recent.entries.removeIf { it.value < cutoff }
    }
}