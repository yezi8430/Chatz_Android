package asia.guojuice.yezigotify.connection

/**
 * 单条连接的连接状态
 *
 * [PARTIAL] 只在**汇总态**里出现：多服务器下"有的连上有没连上"。
 * 单条连接自身永远不会是 PARTIAL。
 */
enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,

    /** 汇总态：部分服务器已连接、部分未连接 */
    PARTIAL;

    companion object {
        /**
         * 把多条连接的状态汇总成一个（`GotifyService.currentState` 用）
         *
         *   - 没有任何连接 → DISCONNECTED
         *   - 全部 CONNECTED → CONNECTED
         *   - 全部 DISCONNECTED → DISCONNECTED
         *   - 其余 → PARTIAL
         */
        fun aggregate(states: Collection<ConnectionState>): ConnectionState {
            if (states.isEmpty()) return DISCONNECTED
            if (states.all { it == CONNECTED }) return CONNECTED
            if (states.all { it == DISCONNECTED }) return DISCONNECTED
            return PARTIAL
        }
    }
}