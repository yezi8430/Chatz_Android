package asia.guojuice.yezigotify.ui.widget

import android.content.Intent
import android.os.IBinder
import android.widget.RemoteViewsService
import asia.guojuice.yezigotify.AppLog

class WidgetService : RemoteViewsService() {

    private val tag = "WidgetService"

    override fun onCreate() {
        super.onCreate()
        AppLog.d(tag, "🟢 WidgetService onCreate")
    }

    override fun onBind(intent: Intent?): IBinder? {
        AppLog.d(tag, "🟢 WidgetService onBind")
        return super.onBind(intent)
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        AppLog.d(tag, "🟢 onGetViewFactory called, intent=$intent")
        return MessageWidgetFactory(applicationContext, intent)
    }
}