package asia.guojuice.yezigotify.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalIds] 的位运算不变量
 *
 * 为什么值得钉住：这些性质一旦破了，表现是**消息串到别的服务器 / 静默丢失 /
 * 频道图标与通知配置查不到**，界面上完全看不出来。项目已经真踩过一次 ——
 * WS 入站的 channelId 漏做 makeChannelId(slot, ...)，导致只有 slot > 0 的
 * 服务器上「不接收通知」静默失效。
 *
 * 纯函数，不需要 Context / 数据库，直接在 JVM 上跑。
 */
class LocalIdsTest {

    // ==================== 消息 ====================

    @Test
    fun messageId_slot0_isIdentity() {
        // slot 0 是旧配置迁移出来的那台服务器：本地 id 必须等于远程 id，
        // 否则旧库里的消息、FTS 的 rowid 关联、通知 id、widget 点击 id 全对不上
        assertEquals(123L, LocalIds.makeMessageId(0, 123L))
        assertEquals(0L, LocalIds.makeMessageId(0, 0L))
    }

    @Test
    fun messageId_roundTrip() {
        val remote = 987_654_321L
        assertEquals(remote, LocalIds.remoteIdOf(LocalIds.makeMessageId(3, remote)))
    }

    @Test
    fun messageId_isIdempotent() {
        // 现实中它会被连着调用两次：连接层转一次、入库时又转一次。
        // 不幂等的话入库的 id 会错位，消息就落到错误的命名空间里去了
        val once = LocalIds.makeMessageId(3, 42L)
        assertEquals(once, LocalIds.makeMessageId(3, once))
    }

    @Test
    fun messageId_slot127_staysPositive() {
        // slot 127 左移 56 位正好占满 Long 的最高正位；再大就会翻成负数，
        // 而负 id 在很多地方会被当成「无效 id」
        assertTrue(LocalIds.makeMessageId(127, 1L) > 0L)
    }

    // ==================== 频道 ====================

    @Test
    fun channelId_slot0_isIdentity() {
        assertEquals(7, LocalIds.makeChannelId(0, 7))
    }

    @Test
    fun channelId_roundTripAndIdempotent() {
        val remote = 7
        val local = LocalIds.makeChannelId(2, remote)
        assertEquals(remote, LocalIds.remoteChannelIdOf(local))
        assertEquals(local, LocalIds.makeChannelId(2, local))
    }

    @Test
    fun channelId_differentSlotsDoNotCollide() {
        // 碰撞的后果：A 服务器的消息被当成 B 服务器的同一个频道 —— 频道图标、
        // 通知配置、静音状态会全部串台
        assertNotEquals(LocalIds.makeChannelId(1, 7), LocalIds.makeChannelId(2, 7))
    }

    @Test
    fun channelId_slot127_staysPositive() {
        assertTrue(LocalIds.makeChannelId(127, 1) > 0)
    }

    // ==================== 应用筛选 ====================

    @Test
    fun appFilter_slot0_isRawAppId() {
        // appid 刻意不做命名空间（messages.appid 保持服务端原值，
        // 因为别处靠 appid == 0 判断 ntfy），只在 UI 侧合成，
        // 所以 slot 0 时必须等于原始 appid
        assertEquals(5, LocalIds.makeAppFilterId(0, 5))
    }

    @Test
    fun appFilter_roundTripAndSlot() {
        val filter = LocalIds.makeAppFilterId(2, 5)
        assertEquals(5, LocalIds.appIdOf(filter))
        assertEquals(2, LocalIds.slotOfAppFilter(filter))
    }

    // ==================== ntfy ====================

    @Test
    fun ntfyId_isStableAndNonNegative() {
        // 负 id 会被当成无效；而且这个值必须**确定**，否则每次收到同一条 ntfy
        // 消息都会当成新消息、插一行重复记录
        val a = LocalIds.ntfyLocalId(0, "abc123")
        assertTrue(a > 0L)
        assertEquals(a, LocalIds.ntfyLocalId(0, "abc123"))
    }

    @Test
    fun ntfyId_isNamespacedBySlot() {
        assertNotEquals(
            LocalIds.ntfyLocalId(0, "abc123"),
            LocalIds.ntfyLocalId(1, "abc123")
        )
    }
}