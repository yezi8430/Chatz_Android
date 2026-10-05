package asia.guojuice.yezigotify.connection

import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.config.ServerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 多服务器连接管理器
 *
 * 一台服务器 = 一条 [ServerConnection]。本类只负责"根据配置列表增删连接"，
 * 不做任何业务（入库 / 通知 / 广播都在 GotifyService）。
 *
 * 差分规则（[applyConfigs]）：
 *
 * | 情况 | 动作 |
 * |---|---|
 * | 不在新列表 / 不可用 | stop() + 移除 |
 * | 新增 serverId | 建连接 + start() |
 * | 存在且连接参数全等 | 完全不动（不重连，避免抖动） |
 * | 存在但地址/凭据/类型/槽位变了 | 只 stop() + 重建这一条 |
 * | 只有 keepAliveSeconds / displayName 变了 | 只更新元信息 + restartHeartbeat() |
 */
class ConnectionManager(private val deps: ConnectionDeps) {

    private val tag = "ConnectionManager"
    private val mutex = Mutex()

    private val conns = ConcurrentHashMap<String, ServerConnection>()

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** 每条连接的状态（key = serverId） */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    /** 汇总态变化回调（由 GotifyService 转发到广播） */
    var onAggregateChanged: ((ConnectionState) -> Unit)? = null

    // ==================== 查询 ====================

    fun connectionCount(): Int = conns.size

    fun aggregateState(): ConnectionState = ConnectionState.aggregate(conns.values.map { it.state })

    // ==================== 差分应用 ====================

    suspend fun applyConfigs(configs: List<ServerConfig>) {
        mutex.withLock {
            // 未启用的卡不建立连接（isUsable 已含 enabled 判定之外的地址/凭据校验）
            val desired = configs.filter { it.enabled && it.isUsable }.associateBy { it.id }

            // 1. 停掉不再需要的
            for (id in conns.keys.toList()) {
                if (id !in desired.keys) {
                    conns.remove(id)?.stop()
                    AppLog.d(tag, "停止并移除连接: $id")
                }
            }

            // 2. 新增 / 更新
            for (cfg in desired.values) {
                val existing = conns[cfg.id]
                when {
                    existing == null -> {
                        val conn = create(cfg)
                        conns[cfg.id] = conn
                        AppLog.d(tag, "新建连接: ${cfg.id} (${cfg.kind})")
                        conn.start()
                    }

                    !sameConnectionParams(existing.config, cfg) -> {
                        conns.remove(cfg.id)
                        existing.stop()
                        val conn = create(cfg)
                        conns[cfg.id] = conn
                        AppLog.d(tag, "连接参数变化，重建: ${cfg.id}")
                        conn.start()
                    }

                    else -> {
                        // 只有保活间隔 / 显示名变了 → 不断 WebSocket
                        val keepAliveChanged =
                            existing.config.keepAliveSeconds != cfg.keepAliveSeconds
                        existing.updateMeta(cfg)
                        if (keepAliveChanged) {
                            AppLog.d(tag, "保活间隔变化，仅重启心跳: ${cfg.id}")
                            existing.restartHeartbeat()
                        }
                    }
                }
            }

            publishStates()
            onAggregateChanged?.invoke(aggregateState())
        }
    }

    /** 全部停止（Service 销毁时调用） */
    fun stopAll() {
        for (id in conns.keys.toList()) {
            conns.remove(id)?.stop()
        }
        publishStates()
    }

    fun onNetworkLost() {
        conns.values.forEach { it.onNetworkLost() }
        publishStates()
        onAggregateChanged?.invoke(aggregateState())
    }

    fun onNetworkRestored() {
        conns.values.forEach { it.onNetworkRestored() }
    }

    // ==================== 内部 ====================

    /**
     * 由 ConnectionDeps.onStateChanged 调用
     *
     * 注意：这里**不能**用 mutex —— 状态回调发生在 OkHttp 的线程池里，
     * 若此时 applyConfigs 持锁等待 stop()，就会互相等待。
     * 状态发布本身只需读写 ConcurrentHashMap，无需加锁。
     */
    fun onConnectionStateChanged(serverId: String, state: ConnectionState) {
        publishStates()
        onAggregateChanged?.invoke(aggregateState())
        AppLog.d(tag, "连接状态: $serverId → $state")
    }

    private fun publishStates() {
        val snapshot = conns.mapValues { it.value.state }
        if (_states.value != snapshot) _states.value = snapshot
    }

    private fun create(cfg: ServerConfig): ServerConnection =
        if (cfg.kind == ServerKind.NTFY) NtfyConnection(cfg, deps)
        else ProtocolConnection(cfg, deps)

    /**
     * 连接参数是否一致
     *
     * 刻意**不含** keepAliveSeconds / displayName / enabled —— 这几个变了不该断连接。
     */
    private fun sameConnectionParams(a: ServerConfig, b: ServerConfig): Boolean =
        a.url == b.url &&
                a.token == b.token &&
                a.username == b.username &&
                a.password == b.password &&
                a.topic == b.topic &&
                a.authMode == b.authMode &&
                a.kind == b.kind &&
                a.slot == b.slot
}