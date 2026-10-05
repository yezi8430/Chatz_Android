package asia.guojuice.yezigotify.utils

/**
 * 三种角色的徽标文案
 *
 * 服务端 2026-09-30 起把权限拆成两级（详见 README「三种角色」）：
 *   role 0 = 普通用户 / 1 = 管理员（只管应用和路由规则） / 2 = 超级管理员
 *
 * ⚠️ `isAdmin` 现在的语义是 **`role >= 1`**，不再等于"全知"。
 *    所以判断"是不是超级管理员"**必须看 `isSuper`**，不能用 isAdmin 代替 ——
 *    服务端收窄私有频道可见性时用的也是 isSuper（见 channelUpdated 广播）。
 *
 * 徽标在**账号页**（AccountDialogs）和**消息卡片**（MessageCard）两处都要显示，
 * 各写一份就会出现一处更新、另一处没跟上的情况（发送者徽标刚加时就漏了通知栏），
 * 所以统一收在这里。
 *
 * @return 普通用户返回 null（不显示徽标）
 */
fun roleLabel(isAdmin: Boolean, isSuper: Boolean): String? = when {
    isSuper -> "超级管理员"
    isAdmin -> "管理员"
    else -> null
}
