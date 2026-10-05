package asia.guojuice.yezigotify.utils

/**
 * 消息文本 / 图片地址的统一处理
 *
 * 抽取原因：这些规则原先散在 4 处，而且**图片地址的校验规则还不一致**：
 *   - MessageCard：`extras` 优先、正文 markdown 兜底；extras 里的 URL 要求 http 开头
 *   - GotifyService：同一套优先级，但 `as? String` 直接取、**不校验** http
 *   - MainScreen.replyPreview / MessageActionSheet：各自内联了一份剥图正则
 * 规则不一致的后果是同一张图"卡片不显示、通知却去加载"；重复三份则是一改就要改四处。
 * 统一到这里，以后只改一处。
 */
object MessageText {

    /**
     * markdown 图片语法 `![alt](url)`
     *
     * 刻意捕获 url 而不在校验时限制协议：剥除（[stripImageMarkdown]）要能剥掉任何协议的
     * 图片语法，取值（[imageUrlFromMarkdown]）才要求 http。
     */
    private val IMAGE_MD = Regex("!\\[.*?\\]\\((.*?)\\)")

    /**
     * 剥掉正文里的 markdown 图片语法
     *
     * 图片在卡片大图 / 通知大图 / 引用摘要处都有专属呈现，正文里再留一段 `![](...)`
     * 就是重复信息。
     */
    fun stripImageMarkdown(text: String): String = text.replace(IMAGE_MD, "").trim()

    /** 通知正文 / 引用摘要用的一行文本 */
    fun preview(text: String, max: Int): String = stripImageMarkdown(text).take(max)

    /**
     * 从 extras 里取图片地址，按 WebUI 的优先级：
     *   `image` → `client::display.url` → `client::notification.bigImageUrl`
     *
     * ⚠️ 一律要求 **http 开头**。extras 是外部可写的（webhook、别的客户端都能塞），
     *    不能把 `javascript:` / `file:` / `data:` 这类值直接喂给图片加载器。
     *    这是原先两份实现唯一的分歧点，统一取严的那份。
     */
    fun imageUrlFromExtras(extras: Map<String, Any>?): String? {
        if (extras == null) return null
        (extras["image"] as? String)?.takeIf { it.startsWith("http") }?.let { return it }
        (extras["client::display"] as? Map<*, *>)
            ?.get("url")
            ?.let { (it as? String)?.takeIf { u -> u.startsWith("http") } }
            ?.let { return it }
        (extras["client::notification"] as? Map<*, *>)
            ?.get("bigImageUrl")
            ?.let { (it as? String)?.takeIf { u -> u.startsWith("http") } }
            ?.let { return it }
        return null
    }

    /**
     * 正文里的 markdown 图片地址
     *
     * 服务端自带的 WebUI 发图时只往正文写 `![](url)`、**不带 extras**，所以这条兜底不能少。
     */
    fun imageUrlFromMarkdown(text: String): String? =
        IMAGE_MD.find(text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

    /**
     * 该展示的图片地址：extras 优先、正文 markdown 兜底
     *
     * 卡片和通知走的是**同一条规则**，别只改一边。
     */
    fun imageUrl(text: String, extras: Map<String, Any>?): String? =
        imageUrlFromExtras(extras) ?: imageUrlFromMarkdown(text)
}
