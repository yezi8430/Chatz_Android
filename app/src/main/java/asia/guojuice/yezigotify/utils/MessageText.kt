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
 *
 * ⚠️ 2026-10-12 起：地址校验改认「http(s) 或**同源相对路径**」——
 *    服务端开了「缓存封面图」之后，封面会变成 `/attachments/cached-xxx.jpg`。
 *    取出相对路径后**必须**过 [MessageImage.resolve] 补全域名再交给加载器，
 *    否则图片加载器拿到不带域名的地址会直接失败（安卓端会表现为封面整个不显示）。
 */
object MessageText {

    /**
     * markdown 图片语法 `![alt](url)`
     *
     * 刻意捕获 url 而不在校验时限制协议：剥除（[stripImageMarkdown]）要能剥掉任何协议的
     * 图片语法，取值（[imageUrlFromMarkdown]）才做校验。
     */
    private val IMAGE_MD = Regex("!\\[.*?\\]\\((.*?)\\)")

    /**
     * 这个图片地址能不能用
     *
     * 只放行两类：
     *   · **绝对地址** http(s) —— 外链（Jellyfin 海报、图床…）
     *   · **同源相对路径**（以单个 `/` 开头）—— 服务端存的就是这种形态：
     *     附件 `/attachments/xxx.png`、「缓存封面图」抓下来的本地封面
     *     `/attachments/cached-xxxx.jpg` 也是。服务端不知道自己的对外域名
     *     （换域名 / 局域网 IP 都不该把消息里存死的地址搞坏），
     *     所以绝对地址由客户端拼 —— **取到之后必须过一遍 [MessageImage.resolve]**。
     *
     * 其余一律拒绝：extras 是**外部可写**的（webhook 和任何持 token 的客户端都能塞），
     * 不能把 `javascript:` / `file:` / `data:` 这类值直接喂给图片加载器。
     * `//evil.com/x.png`（协议相对）不是同源，照样拒。
     */
    private fun usableImageUrl(u: String): Boolean =
        u.startsWith("http://") || u.startsWith("https://") ||
            (u.startsWith("/") && !u.startsWith("//"))

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
     * ⚠️ 校验规则见 [usableImageUrl]（http(s) 或同源相对路径，其余拒绝）。
     *    返回的可能是**相对路径** —— 调用方要过 [MessageImage.resolve] 再交给加载器。
     */
    fun imageUrlFromExtras(extras: Map<String, Any>?): String? {
        if (extras == null) return null
        (extras["image"] as? String)?.takeIf(::usableImageUrl)?.let { return it }
        (extras["client::display"] as? Map<*, *>)
            ?.get("url")
            ?.let { (it as? String)?.takeIf(::usableImageUrl) }
            ?.let { return it }
        (extras["client::notification"] as? Map<*, *>)
            ?.get("bigImageUrl")
            ?.let { (it as? String)?.takeIf(::usableImageUrl) }
            ?.let { return it }
        return null
    }

    /**
     * 正文里的 markdown 图片地址
     *
     * 服务端自带的 WebUI 发图时只往正文写 `![](url)`、**不带 extras**，所以这条兜底不能少。
     * 同样可能拿到相对路径，调用方记得补全。
     */
    fun imageUrlFromMarkdown(text: String): String? =
        IMAGE_MD.find(text)?.groupValues?.get(1)?.takeIf(::usableImageUrl)

    /**
     * 该展示的图片地址：extras 优先、正文 markdown 兜底
     *
     * 卡片和通知走的是**同一条规则**，别只改一边。
     *
     * ⚠️ 返回值可能是不带域名的相对路径，**必须先补全**（[MessageImage.resolve]）。
     */
    fun imageUrl(text: String, extras: Map<String, Any>?): String? =
        imageUrlFromExtras(extras) ?: imageUrlFromMarkdown(text)

    // ==================== 深链（点一下直达对应页面） ====================

    /**
     * 深链：卡片上一个「打开」按钮、通知上一个 action
     *
     * 由发送方写进 extras（如 Jellyfin 插件的详情页地址）：
     *   - `url`      绝对地址（http/https）
     *   - `urlLabel` 按钮文字，**发送方说了算，不翻译**；没给就用域名兜底
     *   - `urlGone`  **服务端**写的标记：目标已被删除 ⇒ 只展示、不可点
     *     （不直接隐藏：历史通知该保持诚实——"当时能点、现在东西没了"）
     */
    data class DeepLink(val url: String, val label: String, val gone: Boolean)

    fun deepLinkFromExtras(extras: Map<String, Any>?): DeepLink? {
        if (extras == null) return null
        // 只认绝对地址：extras 是外部可写的，不能让 javascript: 之类混进来
        val url = (extras["url"] as? String)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        // 没给 label 就用域名（不引入 android.net.Uri：这个对象要保持可在纯 JVM 下单测）
        val label = (extras["urlLabel"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: url.substringAfter("://").substringBefore('/').takeIf { it.isNotEmpty() }
            ?: url
        return DeepLink(url = url, label = label, gone = extras["urlGone"] == true)
    }
}
