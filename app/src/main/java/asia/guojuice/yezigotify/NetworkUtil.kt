package asia.guojuice.yezigotify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtil {

    /** 当前是否为 WiFi（或以太网等"计费不敏感"网络） */
    fun isWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * 根据网络自动计算保活间隔（秒）
     * - WiFi：45s（实时）
     * - 移动网：120s（省电）
     */
    fun autoKeepAliveSeconds(context: Context): Int {
        return if (isWifi(context)) 45 else 180
    }
}