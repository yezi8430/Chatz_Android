package asia.guojuice.yezigotify.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MessageText] 的规则
 *
 * 为什么值得钉住：这些规则原先散在 4 处、而且**图片地址的校验规则还不一致** ——
 * 后果是同一条消息「卡片不显示图、通知却去加载」。统一到这里之后，
 * 这些断言就是"别再把两边改分叉"的护栏。
 */
class MessageTextTest {

    // ==================== 剥除 markdown 图片 ====================

    @Test
    fun stripImageMarkdown_removesImageSyntax() {
        assertEquals("", MessageText.stripImageMarkdown("![](https://a/b.png)"))
    }

    @Test
    fun stripImageMarkdown_keepsSurroundingText() {
        assertEquals("看图", MessageText.stripImageMarkdown("看图\n![](https://a/b.png)"))
    }

    @Test
    fun stripImageMarkdown_leavesPlainTextAlone() {
        assertEquals("普通文本", MessageText.stripImageMarkdown("普通文本"))
    }

    @Test
    fun preview_stripsThenTruncates() {
        // 通知正文和引用摘要都走这里：先剥图、再截断，顺序不能反
        assertEquals("", MessageText.preview("![](https://a/b.png)", 50))
        assertEquals("看图", MessageText.preview("看图\n![](https://a/b.png)", 50))
        assertEquals("看图", MessageText.preview("看图说话", 2))
    }

    // ==================== extras 取图（含协议校验） ====================

    @Test
    fun imageUrlFromExtras_nullExtras() {
        assertNull(MessageText.imageUrlFromExtras(null))
    }

    @Test
    fun imageUrlFromExtras_readsImageKey() {
        val extras = mapOf<String, Any>("image" to "https://a/b.png")
        assertEquals("https://a/b.png", MessageText.imageUrlFromExtras(extras))
    }

    @Test
    fun imageUrlFromExtras_rejectsNonHttpSchemes() {
        // ⚠️ extras 是**外部可写**的（webhook 和任何持 token 的客户端都能往里塞），
        // 不能把 javascript: / file: / data: 这类值直接喂给图片加载器。
        // 这是原先两份实现唯一的分歧点，统一取严的那份 —— 别改回去
        assertNull(MessageText.imageUrlFromExtras(mapOf<String, Any>("image" to "javascript:alert(1)")))
        assertNull(MessageText.imageUrlFromExtras(mapOf<String, Any>("image" to "file:///etc/passwd")))
        assertNull(MessageText.imageUrlFromExtras(mapOf<String, Any>("image" to "data:image/png;base64,AAA")))
        // 相对路径也不是绝对地址，同样不加载
        assertNull(MessageText.imageUrlFromExtras(mapOf<String, Any>("image" to "/attachments/ab.png")))
    }

    @Test
    fun imageUrlFromExtras_fallsBackThroughKeys() {
        val display = mapOf<String, Any>("client::display" to mapOf("url" to "https://c/d.png"))
        assertEquals("https://c/d.png", MessageText.imageUrlFromExtras(display))

        val notif = mapOf<String, Any>("client::notification" to mapOf("bigImageUrl" to "https://e/f.png"))
        assertEquals("https://e/f.png", MessageText.imageUrlFromExtras(notif))
    }

    @Test
    fun imageUrlFromExtras_imageKeyWins() {
        // 优先级：image → client::display.url → client::notification.bigImageUrl
        val extras = mapOf<String, Any>(
            "image" to "https://win.png",
            "client::display" to mapOf("url" to "https://lose.png")
        )
        assertEquals("https://win.png", MessageText.imageUrlFromExtras(extras))
    }

    @Test
    fun imageUrlFromExtras_badValueFallsThroughInsteadOfGivingUp() {
        // image 是脏值时应该继续往后找，而不是直接返回 null ——
        // 否则后面那个合法地址就白白浪费了
        val extras = mapOf<String, Any>(
            "image" to "javascript:alert(1)",
            "client::display" to mapOf("url" to "https://ok.png")
        )
        assertEquals("https://ok.png", MessageText.imageUrlFromExtras(extras))
    }

    @Test
    fun imageUrlFromExtras_nonStringValueIsIgnored() {
        assertNull(MessageText.imageUrlFromExtras(mapOf<String, Any>("image" to 123)))
    }

    // ==================== 正文 markdown 兜底 ====================

    @Test
    fun imageUrlFromMarkdown_readsHttpUrl() {
        assertEquals("https://a/b.png", MessageText.imageUrlFromMarkdown("![](https://a/b.png)"))
    }

    @Test
    fun imageUrlFromMarkdown_rejectsNonHttp() {
        // 服务端 WebUI 发的图是绝对地址；相对路径多半是坏数据
        assertNull(MessageText.imageUrlFromMarkdown("![](./rel.png)"))
        assertNull(MessageText.imageUrlFromMarkdown("没有图片"))
    }

    @Test
    fun imageUrl_prefersExtrasThenMarkdown() {
        val extras = mapOf<String, Any>("image" to "https://ex.png")
        assertEquals("https://ex.png", MessageText.imageUrl("![](https://md.png)", extras))
        // extras 里没图时才回退正文 —— 服务端 WebUI 发图正是这种情况
        assertEquals("https://md.png", MessageText.imageUrl("![](https://md.png)", null))
    }
}