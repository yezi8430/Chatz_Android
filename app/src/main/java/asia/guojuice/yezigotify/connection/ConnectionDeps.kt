package asia.guojuice.yezigotify.connection

import android.content.Context
import asia.guojuice.yezigotify.GotifyMessage
import asia.guojuice.yezigotify.config.ServerConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * 连接层共享依赖
 *
 * 为什么把回调打包成一个对象，而不是让每个连接直接持有 GotifyService：
 *   - 连接需要 Service 的 Context / OkHttpClient / 协程作用域，但不需要 Service 的
 *     通知代码、数据库代码 —— 用接口收口可以避免 connection 包反向依赖 Service 的全部实现
 *   - 一条共享 OkHttpClient + N 条 WebSocket：连接池 / DNS 缓存 / 线程池复用
 *     （沿用原先 buildHttpClient() 的注释意图）
 *
 * ⚠️ 铁律：跨进程边界（HTTP / WS）的 id 一律在进入 [onMessage] / [onChatzEvent] /
 *    [onRemoteDeleted] **之前**转成本地 id；进程内一律用本地 id。
 */
class ConnectionDeps(
    val appContext: Context,
    val client: OkHttpClient,
    val scope: CoroutineScope,
    val gson: Gson,

    /** 当前是否有可用网络（由 GotifyService 的 NetworkCallback 维护） */
    val isNetworkAvailable: () -> Boolean,

    /** 收到一条消息。msg 的 `id`(本地) / `serverId` / `remoteId` 均已填好 */
    val onMessage: (ServerConfig, GotifyMessage) -> Unit,

    /** 收到一条 Chatz 扩展事件（原始 JSON，id 仍是服务端原始值，由 ViewModel 按 slot 转换） */
    val onChatzEvent: (ServerConfig, String) -> Unit,

    /** 服务端删除了某条消息；参数是**本地**消息 id */
    val onRemoteDeleted: (ServerConfig, Long) -> Unit,

    /**
     * 服务端清空了某个 topic / 频道（ntfy 的 `message_clear`）
     *
     * 与 [onRemoteDeleted] 的区别：清空事件**不带 id**，语义是「这一路来源的消息
     * 全部作废」，所以上层要按 serverId 整批清，而不是删某一行。
     * ntfy 之外没有协议推这种事件，所以默认空实现，协议连接不用关心。
     */
    val onTopicCleared: (ServerConfig) -> Unit = {},

    /** 某条连接的状态发生变化 */
    val onStateChanged: (String, ConnectionState) -> Unit
)