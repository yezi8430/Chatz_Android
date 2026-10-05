package asia.guojuice.yezigotify.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务卡「能不能用」的判定
 *
 * `isUsable` 是"这张卡要不要发起连接"的**唯一闸口**（ConnectionManager.applyConfigs
 * 只看它）：判宽了会对着没配好的卡反复重连，判严了表现为"卡片看着配好了却一直不连"。
 */
class ServerConfigTest {

    private fun tokenCard(
        kind: ServerKind = ServerKind.CHATZ,
        url: String = "https://chatz.example.com",
        token: String = "tok"
    ) = ServerConfig(kind = kind, url = url, authMode = AuthMode.TOKEN, token = token)

    /** Token 模式：地址 + Token 都非空（空白串不算） */
    @Test
    fun tokenAuth_needsUrlAndToken() {
        assertTrue(tokenCard().isUsable)
        assertTrue(tokenCard().hasCredentials)

        assertFalse(tokenCard(url = "").isUsable)
        assertFalse(tokenCard(token = "").isUsable)
        assertFalse(tokenCard(token = "   ").isUsable)
        assertFalse(tokenCard(url = "   ").isUsable)
    }

    /** 用户名密码模式：两个字段都要有 */
    @Test
    fun userPasswordAuth_needsBothFields() {
        val base = ServerConfig(
            kind = ServerKind.CHATZ,
            url = "https://chatz.example.com",
            authMode = AuthMode.USER_PASSWORD
        )
        assertFalse(base.isUsable)
        assertFalse(base.hasCredentials)

        assertTrue(base.copy(username = "u", password = "p").isUsable)
        assertFalse(base.copy(username = "u").isUsable)
        assertFalse(base.copy(password = "p").isUsable)
    }

    /** ntfy 还必须有订阅主题 —— 没有主题就不知道该订阅什么 */
    @Test
    fun ntfy_needsTopic() {
        val ntfy = ServerConfig(
            kind = ServerKind.NTFY,
            url = "https://ntfy.sh",
            authMode = AuthMode.TOKEN,
            token = "tok"
        )
        assertFalse(ntfy.isUsable)
        assertTrue(ntfy.copy(topic = "mytopic").isUsable)
    }

    /** 类型判定：只有协议服务器（Chatz / Gotify）才走 HTTP + WS，ntfy 单独一条路 */
    @Test
    fun kindHelpers() {
        assertTrue(ServerConfig(kind = ServerKind.CHATZ).isProtocolServer)
        assertTrue(ServerConfig(kind = ServerKind.GOTIFY).isProtocolServer)
        assertFalse(ServerConfig(kind = ServerKind.NTFY).isProtocolServer)

        assertTrue(ServerConfig(kind = ServerKind.NTFY).isNtfy)
        assertFalse(ServerConfig(kind = ServerKind.GOTIFY).isNtfy)
    }
}