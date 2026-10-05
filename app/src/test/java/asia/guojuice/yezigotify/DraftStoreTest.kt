package asia.guojuice.yezigotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送草稿的持久化（只测其中的纯函数）
 *
 * 背景：`load` / `save` 要 Context，而 JVM 单测里 SharedPreferences 是 stub、
 * 一调就抛 `Stub!`，混在一起就没法验证兜底逻辑。所以 DTO 的映射被抽成了
 * [DraftStore.encode] / [DraftStore.decode] 两个纯函数 ——
 * 这里测的是**生产代码本身**，不是一份副本。
 */
class DraftStoreTest {

    private fun attachment() = UploadedAttachment(
        url = "https://host/attachments/ab.png",
        name = "截图.png",
        size = 2048L,
        isImage = true,
        contentType = "image/png"
    )

    @Test
    fun roundTrip_keepsEveryField() {
        val draft = OutgoingMessage(
            text = "看图",
            title = "标题",
            priority = 9,
            tags = listOf("a", "b"),
            replyToRemoteId = 42L,
            replyToText = "被回复的内容",
            attachment = attachment()
        )
        assertEquals(draft, DraftStore.decode(DraftStore.encode(draft)))
    }

    @Test
    fun roundTrip_plainText() {
        val draft = OutgoingMessage(text = "只发一句话")
        assertEquals(draft, DraftStore.decode(DraftStore.encode(draft)))
    }

    @Test
    fun decode_missingFieldsUseSafeDefaults() {
        // 这条就是"为什么要单独写一个全可空 DTO"的理由：Gson 走 Unsafe 绕开构造函数，
        // 字段缺失时留的是 **null**，而不是 Kotlin 的默认值。哪天给 OutgoingMessage
        // 加了字段，直接反序列化旧数据就会把 null 塞进非空属性，等真正用到才 NPE。
        val draft = requireNotNull(DraftStore.decode("""{"text":"hi"}""")) { "应该能解析出草稿" }
        assertEquals("hi", draft.text)
        assertEquals(emptyList<String>(), draft.tags)   // 空列表，不是 null
        assertNull(draft.priority)                      // null = 用服务端默认值，不能被当成 0
        assertNull(draft.attachment)
        assertFalse(draft.isReply)
    }

    @Test
    fun decode_attachmentMissingMetaUsesDefaults() {
        // 附件只存了 url（老版本写的、或手工改过）时其余字段要有兜底。
        // name 尤其不能是空字符串 —— UI 会拿它当下载按钮的文字
        val draft = requireNotNull(DraftStore.decode("""{"attachmentUrl":"https://h/a.bin"}""")) {
            "只有附件也算有效草稿"
        }
        assertEquals("附件", draft.attachment?.name)
        assertEquals(0L, draft.attachment?.size)
        assertEquals(false, draft.attachment?.isImage)
        assertNull(draft.attachment?.contentType)
    }

    @Test
    fun decode_emptyDraftIsTreatedAsNoDraft() {
        // 全空草稿恢复出来没有任何意义（只会在输入框里留个空壳），等同于"没有草稿"
        assertNull(DraftStore.decode("{}"))
        assertNull(DraftStore.decode("""{"text":"   "}"""))
    }

    @Test
    fun decode_replyOnly_isNotEmpty() {
        // 只有引用、没打字也算有效草稿：用户可能只是还没来得及输入
        val draft = DraftStore.decode("""{"replyToText":"原消息"}""")
        assertNotNull(draft)
        assertTrue(draft!!.isReply)
    }

    @Test
    fun decode_brokenJsonReturnsNull() {
        // 数据坏了只能当"没有草稿"，绝不能让"读草稿"把 App 带崩
        assertNull(DraftStore.decode("{broken"))
        assertNull(DraftStore.decode("null"))
    }
}

/**
 * 本机刚发出的消息指纹
 *
 * ⚠️ [SelfSentMessages] 是单例、状态跨用例保留，所以每个用例都用**独立的
 * serverId**，否则用例之间会互相把对方的指纹消费掉。
 *
 * TTL（30 秒后失效）这里有意没测：验证它要么等 30 秒、要么给单例注入时钟，
 * 两样都不划算。真要覆盖，先把它改成 `class SelfSentMessages(private val now: () -> Long)`。
 */
class SelfSentMessagesTest {

    @Test
    fun consume_matchesSameFingerprintOnlyOnce() {
        val s = "srv-once"
        SelfSentMessages.mark(s, 1, "你好")
        // 同一条回声只该抑制一次通知：第二次再收到（重复推送）时，
        // 不该还被当成"自己发的"
        assertTrue(SelfSentMessages.consume(s, 1, "你好"))
        assertFalse(SelfSentMessages.consume(s, 1, "你好"))
    }

    @Test
    fun consume_ignoresOtherChannel() {
        val s = "srv-channel"
        SelfSentMessages.mark(s, 1, "你好")
        assertFalse(SelfSentMessages.consume(s, 2, "你好"))
        // 关键：上面这次失败的匹配不能把指纹顺手消费掉
        assertTrue(SelfSentMessages.consume(s, 1, "你好"))
    }

    @Test
    fun consume_ignoresOtherBody() {
        val s = "srv-body"
        SelfSentMessages.mark(s, 1, "你好")
        assertFalse(SelfSentMessages.consume(s, 1, "你好啊"))
    }

    @Test
    fun consume_ignoresOtherServer() {
        // serverId 必须参与指纹：两台服务器上本地频道 id 相同、内容也相同时，
        // 不能把对方的消息误当成自己刚发的
        SelfSentMessages.mark("srv-a", 1, "你好")
        assertFalse(SelfSentMessages.consume("srv-b", 1, "你好"))
    }

    @Test
    fun consume_nullChannelIsNeverSelfSent() {
        // channelId 为 null（老格式消息）时只能保守地不当成自己发的
        assertFalse(SelfSentMessages.consume("srv-null", null, "你好"))
    }

    @Test
    fun consume_withoutMarkIsFalse() {
        assertFalse(SelfSentMessages.consume("srv-never", 1, "没登记过"))
    }
}