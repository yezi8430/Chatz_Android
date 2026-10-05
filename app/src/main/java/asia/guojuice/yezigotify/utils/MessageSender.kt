package asia.guojuice.yezigotify.utils

/**
 * 一条消息的发送者快照
 *
 * 约定：`extras["sender"] = {id, username, displayName, isAdmin, isSuper}` —— 由**服务端**在
 * 消息创建时写入（不是客户端传的，所以伪造不了）。webhook 发的消息没有登录用户，就没有这个字段。
 *
 * 为什么是快照而不是查当前用户：消息表没有 user_id 列，而且客户端展示读的是本地库，
 * 快照才不需要任何表结构迁移。代价是用户改名后旧消息仍显示当时的名字。
 *
 * ⚠️ [isSuper] 是 2026-09-30 加的（服务端 resolveSender 随两级角色一起补的）。
 *    老服务端不返回 → 默认 false → 显示成"管理员"，不会崩。
 */
data class SenderInfo(
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
    val isSuper: Boolean = false
) {
    /**
     * 展示用的一行标签
     *
     * 昵称与用户名相同时只给一个 —— 注册时 display_name 会回落成 username，
     * 那种情况下显示"张三 @张三"纯属噪音。
     */
    val label: String
        get() = if (displayName == username) displayName else "$displayName @$username"
}

/**
 * 解析 extras 里的发送者
 *
 * 卡片、聚合子消息、通知栏三处都用这一个实现 —— 它们显示的是同一条消息的同一个字段，
 * 各写一份就会出现"卡片有、通知没有"这种不一致（发送者刚加时就漏了通知）。
 */
object MessageSender {

    fun from(extras: Map<String, Any>?): SenderInfo? {
        val m = extras?.get("sender") as? Map<*, *> ?: return null
        val username = (m["username"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = (m["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: username
        return SenderInfo(
            username = username,
            displayName = displayName,
            isAdmin = m["isAdmin"] as? Boolean ?: false,
            isSuper = m["isSuper"] as? Boolean ?: false
        )
    }
}

/**
 * 一条消息的**应用快照**
 *
 * 约定：`extras["app"] = {id, name, image}` —— 由**服务端**在消息创建时写入
 * （不是客户端传的，所以伪造不了）。老服务端不返回 → null，降级回按 appid 查本地缓存。
 *
 * 为什么要快照：应用改成**按用户隔离**后，`GET /application` 只返回自己的应用，
 * 客户端再也查不到「别人应用」的名字/图标 —— 而订阅了别人频道的人**看得到那些消息**，
 * 卡片上就会只剩默认图标。快照进 extras 后，展示不再依赖应用列表。
 *
 * ⚠️ 只含 id / name / image，**不含 token**（服务端刻意不写，那是 webhook 凭据）。
 * ⚠️ [image] 是 `/icons/xxx.png` 这种**相对路径**，要配合服务器基址拼绝对 URL
 *    （用 `AppIconCache.resolveUrl(serverId, ...)`）。
 */
data class AppInfo(
    val id: Int,
    val name: String,
    val image: String?
) {
    companion object {
        fun from(extras: Map<String, Any>?): AppInfo? {
            val m = extras?.get("app") as? Map<*, *> ?: return null
            // Gson 把 JSON 数字解析成 Double，所以走 Number 再 toInt
            val id = (m["id"] as? Number)?.toInt() ?: return null
            return AppInfo(
                id = id,
                name = (m["name"] as? String).orEmpty(),
                image = (m["image"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}
