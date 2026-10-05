package asia.guojuice.yezigotify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

interface GotifyApiService {
    @GET("message")
    suspend fun getMessages(
        @Query("limit") limit: Int,
        @Query("since") since: Long? = null
    ): PagedMessages

    @GET("version")
    suspend fun getVersion(): Response<Void>

    @DELETE("message/{id}")
    suspend fun deleteMessage(@Path("id") id: Long)

    @GET("application")
    suspend fun getApplications(): List<GotifyApp>
}

class GotifyApi(private val serverUrl: String, private val token: String) {

    //  每个实例持有自己的 authInterceptor
    private val authInterceptor: Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }

    //  拷贝单例 OkHttpClient，只加 auth header，共享连接池
    private val client = Companion.okHttpClient.newBuilder()
        .addInterceptor(authInterceptor)
        .build()

    private val service: GotifyApiService = Retrofit.Builder()
        .baseUrl(serverUrl.trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(Companion.gsonConverterFactory)
        .build()
        .create(GotifyApiService::class.java)

    suspend fun getMessages(limit: Int = 20, since: Long? = null): PagedMessages? {
        return try {
            service.getMessages(limit, since)
        } catch (e: Exception) {
            AppLog.e("GotifyApi", "getMessages failed: ${e.message}")
            null
        }
    }

    suspend fun testConnection(): Boolean {
        return try {
            // 直接调 Retrofit 接口，token 无效会抛 HttpException(401)
            service.getApplications()
            true
        } catch (e: Exception) {
            AppLog.d("GotifyApi", "testConnection failed: ${e.message}")
            false
        }
    }

    suspend fun deleteMessage(msgId: Long): Boolean {
        return try {
            service.deleteMessage(msgId)
            true
        } catch (e: retrofit2.HttpException) {
            // 404 表示消息本来就不存在（已经删过了），视为成功
            if (e.code() == 404) {
                AppLog.d("GotifyApi", "deleteMessage: 消息已不存在 (404)，视为成功")
                true
            } else {
                AppLog.e("GotifyApi", "deleteMessage failed: HTTP ${e.code()}")
                false
            }
        } catch (e: Exception) {
            AppLog.e("GotifyApi", "deleteMessage failed: ${e.message}")
            false
        }
    }

    companion object {
        //  共享 OkHttpClient（连接池、线程池全局唯一）
        private val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        //  Gson 单例
        private val gson: Gson = GsonBuilder().create()
        private val gsonConverterFactory = GsonConverterFactory.create(gson)

        //  可选：按 serverUrl 缓存 GotifyApi 实例，避免重复创建
        private val instanceCache = ConcurrentHashMap<String, GotifyApi>()

        fun getInstance(serverUrl: String, token: String): GotifyApi {
            val key = "$serverUrl|$token"
            return instanceCache.getOrPut(key) {
                GotifyApi(serverUrl, token)
            }
        }
    }
}