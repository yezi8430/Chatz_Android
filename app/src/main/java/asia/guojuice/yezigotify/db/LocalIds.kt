package asia.guojuice.yezigotify.db

/**
 * 本地 id 命名空间
 *
 * 多服务器后，各服务端的消息 / 频道 id 都各自从 1 开始，本地表必须能区分来源。
 * 方案：把「槽位 slot」编进 id 高位，形成确定性命名空间 ——
 *
 *   本地消息 id = (slot shl 56) or (remoteId and 0x00FF_FFFF_FFFF_FFFF)
 *   本地频道 id = (slot shl 24) or (remoteId and 0x00FF_FFFF)
 *
 * slot 0 保留给旧配置迁移出的服务器，此时 `本地 id == 远程 id`：
 * 旧数据零改写、FTS 的 rowid 关联不变、通知 id / PendingIntent / widget 点击 id 全都不变。
 *
 * ⚠️ 铁律：跨进程边界（HTTP API / WS 事件）一律做本地 id ↔ 远程 id 转换；
 *    进程内一律用本地 id。出站调用优先用 GotifyMessage.remoteId，不要手写位运算。
 */
object LocalIds {

    /** 消息远程 id 占低 56 位 */
    private const val MESSAGE_REMOTE_MASK = 0x00FF_FFFF_FFFF_FFFFL

    /** 频道 / 应用远程 id 占低 24 位 */
    private const val SHORT_REMOTE_MASK = 0x00FF_FFFF

    // ==================== 消息 ====================

    /** 远程消息 id → 本地消息 id */
    fun makeMessageId(slot: Int, remoteId: Long): Long =
        (slot.toLong() shl 56) or (remoteId and MESSAGE_REMOTE_MASK)

    /** 本地消息 id → 远程消息 id（发给服务端 API 用） */
    fun remoteIdOf(localMessageId: Long): Long = localMessageId and MESSAGE_REMOTE_MASK

    // ==================== 频道 ====================

    /** 远程频道 id → 本地频道 id；slot 0 时恒等 */
    fun makeChannelId(slot: Int, remoteChannelId: Int): Int =
        (slot shl 24) or (remoteChannelId and SHORT_REMOTE_MASK)

    /** 本地频道 id → 远程频道 id */
    fun remoteChannelIdOf(localChannelId: Int): Int = localChannelId and SHORT_REMOTE_MASK

    // ==================== 应用筛选 ====================

    /**
     * 抽屉里「应用」筛选用的合成 id
     *
     * messages.appid 刻意保持服务端原始值（不做命名空间，避免破坏 `appid == 0` 判 ntfy
     * 等隐式约定），只在 UI 侧把 slot 合进来。slot 0 时等于原始 appid。
     */
    fun makeAppFilterId(slot: Int, appId: Int): Int =
        (slot shl 24) or (appId and SHORT_REMOTE_MASK)

    /** 合成筛选 id → 原始 appid（拼 SQL 查 messages.appid 用） */
    fun appIdOf(appFilterId: Int): Int = appFilterId and SHORT_REMOTE_MASK

    /** 合成筛选 id → 槽位（UI 侧据此找到服务器，再用 serverId 查库） */
    fun slotOfAppFilter(appFilterId: Int): Int = appFilterId ushr 24

    // ==================== ntfy ====================

    /**
     * ntfy 消息的本地 id
     *
     * ntfy 的 id 是字符串（如 "abc123"），沿用旧版 `hashCode()` 非负值语义，
     * 只是把结果放进 slot 命名空间（slot 0 时与改造前的本地 id 完全相同）。
     */
    fun ntfyLocalId(slot: Int, ntfyId: String): Long {
        val hash = ntfyId.hashCode().toLong()
        val nonNegative = if (hash < 0) -hash else hash
        return makeMessageId(slot, nonNegative)
    }
}