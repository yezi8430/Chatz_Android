package asia.guojuice.yezigotify.ntfy

/**
 * ntfy 单条通知的数据模型（与 ntfy /json SSE 字段对应）
 */
data class NtfyMessage(
    val id: String = "",
    val sequenceId: String? = null,
    val time: Long = 0,
    val event: String = "",
    val topic: String = "",
    val message: String = "",
    val title: String = "",
    val priority: Int = 0,
    val tags: List<String> = emptyList(),
    val imageUrl: String? = null,          // 图片 URL（ntfy 的 "image" 字段）
    val icon: String? = null,
    // 新增附件字段（可选）
    val attachment: Attachment? = null,
) {
    data class Attachment(
        val name: String?,
        val url: String?,
        val size: Long?,
        val type: String?
    )
}