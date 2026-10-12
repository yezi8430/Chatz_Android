package asia.guojuice.yezigotify.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DeepLinkOpener] 里的**纯函数**部分：从 Jellyfin 详情页地址里抠条目 id
 *
 * 为什么要钉住：这个 id 会被塞进 `ACTION_VIEW` 的 data 去唤起 Jellyfin（TV 端），
 * 抠错了就会打开一个不存在的条目、或者把别的链接误判成 Jellyfin 深链。
 * 注意 id 在 **fragment** 里（`#/details?id=…`），不是普通 query。
 */
class DeepLinkOpenerTest {

    @Test
    fun itemIdOf_pluginUrlFormat() {
        // 插件的实际形态：id 在 #/details? 之后
        assertEquals(
            "007ae04ac60f734757fe312272afe5cc",
            DeepLinkOpener.jellyfinItemIdOf(
                "https://jellyfin.guojuice.asia/web/#/details?id=007ae04ac60f734757fe312272afe5cc"
            )
        )
    }

    @Test
    fun itemIdOf_withExtraQueryParams() {
        // 后面还挂着别的参数时也要只取 id
        assertEquals(
            "805ed2dadb636a5ad5674fd4d95e0f46",
            DeepLinkOpener.jellyfinItemIdOf(
                "https://host/web/#/details?id=805ed2dadb636a5ad5674fd4d95e0f46&serverId=abc"
            )
        )
    }

    @Test
    fun itemIdOf_plainQueryFormat() {
        // 兼容 ?id= 直接跟在主 URL 后面的写法（有些换肤/旧版本是这样）
        assertEquals(
            "007ae04ac60f734757fe312272afe5cc",
            DeepLinkOpener.jellyfinItemIdOf("https://host/Items?id=007ae04ac60f734757fe312272afe5cc")
        )
    }

    @Test
    fun itemIdOf_rejectsNonJellyfinUrls() {
        assertNull(DeepLinkOpener.jellyfinItemIdOf("https://example.com"))
        assertNull(DeepLinkOpener.jellyfinItemIdOf("https://example.com/?q=hello"))
        assertNull(DeepLinkOpener.jellyfinItemIdOf("https://example.com/?id=not-a-guid"))
        // 太短 / 太长 / 带非十六进制字符都不认
        assertNull(DeepLinkOpener.jellyfinItemIdOf("https://host/#/details?id=abc123"))
        assertNull(DeepLinkOpener.jellyfinItemIdOf("https://host/#/details?id=007ae04ac60f734757fe312272afe5ccZZ"))
    }
}
