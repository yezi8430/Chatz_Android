package asia.guojuice.yezigotify.utils

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import asia.guojuice.yezigotify.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 气泡提示（View 版）
 *
 * 为什么不用 Compose 版：
 * - Compose 1.8+ 限制「同一 LifecycleOwner 只能有一个活跃 Composition」
 * - 页面本身已有 ComposeView（如 MessageListScreen、SettingsScreen）
 * - 若再挂一个 ComposeView 会抛 "ManagedValuesStore tried to enter composition twice"
 *
 * 用 TextView + GradientDrawable 手绘气泡，视觉与 Compose 版一致，且零冲突。
 *
 *  线程安全：
 *   View 操作（addView / removeView / animate）必须在主线程执行，
 *   否则抛 CalledFromWrongThreadException。
 *   本类对外 API（show / cancel）做了线程判断：
 *     - 主线程调用 → 直接执行（零额外开销）
 *     - 非主线程调用 → post 到主线程执行
 *   调用方无需关心自己在哪个线程。
 */
class BubbleHelper(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    //  主线程 Handler，用于把非主线程调用切回主线程
    private val mainHandler = Handler(Looper.getMainLooper())

    //  加 @Volatile：textView / hideJob 可能被多个线程读写，
    //    保证可见性，避免读到旧引用
    @Volatile
    private var textView: TextView? = null

    @Volatile
    private var hideJob: Job? = null

    private val cornerRadiusPx: Float
        get() = activity.resources.getDimension(R.dimen.radius_large)

    /** 当前是否在主线程 */
    private fun isMainThread(): Boolean =
        Looper.myLooper() == Looper.getMainLooper()

    fun show(
        message: String,
        durationMs: Long = 2500,
        topMarginDp: Float = 60f,
        textColor: Int? = null
    ) {
        //  线程安全：非主线程调用时，post 到主线程后重入本方法，
        //    保证下面所有 View 操作都在主线程执行
        if (!isMainThread()) {
            mainHandler.post { show(message, durationMs, topMarginDp, textColor) }
            return
        }

        if (activity.isFinishing || activity.isDestroyed) return

        hideJob?.cancel()
        textView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val context = activity
        val density = context.resources.displayMetrics.density
        val isNight = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isNight) Color.parseColor("#333333") else Color.WHITE
        val defaultTextColor = if (isNight) Color.parseColor("#EEEEEE") else Color.parseColor("#333333")
        val strokeColor = if (isNight) Color.parseColor("#555555") else Color.parseColor("#DDDDDD")

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(bgColor)
            setStroke((1 * density).toInt(), strokeColor)
        }

        textView = TextView(context).apply {
            text = message
            setTextColor(textColor ?: defaultTextColor)
            textSize = 14f
            background = drawable
            val hPad = (16 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (topMarginDp * density).toInt()
        }

        val decorView = context.window.decorView as ViewGroup
        decorView.addView(textView, params)

        textView?.alpha = 0f
        textView?.animate()?.alpha(1f)?.setDuration(200)?.start()

        // lifecycleScope 默认是 Main dispatcher，delay 结束后 dismiss() 在主线程执行
        hideJob = lifecycleScope.launch {
            delay(durationMs)
            dismiss()
        }
    }

    private fun dismiss() {
        // 内部私有方法，入口已保证主线程（cancel 已判断；hideJob 用 lifecycleScope Main）
        textView?.animate()
            ?.alpha(0f)
            ?.setDuration(300)
            ?.withEndAction {
                (textView?.parent as? ViewGroup)?.removeView(textView)
                textView = null
            }
            ?.start()
    }

    fun cancel() {
        //  线程安全：同 show
        if (!isMainThread()) {
            mainHandler.post { cancel() }
            return
        }
        hideJob?.cancel()
        dismiss()
    }
}