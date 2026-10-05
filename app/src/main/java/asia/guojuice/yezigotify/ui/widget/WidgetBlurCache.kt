package asia.guojuice.yezigotify.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import asia.guojuice.yezigotify.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Widget 模糊背景图的本地文件缓存
 *
 * 设计目标：
 *   - 即使 Glide 内存/磁盘缓存被清空，也能快速拿到模糊图
 *   - widget 刷新不需要网络，不需要等待 Glide
 *   - 存储空间可控（LRU + 过期清理）
 *
 * 存储位置：context.cacheDir/widget_blur/
 *   - cacheDir 系统可在空间不足时自动清理，不会影响用户存储
 *   - 用户清除 App 数据时一并清空，无需额外处理
 *
 * 文件命名：URL 的 SHA-1 前 16 字节（32 hex 字符）+ .png
 *   - 避免 URL 中的特殊字符导致文件名非法
 *   - SHA-1 冲突概率可忽略
 *
 * 写入策略：tmp + rename
 *   - 直接写目标文件的话，如果写一半进程被杀，会留下损坏文件
 *   - 先写 .tmp，写完 rename，rename 在同分区上是原子操作
 *
 * 清理策略：
 *   - 7 天未访问的文件删除
 *   - 超过 100 个文件时按 lastModified 排序删最旧的
 *   - 由 MessageWidgetFactory 在 onDataSetChanged 时异步调用 trim()
 *
 * 存储格式：PNG
 *   - 保留圆角外的 alpha 透明通道（JPEG 不支持 alpha）
 *   - 模糊图 PNG 压缩率高，240×135 大约 20~40 KB
 *   - 100 文件上限 ≈ 2~4 MB，可接受
 */
object WidgetBlurCache {

    private const val TAG = "WidgetBlurCache"
    private const val DIR_NAME = "widget_blur"
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 天
    private const val MAX_FILES = 100

    private fun cacheDir(context: Context): File {
        val d = File(context.cacheDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileNameFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(url.toByteArray(Charsets.UTF_8))
        // 取前 16 字节 hex = 32 字符，足以避免碰撞
        val hex = hash.take(16).joinToString("") { "%02x".format(it) }
        return "$hex.png"
    }

    /**
     * 从文件缓存读
     *   - 命中且未过期 → 返回 Bitmap，并刷新 lastModified（LRU 用）
     *   - 未命中或已过期 → 删除并返回 null
     *   - 文件损坏 → 删除并返回 null
     *
     * ⚠️ 只应在后台线程调用（decodeFile 会读磁盘）
     */
    fun read(context: Context, url: String): Bitmap? {
        val f = File(cacheDir(context), fileNameFor(url))
        if (!f.exists()) return null

        val now = System.currentTimeMillis()
        if (now - f.lastModified() > MAX_AGE_MS) {
            f.delete()
            return null
        }

        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            AppLog.d(TAG, "decodeFile 失败: ${e.message}")
            null
        }

        if (bmp == null) {
            // 损坏文件 → 删除
            f.delete()
            return null
        }

        // 刷新 lastModified，LRU 清理时保留"最近用过"的
        f.setLastModified(now)
        return bmp
    }

    /**
     * 写文件缓存
     *   - 用 tmp + rename 保证原子性
     *   - PNG 无损，保留 alpha 圆角
     *
     * ⚠️ 只应在后台线程调用
     */
    fun write(context: Context, url: String, bitmap: Bitmap) {
        try {
            val target = File(cacheDir(context), fileNameFor(url))
            val tmp = File(target.absolutePath + ".tmp")

            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (!tmp.renameTo(target)) {
                // rename 失败（罕见，可能是跨设备挂载点） → 删除 tmp
                tmp.delete()
                AppLog.d(TAG, "rename 失败: $url")
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "write 失败: $url, ${e.message}")
        }
    }

    /**
     * 清理：
     *   - 删除过期文件（超过 7 天未访问）
     *   - 文件数超过 MAX_FILES 时按 lastModified 删最旧的
     *
     * ⚠️ 只应在后台线程调用
     */
    fun trim(context: Context) {
        try {
            val dir = cacheDir(context)
            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()

            // 1. 删过期
            files.forEach { f ->
                if (now - f.lastModified() > MAX_AGE_MS) {
                    f.delete()
                }
            }

            // 2. 数量控制
            val remaining = dir.listFiles() ?: return
            if (remaining.size > MAX_FILES) {
                remaining
                    .sortedBy { it.lastModified() }  // 最旧在前
                    .take(remaining.size - MAX_FILES)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "trim 失败: ${e.message}")
        }
    }

    /**
     * 清空所有缓存（用户关闭"图片置底"时可选调用）
     *
     * ⚠️ 只应在后台线程调用
     */
    fun clear(context: Context) {
        try {
            cacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            AppLog.d(TAG, "clear 失败: ${e.message}")
        }
    }
}