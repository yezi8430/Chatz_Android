package asia.guojuice.yezigotify.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import asia.guojuice.yezigotify.AppDatabase
import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.MessageEntity
import asia.guojuice.yezigotify.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import jp.wasabeef.glide.transformations.BlurTransformation
import java.util.concurrent.TimeUnit

class MessageWidgetFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val tag = "MessageWidgetFactory"
    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private val prefs = context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
    private var messages: List<MessageEntity> = emptyList()

    // ============================================================
    // 本次 onDataSetChanged 阶段就准备好的模糊背景图
    //
    // 为什么不在 getViewAt 里读？
    //   - getViewAt 由系统在 binder 线程调用，超时会拖累 widget 刷新
    //   - 同一帧渲染可能多次调用 getViewAt，重复读缓存浪费 CPU
    //
    // 在 onDataSetChanged 一次性读好，getViewAt 直接查 map
    // ============================================================
    private val blurredCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    // 防止 "写文件 → notifyWidgetDataChanged → onDataSetChanged → 写文件"
    // 的无限循环：30 秒冷却时间
    @Volatile
    private var lastRefreshTriggerTime = 0L
    private val REFRESH_COOLDOWN_MS = 30_000L

    // ============================================================
    // 图片处理的统一参数
    //
    // ⚠️ preload 和 submit 的 Glide 参数必须【完全一致】，
    //    否则缓存 key 不同，处理结果会被 Glide 认为是两个不同请求。
    //    这里抽到常量 + makeTransform()，避免手写不一致。
    // ============================================================
    private val IMG_W = 240
    private val IMG_H = 135
    private val BLUR_RADIUS = 6
    private val BLUR_SAMPLING = 2
    private val CORNER_RADIUS_DP = 16f

    override fun onCreate() {
        AppLog.d(tag, "🟢 onCreate, appWidgetId=$appWidgetId")
    }

    /**
     * 完整的加载流程
     *
     * 阶段 1（同步，在 onDataSetChanged 里）：
     *   1. 读 DB 拿最近 5 条消息
     *   2. 从 WidgetBlurCache 文件读模糊图
     *      - 命中 → 存入 blurredCache，getViewAt 直接用
     *      - 未命中 → 记入 missing
     *
     * 阶段 2（异步，独立线程）：
     *   对 missing 的 URL：
     *     a. Glide 下载 + transform（模糊）
     *     b. 后处理（遮罩 + 圆角）
     *     c. 写文件到 WidgetBlurCache
     *
     * 阶段 3（回调）：
     *   preload 完成后通知 widget 再刷一次
     *   - 此时文件缓存已有数据，阶段 1 会全部命中
     *   - 30 秒冷却防止死循环
     */
    override fun onDataSetChanged() {
        AppLog.d(tag, "🟢 onDataSetChanged called")
        try {
            val db = AppDatabase.getDatabase(context)
            messages = db.messageDao().getRecentMessagesSync(5)
            AppLog.d(tag, "🟢 onDataSetChanged done, messages.size=${messages.size}")

            // 清空上一轮的缓存（不 recycle，让 GC 处理，避免 RemoteViews 还在用）
            blurredCache.clear()

            val imageBgEnabled = prefs.getBoolean("image_as_background", false)
            if (!imageBgEnabled) return

            val urls = messages
                .mapNotNull { extractImageUrl(it.message) }
                .distinct()

            if (urls.isEmpty()) return

            // 后台 trim 缓存（异步，不阻塞）
            Thread { WidgetBlurCache.trim(context) }.start()

            // ============ 阶段 1：从文件缓存读 ============
            val missing = mutableListOf<String>()
            urls.forEach { url ->
                val bmp = WidgetBlurCache.read(context, url)
                if (bmp != null) {
                    blurredCache[url] = bmp
                } else {
                    missing.add(url)
                }
            }

            AppLog.d(
                tag,
                "文件缓存命中 ${blurredCache.size}/${urls.size}，缺失 ${missing.size}"
            )

            // ============ 阶段 2：异步处理未命中的 ============
            val now = System.currentTimeMillis()
            if (missing.isNotEmpty() && now - lastRefreshTriggerTime > REFRESH_COOLDOWN_MS) {
                lastRefreshTriggerTime = now
                loadAndCacheInBackground(missing)
            }
        } catch (e: Exception) {
            AppLog.e(tag, "❌ onDataSetChanged failed: ${e.message}", e)
        }
    }

    /**
     * 后台：下载 → transform → 后处理 → 写文件
     *
     * 完成后通知 widget 二次刷新（30 秒冷却）
     */
    private fun loadAndCacheInBackground(urls: List<String>) {
        AppLog.d(tag, "🔽 后台加载 ${urls.size} 张图片")
        Thread {
            urls.forEach { url ->
                try {
                    // 1. Glide 下载 + 模糊 transform
                    val loaded = Glide.with(context)
                        .asBitmap()
                        .load(url)
                        .override(IMG_W, IMG_H)
                        .transform(makeTransform())
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .submit()
                        .get(10, TimeUnit.SECONDS)

                    // 2. 后处理：遮罩 + 圆角
                    val processed = postProcess(loaded)

                    // 3. 写文件
                    WidgetBlurCache.write(context, url, processed)

                    AppLog.d(tag, " $url 处理完成，已写文件")
                } catch (e: Exception) {
                    AppLog.d(tag, "❌ 加载失败: $url, ${e.message}")
                }
            }

            Handler(Looper.getMainLooper()).post {
                AppLog.d(tag, "🔼 加载完成，触发 widget 二次刷新")
                RecentMessagesWidget.notifyWidgetDataChanged(context)
            }
        }.start()
    }

    /**
     * Glide 转换链：居中裁剪 → 模糊
     *
     * ⚠️ 只在这里定义一次，避免 preload / submit 参数写错
     */
    private fun makeTransform(): MultiTransformation<Bitmap> =
        MultiTransformation(
            CenterCrop(),
            BlurTransformation(BLUR_RADIUS, BLUR_SAMPLING)
        )

    /**
     * 后处理：半透明遮罩 + 圆角
     *
     * ⚠️ 输出 ARGB_8888（保留 alpha，圆角外的透明区域 PNG 能保留）
     */
    private fun postProcess(source: Bitmap): Bitmap {
        val working = if (source.width == IMG_W && source.height == IMG_H) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            scaleCenterCrop(source, IMG_W, IMG_H)
        }

        val canvas = Canvas(working)
        canvas.drawColor(Color.parseColor("#66000000"))

        val rounded = getRoundedCornerBitmap(working, CORNER_RADIUS_DP)
        if (working !== source) working.recycle()

        return rounded
    }

    override fun getCount(): Int = messages.size

    override fun getViewAt(position: Int): RemoteViews {
        val msg = messages[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_item_message)

        rv.setViewVisibility(R.id.tv_app_name, View.GONE)
        rv.setTextViewText(R.id.tv_title, msg.title ?: "无标题")
        rv.setTextViewText(R.id.tv_content, cleanText(msg.message))

        // 用 data URI 携带消息 ID（最可靠的方式）
        val fillInIntent = Intent().apply {
            data = Uri.parse("gotify://message/${msg.id}")
        }
        rv.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

        val imageBgEnabled = prefs.getBoolean("image_as_background", false)
        val imageUrl = extractImageUrl(msg.message)

        if (imageBgEnabled && imageUrl != null) {
            // 直接从预读缓存取，不做任何 IO
            val blurred = blurredCache[imageUrl]
            if (blurred != null && !blurred.isRecycled) {
                rv.setImageViewBitmap(R.id.item_background_image, blurred)
                rv.setViewVisibility(R.id.item_background_image, View.VISIBLE)
                rv.setViewVisibility(R.id.item_overlay, View.GONE)
                rv.setTextColor(R.id.tv_title, Color.WHITE)
                rv.setTextColor(R.id.tv_content, Color.parseColor("#EEEEEE"))
                rv.setInt(R.id.widget_item_root, "setBackgroundColor", Color.TRANSPARENT)
                return rv
            }
        }

        applyThemeBackground(rv)
        return rv
    }

    private fun applyThemeBackground(rv: RemoteViews) {
        rv.setViewVisibility(R.id.item_background_image, View.GONE)
        rv.setViewVisibility(R.id.item_overlay, View.GONE)

        val themeColor = prefs.getInt("top_bar_color", Color.parseColor("#A5D6A7"))
        rv.setInt(R.id.widget_item_root, "setBackgroundColor", themeColor)

        val textColor = if (isColorDark(themeColor)) Color.WHITE else Color.BLACK
        rv.setTextColor(R.id.tv_title, textColor)
        rv.setTextColor(R.id.tv_content, textColor)
    }

    /**
     * 按比例缩放并居中裁剪，模拟 ImageView 的 CENTER_CROP 行为
     */
    private fun scaleCenterCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val matrix = Matrix()

        val scale: Float
        val dx: Float
        val dy: Float

        if (source.width * targetHeight > targetWidth * source.height) {
            // 原图较宽，按高度缩放
            scale = targetHeight.toFloat() / source.height.toFloat()
            dx = (targetWidth - source.width * scale) * 0.5f
            dy = 0f
        } else {
            // 原图较高，按宽度缩放
            scale = targetWidth.toFloat() / source.width.toFloat()
            dx = 0f
            dy = (targetHeight - source.height * scale) * 0.5f
        }

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    /**
     * 圆角处理
     *
     * ⚠️ 输出 ARGB_8888，保留圆角外的透明区域
     *    这样 PNG 压缩后圆角效果能被 RemoteViews 正确还原
     */
    private fun getRoundedCornerBitmap(bitmap: Bitmap, radiusDp: Float): Bitmap {
        val radiusPx = (context.resources.displayMetrics.density * radiusDp).toInt()

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.BLACK
        canvas.drawRoundRect(rectF, radiusPx.toFloat(), radiusPx.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading)

    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = messages[position].id
    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        // 不主动 recycle：RemoteViews 可能还在引用，交给 GC 处理
        blurredCache.clear()
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) +
                0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "").trim().take(120)
    }

    /**
     * 从 markdown 语法中提取图片 URL
     *
     *  正则有捕获组 ()，且用 getOrNull(1) 避免越界
     */
    private fun extractImageUrl(text: String): String? {
        return try {
            val regex = Regex("!\\[.*?\\]\\((https?://.*?)\\)")
            regex.find(text)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }
}