package asia.guojuice.yezigotify.config

import java.util.UUID

/**
 * 服务卡类型：用户在「添加服务」时选择的类型
 *
 * ⚠️ 不要和 capabilities/ServerCapabilities.kt 里的 ServerType 混用：
 *   - ServerKind  = 用户选的卡类型（"我要加一台 Chatz / Gotify / Ntfy"）
 *   - ServerType  = 探测出来的协议身份（可能是 UNKNOWN）
 * 两者语义不同，ServerType 保持原样不动。
 */
enum class ServerKind { CHATZ, GOTIFY, NTFY }

/** 鉴权方式 */
enum class AuthMode { TOKEN, USER_PASSWORD }

/**
 * 一台服务器的配置（"一张服务卡"）
 *
 * 存储：由 [ServerConfigStore] 以 JSON 数组形式存进 SharedPreferences 的
 * servers_json，不建 Room 表（读取点都在冷启动早期路径，需要同步可读）。
 *
 * @param id           稳定 id（UUID）。旧配置迁移出来的为常量
 *                     [ServerConfigStore.LEGACY_SERVER_ID] / [ServerConfigStore.LEGACY_NTFY_SERVER_ID]
 * @param slot         id 命名空间槽位，0..127。旧配置 = 0（slot 0 时
 *                     `本地id == 远程id`，这是"旧数据零改写"的支点）
 * @param kind         卡类型
 * @param enabled      启用/暂停开关；暂停 = 断开连接但保留配置
 * @param url          服务器地址
 * @param authMode     鉴权方式（TOKEN / USER_PASSWORD）
 * @param token        TOKEN 模式用；Chatz 用户名密码登录成功后也会回填到这里
 * @param username     USER_PASSWORD 模式用
 * @param password     USER_PASSWORD 模式用（Chatz 登录成功后不保留，见 ServerAuth）
 * @param topic        仅 NTFY：订阅主题
 * @param keepAliveSeconds 保活间隔秒数；0 = 自动（WiFi 45s / 移动 180s）
 * @param displayName  自定义显示名；空 = 默认（类型标签 + 域名）
 */
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val slot: Int = 0,
    val kind: ServerKind = ServerKind.GOTIFY,
    val enabled: Boolean = true,
    val url: String = "",
    val authMode: AuthMode = AuthMode.TOKEN,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val topic: String = "",
    val keepAliveSeconds: Int = 0,
    val displayName: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 协议类服务器（Chatz / Gotify 共用同一套 HTTP + WS 协议） */
    val isProtocolServer: Boolean get() = kind != ServerKind.NTFY

    /** 是否是 ntfy 卡（ntfy 没有频道/应用概念，只有订阅主题） */
    val isNtfy: Boolean get() = kind == ServerKind.NTFY

    /**
     * 凭据是否填齐（用于"已配置/未配置"状态与是否值得发起连接）
     *
     * 注意：USER_PASSWORD 模式下 Chatz 会先登录换 token，所以这里对
     * "凭据齐备"的判断要同时接受 token 与 用户名+密码。
     */
    val hasCredentials: Boolean
        get() = when (authMode) {
            AuthMode.TOKEN -> token.isNotBlank()
            AuthMode.USER_PASSWORD -> username.isNotBlank() && password.isNotBlank()
        }

    /** 地址 + 凭据都齐了才算可用 */
    val isUsable: Boolean
        get() = url.isNotBlank() && hasCredentials &&
                (kind != ServerKind.NTFY || topic.isNotBlank())
}