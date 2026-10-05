package asia.guojuice.yezigotify

import android.content.Context
import com.google.gson.Gson

/**
 * 发送草稿的本地存储（按频道）
 *
 * 存的是"这台服务器这个频道里还没发出去的内容"。不落盘的后果是切个频道、切个视图
 * （甚至只是进程被系统杀掉）打了一半的字就没了。
 *
 * ⚠️ 持久化用**独立的 DTO**（字段全可空）而不是直接存 [OutgoingMessage]：
 *    Gson 走 Unsafe 绕开构造函数，字段缺失时会留 null 而不是取 Kotlin 的默认值。
 *    哪天给 OutgoingMessage 加了字段，直接反序列化旧数据就会把 null 塞进非空属性，
 *    等真正用到时才 NPE。全可空的 DTO + 显式兜底就没这个隐患。
 */
object DraftStore {

    private const val PREFS_NAME = "gotify_drafts"

    private val gson = Gson()

    private fun key(serverId: String, channelId: Int) = "$serverId|$channelId"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全可空：见类注释 */
    private data class DraftDto(
        val text: String? = null,
        val title: String? = null,
        val priority: Int? = null,
        val tags: List<String>? = null,
        val replyToRemoteId: Long? = null,
        val replyToText: String? = null,
        val attachmentUrl: String? = null,
        val attachmentName: String? = null,
        val attachmentSize: Long? = null,
        val attachmentIsImage: Boolean? = null,
        val attachmentContentType: String? = null
    )

    /** @return null = 没有草稿（或数据坏了）；空草稿等同于没有 */
    fun load(context: Context, serverId: String, channelId: Int): OutgoingMessage? {
        val json = prefs(context).getString(key(serverId, channelId), null) ?: return null
        return decode(json)
    }

    /**
     * JSON → 草稿
     *
     * 刻意抽成**不碰 Context 的纯函数**：单测里 `getSharedPreferences` 会抛 `Stub!`，
     * 混在一起就没法验证下面这些兜底到底对不对。
     *
     * @return null = 数据坏了，或是个什么都没填的草稿
     */
    fun decode(json: String): OutgoingMessage? {
        val dto = runCatching { gson.fromJson(json, DraftDto::class.java) }.getOrNull() ?: return null

        val draft = OutgoingMessage(
            text = dto.text.orEmpty(),
            title = dto.title,
            priority = dto.priority,
            tags = dto.tags.orEmpty(),
            replyToRemoteId = dto.replyToRemoteId,
            replyToText = dto.replyToText,
            attachment = dto.attachmentUrl?.let { url ->
                UploadedAttachment(
                    url = url,
                    name = dto.attachmentName.orEmpty().ifBlank { "附件" },
                    size = dto.attachmentSize ?: 0L,
                    isImage = dto.attachmentIsImage ?: false,
                    contentType = dto.attachmentContentType
                )
            }
        )
        // 什么都没填的草稿不值得恢复（也顺手把脏数据清掉）
        return draft.takeIf { it.text.isNotBlank() || it.attachment != null || it.isReply }
    }

    /** 草稿 → JSON（同样是不碰 Context 的纯函数，便于单测） */
    fun encode(draft: OutgoingMessage): String = gson.toJson(
        DraftDto(
            text = draft.text,
            title = draft.title,
            priority = draft.priority,
            tags = draft.tags,
            replyToRemoteId = draft.replyToRemoteId,
            replyToText = draft.replyToText,
            attachmentUrl = draft.attachment?.url,
            attachmentName = draft.attachment?.name,
            attachmentSize = draft.attachment?.size,
            attachmentIsImage = draft.attachment?.isImage,
            attachmentContentType = draft.attachment?.contentType
        )
    )

    fun save(context: Context, serverId: String, channelId: Int, draft: OutgoingMessage) {
        runCatching {
            prefs(context).edit().putString(key(serverId, channelId), encode(draft)).apply()
        }.onFailure { AppLog.w("DraftStore", "草稿保存失败: ${it.message}") }
    }

    fun clear(context: Context, serverId: String, channelId: Int) {
        runCatching {
            prefs(context).edit().remove(key(serverId, channelId)).apply()
        }
    }
}
