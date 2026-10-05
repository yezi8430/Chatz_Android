package asia.guojuice.yezigotify.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.MainActivity
import asia.guojuice.yezigotify.R

class RecentMessagesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 首次创建或系统触发更新时，走完整更新
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
            // 强制刷新列表数据
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            "asia.guojuice.yezigotify.WIDGET_UPDATE" -> {
                notifyWidgetDataChanged(context)
            }
        }
    }

    companion object {
        private const val TAG = "Gotify_RecentMessagesWidget"
        private var lastRefreshTime = 0L
        private const val REFRESH_THROTTLE_MS = 1500L

        // Trailing throttle 用的 Handler 和待执行任务
        //
        // 语义：
        //   - 窗口外调用：立即执行
        //   - 窗口内调用：安排一个延迟任务，在窗口末尾触发一次
        //   - 窗口内多次调用：不会重复安排任务（合并为一次）
        // 保证每 1.5 秒至少刷新一次，不会漏。
        private val handler = Handler(Looper.getMainLooper())
        private var pendingRefresh: Runnable? = null

        // 给 root 和 template 用不同的 action，避免 PendingIntent 去重
        private const val ACTION_WIDGET_ROOT = "asia.guojuice.yezigotify.WIDGET_ROOT_CLICK"
        private const val ACTION_WIDGET_ITEM = "asia.guojuice.yezigotify.WIDGET_ITEM_CLICK"

        /**
         * 触发 Widget 数据刷新（trailing throttle，1.5 秒窗口）
         */
        fun notifyWidgetDataChanged(context: Context) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefreshTime

            if (elapsed >= REFRESH_THROTTLE_MS) {
                // ===== 窗口外：立即执行 =====
                lastRefreshTime = now
                // 如果之前有挂起的延迟任务，先取消（本次立即执行已包含最新数据）
                pendingRefresh?.let {
                    handler.removeCallbacks(it)
                    pendingRefresh = null
                }
                doRefresh(context)
            } else {
                // ===== 窗口内：安排延迟任务（只安排一次） =====
                if (pendingRefresh == null) {
                    val delayMs = REFRESH_THROTTLE_MS - elapsed
                    val task = Runnable {
                        lastRefreshTime = System.currentTimeMillis()
                        pendingRefresh = null
                        doRefresh(context)
                    }
                    pendingRefresh = task
                    handler.postDelayed(task, delayMs)
                    AppLog.d(TAG, "⏳ 窗口内调用，延迟 ${delayMs}ms 补刷新")
                } else {
                    AppLog.d(TAG, "🚫 窗口内调用，已存在挂起任务，合并")
                }
            }
        }

        /**
         * 真正执行刷新
         * 抽出来给"立即执行"和"延迟执行"两条路径共用
         */
        private fun doRefresh(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, RecentMessagesWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
                AppLog.d(TAG, "🟢 精准刷新 Widget 数据, ids=${ids.joinToString()}")
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            AppLog.d(TAG, "🟢 updateAppWidget for id=$appWidgetId")

            val serviceIntent = Intent(context, WidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val remoteViews = RemoteViews(
                context.packageName,
                R.layout.widget_recent_messages
            ).apply {
                setRemoteAdapter(R.id.widget_list, serviceIntent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)

                // ========== 根布局点击：打开 App ==========
                val rootIntent = Intent(context, MainActivity::class.java).apply {
                    // 加 action，让 PendingIntent 身份唯一
                    action = ACTION_WIDGET_ROOT
                    // 加 data，进一步区分（避免不同 App 之间去重）
                    data = Uri.parse("gotify://widget/root")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val rootFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                val rootPendingIntent = PendingIntent.getActivity(
                    context,
                    1001,               // requestCode 改成不常见值，避免和其他组件冲突
                    rootIntent,
                    rootFlags
                )
                setOnClickPendingIntent(R.id.widget_root, rootPendingIntent)
            }

            // ========== 列表 item 点击：跳转到指定消息 ==========
            // Template 必须用 FLAG_MUTABLE，FillInIntent 才能填充数据
            val templateIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_WIDGET_ITEM
                data = Uri.parse("gotify://widget/item")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val templateFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_MUTABLE
            val templatePendingIntent = PendingIntent.getActivity(
                context,
                2002,
                templateIntent,
                templateFlags
            )
            remoteViews.setPendingIntentTemplate(R.id.widget_list, templatePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    }
}