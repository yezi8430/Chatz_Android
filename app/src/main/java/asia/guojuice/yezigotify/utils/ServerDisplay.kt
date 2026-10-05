package asia.guojuice.yezigotify.utils

import android.content.Context
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.capabilities.ServerType
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.config.ServerKind

/**
 * 服务器显示文字的统一实现
 *
 * 抽取原因：
 *   原先 MainActivity 和 SettingsScreen 各有一份 extractDomain + getServerDisplayText，
 *   逻辑重复、未来修改要动两处，且两处实现已经出现不一致：
 *     - 抽屉头部显示：多服务用换行分隔，未配置显示"未配置服务器"
 *     - 设置页副标题：多服务用 " + "分隔，未配置显示"未配置"
 *   这不是 bug，而是两处场景对排版的不同需求。
 *
 *   因此本类：
 *     - 把逻辑收敛到一份代码
 *     - 用参数 separator / emptyText 保留两处的显示差异
 *   后续修改只需改这里，两处自动同步。
 *
 * 多服务器改造后：
 *   - 全部读取都走 [ServerConfigStore]（支持任意多台），不再读旧 prefs key
 */
object ServerDisplay {

    /**
     * 从 URL 中提取主域名
     * 例：https://gotify.example.com:8080/path  →  example.com
     *
     * 规则：取最后两段（example.com）；
     *       单段域名（localhost）直接返回；
     *       空串返回空串。
     */
    fun extractDomain(url: String): String {
        if (url.isEmpty()) return ""
        var s = url.trim().lowercase()
        if (s.startsWith("http://")) s = s.substring(7)
        if (s.startsWith("https://")) s = s.substring(8)
        s = s.substringBefore('/').substringBefore(':')
        val parts = s.split('.')
        return when {
            parts.size >= 2 -> parts.takeLast(2).joinToString(".")
            parts.size == 1 && parts[0].isNotEmpty() -> parts[0]
            else -> s
        }
    }

    /** 卡类型 → 标签（用户添加服务时选的类型） */
    fun kindLabel(kind: ServerKind): String = when (kind) {
        ServerKind.CHATZ -> "Chatz"
        ServerKind.GOTIFY -> "Gotify"
        ServerKind.NTFY -> "Ntfy"
    }

    /**
     * 卡片 / 抽屉分组标题旁边那个类型徽标要不要显示
     *
     * ⚠️ 为什么要这个判断：[labelOf] 在 `displayName` 为空时**自己就带了类型前缀**
     *    （"Chatz(域名)"）。此时再并排一个类型徽标纯属重复，会显示成
     *    「Chatz(GUOjuice.asia)  Chatz」—— 2026-10-03 用户反馈的正是这个。
     *
     *    只有用户自己起了名字（`displayName` 非空）时那个徽标才有信息量：
     *    它告诉你"这台到底是 Gotify 还是 Chatz"。
     */
    fun showsKindBadge(config: ServerConfig): Boolean = config.displayName.isNotBlank()

    /**
     * 类型徽标的文字；**不需要显示时返回空串**
     *
     * 给那些只拿到 String、拿不到 config 的地方用（比如抽屉的 DrawerServer）——
     * 调用方只要 `if (kindLabel.isNotBlank())` 就够了，不必再传一个 config 下去。
     */
    fun kindBadge(config: ServerConfig): String =
        if (showsKindBadge(config)) kindLabel(config.kind) else ""

    /**
     * 单张服务卡的显示名
     *
     * 规则：displayName 非空则直接用；否则 "类型标签(域名)"，域名为空时只显示类型标签。
     *
     * 一个例外：旧配置迁移出来的协议服务器（[ServerConfigStore.LEGACY_SERVER_ID]）
     * 的类型优先取**探测结果** —— Chatz 是 Gotify 的分支，同一套协议，
     * 用户当初并没有"选类型"这一步，探测结果更准。
     * 新加的卡一律用用户选的 kind（探测结果只用于决定是否启用 Chatz 扩展能力）。
     */
    fun labelOf(config: ServerConfig): String {
        if (config.displayName.isNotBlank()) return config.displayName

        val base = when {
            config.kind == ServerKind.NTFY -> "Ntfy"
            config.id == ServerConfigStore.LEGACY_SERVER_ID &&
                    ServerCapabilitiesHolder.getServerType() == ServerType.CHATZ -> "Chatz"
            else -> kindLabel(config.kind)
        }

        val domain = extractDomain(config.url)
        return if (domain.isEmpty()) base else "$base($domain)"
    }

    /**
     * 组装"服务器显示文字"（多服务器版）
     *
     * 只为**已启用且可用**的服务器生成条目，按配置顺序用 [separator] 拼接。
     */
    fun getServerDisplayText(
        configs: List<ServerConfig>,
        separator: String = "\n",
        emptyText: String = "未配置服务器"
    ): String {
        val parts = configs
            .filter { it.enabled && it.isUsable }
            .map { labelOf(it) }
        return if (parts.isEmpty()) emptyText else parts.joinToString(separator)
    }

    /**
     * 按 Context 读取「服务器显示文字」（读 [ServerConfigStore]）
     */
    fun getServerDisplayText(
        context: Context,
        separator: String = "\n",
        emptyText: String = "未配置服务器"
    ): String = getServerDisplayText(ServerConfigStore.all(context), separator, emptyText)
}