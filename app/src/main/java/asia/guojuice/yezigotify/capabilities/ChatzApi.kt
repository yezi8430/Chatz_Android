package asia.guojuice.yezigotify.capabilities

import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.GotifyApp
import asia.guojuice.yezigotify.PagedMessages
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Chatz REST 接口
 *
 * 只包含 Chatz 扩展端点；Gotify 兼容端点继续用 GotifyApi。
 * 两者共享 OkHttpClient 连接池。
 */
interface ChatzApiService {

    // ===== 频道 =====
    @GET("channel")
    suspend fun getChannels(): List<ChatzChannel>

    @GET("channel/discover")
    suspend fun discoverChannels(@Query("q") q: String? = null): List<ChatzChannel>

    @POST("channel/{id}/subscribe")
    suspend fun subscribeChannel(
        @Path("id") id: Int,
        @Body body: ChatzSubscribeBody
    ): Response<Unit>

    @DELETE("channel/{id}/subscribe")
    suspend fun unsubscribeChannel(@Path("id") id: Int): Response<Unit>

    /**
     * 静音 / 取消静音
     * body: {"muted": true}
     */
    @PATCH("channel/{id}/subscribe")
    suspend fun muteChannel(
        @Path("id") id: Int,
        @Body body: ChatzMuteBody
    ): Response<Unit>

    // ===== 频道管理（改名 / 描述 / 公开性 / 图标）=====

    /**
     * 更新频道元信息（名称 / 描述 / 公开性）。
     * 图标走独立的 [uploadChannelIcon]，不在这里处理。
     *
     * 服务端字段都可空、可只传需要改的列（Gson 省略 null）。
     */
    @PATCH("channel/{id}")
    suspend fun updateChannel(
        @Path("id") id: Int,
        @Body body: ChatzChannelUpdateBody
    ): Response<ChatzChannel>

    /**
     * 上传频道图标
     *
     * body 是**裸二进制**（服务端 express.raw 按图片类型收、上限 5MB，见 index.js），
     * Content-Type 必须是图片类，否则解析不到 → 400「请求内容为空」。
     */
    @POST("channel/{id}/icon")
    suspend fun uploadChannelIcon(
        @Path("id") id: Int,
        @Body body: RequestBody
    ): Response<ChatzChannel>

    // ===== 消息 =====
    @GET("message")
    suspend fun getMessages(
        @Query("limit") limit: Int = 50,
        @Query("since") since: Long? = null,
        @Query("channel") channelId: Int? = null,
        @Query("unread") unread: Int? = null,
        @Query("archived") archived: String? = null
    ): PagedMessages

    @GET("message/search")
    suspend fun searchMessages(
        @Query("q") query: String,
        @Query("limit") limit: Int = 50,
        @Query("channel") channelId: Int? = null
    ): ChatzSearchResult

    @GET("message/unread-counts")
    suspend fun getUnreadCounts(): ChatzUnreadCounts

    /**
     * 已删除消息的增量对账（墓碑列表）
     *
     * @param since 删除时间水位线（毫秒）。首次传 null/0 会拿到全部墓碑；
     *              之后由客户端用上一批的 max(deletedAt) 推进。
     *
     * ⚠️ 这是**独立的对账通道**，和 [getMessages] 的 since（id 水位线）不是一回事：
     *    那个只能表达「新增」，删除必须靠这个接口才能增量发现。
     */
    @GET("message/deleted")
    suspend fun getDeletedMessages(
        @Query("since") since: Long? = null,
        @Query("sinceId") sinceId: Long? = null,
        @Query("limit") limit: Int = 500
    ): ChatzDeletedResult

    @POST("message/{id}/read")
    suspend fun markRead(@Path("id") id: Long): Response<Unit>

    @POST("message/{id}/unread")
    suspend fun markUnread(@Path("id") id: Long): Response<Unit>

    /**
     * 批量已读
     * body: {"channel_id": 2} 或 {}（channelId 为 null 时 Gson 省略该字段）
     */
    @POST("message/read-all")
    suspend fun markAllRead(@Body body: ChatzReadAllBody): Response<Unit>

    @POST("message/{id}/archive")
    suspend fun archiveMessage(@Path("id") id: Long): Response<Unit>

    @POST("message/{id}/unarchive")
    suspend fun unarchiveMessage(@Path("id") id: Long): Response<Unit>

    @DELETE("message/{id}")
    suspend fun deleteMessage(@Path("id") id: Long): Response<Unit>

    /**
     * 发消息
     * body: {"message": "...", "channel_id": 2, "appid": 1}
     */
    @POST("message")
    suspend fun postMessage(@Body body: ChatzSendBody): Response<ChatzSendResult>

    /**
     * 上传附件
     *
     * body 是**裸二进制**（服务端用 express.raw 收，不是 multipart）；
     * name 只用来取扩展名 —— 图片的扩展名以服务端魔数为准。
     */
    @POST("attachment")
    suspend fun uploadAttachment(
        @Query("name") name: String,
        @Body body: RequestBody
    ): Response<ChatzUploadResult>

    // ===== 账号（当前登录用户）=====
    @GET("auth/me")
    suspend fun getMe(): ChatzMe

    /** 改昵称（服务端限制 1~32 字符） */
    @PATCH("user/profile")
    suspend fun updateProfile(@Body body: ChatzProfileBody): Response<ChatzProfileResult>

    /**
     * 改用户名（登录用的名字）
     *
     * 服务端规则：2-32 位，仅 a-z A-Z 0-9 _ - .，**全局唯一**。
     * 重名回 409；漏传/类型不对回 400；限速 10 次 / 10 分钟。
     * 改名不影响登录态（设备 Token 绑的是 user_id）。
     */
    @PATCH("user/username")
    suspend fun updateUsername(@Body body: ChatzUsernameBody): Response<ChatzUsernameResult>

    /**
     * 改邮箱（选填、全局唯一，用于「忘记密码」）
     *
     * 传空串 = 清除；格式不合法回 400，被占用回 409。
     */
    @PATCH("user/email")
    suspend fun updateEmail(@Body body: ChatzEmailBody): Response<ChatzEmailResult>

    /**
     * 改密码
     *
     * 服务端**不要求旧密码**（已登录即已通过身份验证），只校验：
     *   6-128 位、且不能和当前密码相同（相同回 400）。
     * [ChatzPasswordBody.revokeDevices] = true 时顺带吊销其它设备 Token
     * （当前设备与主密钥行不会被删）。限速 10 次 / 10 分钟。
     */
    @PATCH("user/password")
    suspend fun updatePassword(@Body body: ChatzPasswordBody): Response<ChatzPasswordResult>

    /**
     * 传头像
     *
     * body 是**裸二进制**（服务端用 express.raw 按图片类型收，不是 multipart），
     * 所以 RequestBody 的媒体类型必须是图片类（image/png、image/jpeg 等）。
     * 传 application/octet-stream 服务端会解析不到、直接 400。
     * 服务端只认 png/jpg/gif/webp（按魔数），svg 会被拒。
     *
     * ⚠️ 注释里**不要**写"image 斜杠 星号"那种字面量：Kotlin 的块注释可嵌套，
     *    那几个字符会再开一层注释，把后面的代码整个吞掉（本文件踩过一次）。
     */
    @POST("user/avatar")
    suspend fun uploadAvatar(@Body body: RequestBody): Response<ChatzAvatarResult>

    @DELETE("user/avatar")
    suspend fun deleteAvatar(): Response<Unit>

    // ===== 设备（自己的 token 列表）=====
    @GET("device")
    suspend fun getDevices(): List<ChatzDevice>

    @POST("device")
    suspend fun createDevice(@Body body: ChatzCreateDeviceBody): Response<ChatzDevice>

    @DELETE("device/{id}")
    suspend fun deleteDevice(@Path("id") id: Int): Response<Unit>

    // ===== 应用 =====
    @GET("application")
    suspend fun getApplications(): List<GotifyApp>
}

/**
 * 账号类操作（改用户名 / 邮箱 / 密码）的结果
 *
 * 为什么不用 `null` 表示失败：这三个操作各有多种**用户能修正**的失败
 * （重名 409、格式 400、太频繁 429、密码和现在一样 400），必须把服务端原文透出来，
 * 否则用户看到「保存失败」完全不知道下一步该做什么。
 *
 * 而 [Rejected] 与 [Failed] 的分野在于：前者是"你填的不对"，后者是"环境不对"
 * （网络 / 5xx）—— UI 上可以给不同的呈现（前者标在输入框旁，后者用 Toast）。
 */
sealed class AccountOpResult<out T> {
    /** 成功，[value] 是服务端返回的结果（账号对象 / 吊销台数） */
    data class Ok<T>(val value: T) : AccountOpResult<T>()

    /** 服务端明确拒绝（4xx），[message] 是可直接展示的中文原因 */
    data class Rejected(val message: String) : AccountOpResult<Nothing>()

    /** 请求没能完成：网络异常、超时、5xx，[message] 是兜底提示 */
    data class Failed(val message: String) : AccountOpResult<Nothing>()
}

/**
 * 订阅频道的细粒度结果
 *
 * 密码订阅要区分「密码错误」和「尝试次数过多（429）」——前者让用户原地重试，
 * 后者要锁一段时间。普通订阅只看 [Success] / [Failed] 即可。
 */
sealed class ChannelSubscribeResult {
    object Success : ChannelSubscribeResult()

    /** 密码错误（403）。在发现页语境下，订阅公开频道的 403 只可能是密码错 */
    object WrongPassword : ChannelSubscribeResult()

    /** 密码尝试次数过多（429），[retryAfter] 是服务端给的剩余冷却秒数 */
    data class RateLimited(val retryAfter: Int) : ChannelSubscribeResult()

    /** 网络异常 / 5xx / 其它非预期状态 */
    object Failed : ChannelSubscribeResult()
}

/**
 * 从服务端的 `{ "error": "..." }` 里取出可展示的文案
 *
 * 解析失败（空 body / 非 JSON）时返回 null，由调用方给兜底文案 ——
 * 服务端所有 4xx 都带 error 字段，所以正常情况一定取得到。
 */
private fun errorMessageOf(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        com.google.gson.JsonParser.parseString(raw).asJsonObject.get("error")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** 从订阅 429 响应体里读剩余冷却秒数，读不到按 60 秒兜底 */
private fun retryAfterOf(resp: Response<*>): Int {
    val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return 60
    return runCatching {
        com.google.gson.JsonParser.parseString(raw).asJsonObject.get("retryAfter")?.asInt ?: 60
    }.getOrDefault(60)
}

/**
 * 从 `PATCH /user/password` 的响应体里读吊销台数
 *
 * 服务端回 `{ok:true, revokedDevices:N}`；读不到时按 0 处理
 * （不算失败：密码确实已经改掉了）。
 */
private fun revokedDevicesOf(result: ChatzPasswordResult?): Int = result?.revokedDevices ?: 0

/**
 * Chatz API 客户端
 *
 * 设计要点：
 *   - 认证同时发 Bearer 和 X-Gotify-Key（服务端两个 header 都认，
 *     万一用户填了旧 Gotify 的 token 格式也能工作）
 *   - 复用 GotifyApi 的 OkHttpClient（连接池、Dispatcher 共享）
 *   - 按 serverUrl|token 缓存实例
 */
class ChatzApi(
    private val serverUrl: String,
    private val token: String
) {

    private val service: ChatzApiService

    init {
        val client = sharedOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val req = original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("X-Gotify-Key", token)
                    .build()
                chain.proceed(req)
            }
            .build()

        service = Retrofit.Builder()
            .baseUrl(normalizeBase(serverUrl))
            .client(client)
            .addConverterFactory(sharedGsonConverter)
            .build()
            .create(ChatzApiService::class.java)
    }

    // ==================== 频道 ====================

    suspend fun getChannels(): List<ChatzChannel> = try {
        service.getChannels()
    } catch (e: Exception) {
        AppLog.e(TAG, "getChannels failed: ${e.message}")
        emptyList()
    }

    suspend fun discoverChannels(q: String? = null): List<ChatzChannel> = try {
        service.discoverChannels(q)
    } catch (e: Exception) {
        AppLog.e(TAG, "discoverChannels failed: ${e.message}")
        emptyList()
    }

    suspend fun subscribeChannel(id: Int, password: String? = null): ChannelSubscribeResult = try {
        val resp = service.subscribeChannel(id, ChatzSubscribeBody(password))
        when {
            resp.isSuccessful -> ChannelSubscribeResult.Success
            resp.code() == 403 -> ChannelSubscribeResult.WrongPassword
            resp.code() == 429 -> ChannelSubscribeResult.RateLimited(retryAfterOf(resp))
            else -> {
                AppLog.e(TAG, "subscribeChannel failed: HTTP ${resp.code()}")
                ChannelSubscribeResult.Failed
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "subscribeChannel failed: ${e.message}")
        ChannelSubscribeResult.Failed
    }

    suspend fun unsubscribeChannel(id: Int): Boolean = try {
        service.unsubscribeChannel(id).isSuccessful
    } catch (e: Exception) {
        AppLog.e(TAG, "unsubscribeChannel failed: ${e.message}")
        false
    }

    /**
     * 静音 / 取消静音（只影响当前用户的订阅，不影响其他订阅者）
     */
    suspend fun muteChannel(id: Int, muted: Boolean): Boolean = try {
        service.muteChannel(id, ChatzMuteBody(muted)).isSuccessful
    } catch (e: Exception) {
        AppLog.e(TAG, "muteChannel failed: ${e.message}")
        false
    }

    // ==================== 频道管理 ====================

    /**
     * 更新频道元信息（名称 / 描述 / 公开性 / 订阅密码）
     *
     * @param password null = 不动；空串 "" = 清除密码；非空 = 设置新密码
     *
     * @return [AccountOpResult]
     *   - Ok：服务端返回的更新后频道对象
     *   - Rejected：403 没有权限 / 404 频道不存在 / 400 请求不合法
     *   - Failed：网络异常或非 2xx
     */
    suspend fun updateChannel(
        id: Int,
        name: String,
        description: String,
        isPublic: Boolean,
        password: String? = null
    ): AccountOpResult<ChatzChannel> = try {
        val resp = service.updateChannel(
            id,
            ChatzChannelUpdateBody(
                name = name,
                description = description,
                isPublic = isPublic,
                password = password
            )
        )
        channelResultOf(resp, "保存失败")
    } catch (e: Exception) {
        AppLog.e(TAG, "updateChannel failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 上传频道图标
     *
     * 图标上限 5MB（与服务端 express.raw 的 limit 一致）。
     */
    suspend fun uploadChannelIcon(
        id: Int,
        bytes: ByteArray,
        mime: String
    ): AccountOpResult<ChatzChannel> = try {
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        val resp = service.uploadChannelIcon(id, body)
        when {
            resp.isSuccessful -> {
                val ch = resp.body()
                if (ch == null) AccountOpResult.Failed("服务器未返回频道信息")
                else AccountOpResult.Ok(ch)
            }
            resp.code() == 400 || resp.code() == 413 ->
                AccountOpResult.Rejected("仅支持 png / jpg / gif / webp，且不超过 5MB")
            else -> channelResultOf(resp, "上传失败")
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "uploadChannelIcon failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 移除频道图标（PATCH image 为空串）
     */
    suspend fun removeChannelIcon(id: Int): AccountOpResult<ChatzChannel> = try {
        val resp = service.updateChannel(id, ChatzChannelUpdateBody(image = ""))
        channelResultOf(resp, "移除失败")
    } catch (e: Exception) {
        AppLog.e(TAG, "removeChannelIcon failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /** 把 PATCH /channel/:id 与 POST /channel/:id/icon 的响应统一解析成 [AccountOpResult] */
    private fun channelResultOf(
        resp: Response<ChatzChannel>,
        failLabel: String
    ): AccountOpResult<ChatzChannel> = when {
        resp.isSuccessful -> {
            val ch = resp.body()
            if (ch == null) AccountOpResult.Failed("服务器未返回频道信息")
            else AccountOpResult.Ok(ch)
        }
        resp.code() == 403 -> AccountOpResult.Rejected("没有权限管理这个频道")
        resp.code() == 404 -> AccountOpResult.Rejected("频道不存在")
        resp.code() == 400 -> AccountOpResult.Rejected(
            errorMessageOf(resp.errorBody()?.string()) ?: "$failLabel：请求不合法"
        )
        resp.code() == 429 -> AccountOpResult.Rejected("操作过于频繁，请稍后再试")
        else -> {
            AppLog.e(TAG, "$failLabel failed: HTTP ${resp.code()}")
            AccountOpResult.Failed("$failLabel（HTTP ${resp.code()}）")
        }
    }

    // ==================== 消息 ====================

    /**
     * 拉取消息
     *
     * @param archived 归档过滤模式：
     *   - "all"  : 拉全部（默认，客户端对账用，收藏也需要）
     *   - "1"    : 只拉已归档
     *   - null   : 只拉未归档
     *
     * 默认 "all" 的原因：
     *   1. 收藏功能需要显示已归档消息
     *   2. 本地 archived_at 与 starred 对账需要完整数据
     */
    suspend fun getMessages(
        limit: Int = 50,
        since: Long? = null,
        channelId: Int? = null,
        unreadOnly: Boolean = false,
        archived: String? = "all"
    ): PagedMessages? = try {
        service.getMessages(
            limit = limit,
            since = since,
            channelId = channelId,
            unread = if (unreadOnly) 1 else null,
            archived = archived
        )
    } catch (e: Exception) {
        AppLog.e(TAG, "getMessages failed: ${e.message}")
        null
    }

    suspend fun searchMessages(query: String, limit: Int = 50): ChatzSearchResult? = try {
        service.searchMessages(query, limit)
    } catch (e: Exception) {
        AppLog.e(TAG, "searchMessages failed: ${e.message}")
        null
    }

    suspend fun getUnreadCounts(): ChatzUnreadCounts? = try {
        service.getUnreadCounts()
    } catch (e: Exception) {
        AppLog.e(TAG, "getUnreadCounts failed: ${e.message}")
        null
    }

    /**
     * 拉取删除墓碑（增量对账删除用）
     *
     * 返回 null 表示请求失败（网络 / 非 2xx）。**调用方必须把 null 和
     * 「deleted 为空」区分开**：null 时绝不能推进水位线，否则那一段删除会被永久跳过。
     */
    /**
     * 增量拉取删除墓碑
     *
     * 双游标 `(since, sinceId)`：`since` 是删除时间戳，`sinceId` 是同一时间戳内的
     * id 位置。两个都要传 —— 只传 `since` 时，同刻墓碑（删频道/删应用是单条 UPDATE
     * 打同一个时间戳）超过一页就无法续读。
     * 首次调用两个都传 null；之后用上一页响应的 `next` 原样回传。
     */
    suspend fun getDeletedMessages(
        since: Long?,
        sinceId: Long? = null
    ): ChatzDeletedResult? = try {
        service.getDeletedMessages(since = since, sinceId = sinceId, limit = 500)
    } catch (e: Exception) {
        AppLog.e(TAG, "getDeletedMessages failed: ${e.message}")
        null
    }

    suspend fun markRead(id: Long): Boolean = safeCall { service.markRead(id) }

    suspend fun markUnread(id: Long): Boolean = safeCall { service.markUnread(id) }

    /**
     * 批量已读
     * @param channelId null = 全部频道
     */
    suspend fun markAllRead(channelId: Int? = null): Boolean = try {
        service.markAllRead(ChatzReadAllBody(channelId)).isSuccessful
    } catch (e: Exception) {
        AppLog.e(TAG, "markAllRead failed: ${e.message}")
        false
    }

    suspend fun archiveMessage(id: Long): Boolean = safeCall { service.archiveMessage(id) }

    suspend fun unarchiveMessage(id: Long): Boolean = safeCall { service.unarchiveMessage(id) }

    suspend fun deleteMessage(id: Long): Boolean = try {
        val resp = service.deleteMessage(id)
        resp.isSuccessful || resp.code() == 404
    } catch (e: Exception) {
        AppLog.e(TAG, "deleteMessage failed: ${e.message}")
        false
    }

    /**
     * 发消息
     *
     * 服务端会把它当成一条普通消息广播回来（含本机），所以调用方**不要**本地插入 ——
     * 交给 WS 回声入库，多设备 / 本机的路径才是同一条。
     *
     * @return null = 请求失败或非 2xx；否则为服务端结果
     *   （[ChatzSendResult.dropped] 为 true 表示被路由规则丢弃，服务端没创建消息）
     */
    suspend fun sendMessage(body: ChatzSendBody): ChatzSendResult? = try {
        val resp = service.postMessage(body)
        if (resp.isSuccessful) {
            resp.body()
        } else {
            AppLog.e(TAG, "sendMessage failed: HTTP ${resp.code()}")
            null
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "sendMessage failed: ${e.message}")
        null
    }

    /**
     * 上传附件
     *
     * @param contentType 只决定服务端读到的 Content-Type；扩展名以服务端嗅探为准
     * @return null = 请求失败或非 2xx；否则为服务端结果
     *   （[ChatzUploadResult.url] 是**相对路径**，绝对地址由调用方按服务器地址补全）
     */
    suspend fun uploadAttachment(
        name: String,
        bytes: ByteArray,
        contentType: String
    ): ChatzUploadResult? = try {
        val body = bytes.toRequestBody(contentType.toMediaTypeOrNull())
        val resp = service.uploadAttachment(name, body)
        if (resp.isSuccessful) {
            resp.body()
        } else {
            AppLog.e(TAG, "uploadAttachment failed: HTTP ${resp.code()}")
            null
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "uploadAttachment failed: ${e.message}")
        null
    }

    // ==================== 应用 ====================

    /**
     * 拉应用列表
     *
     * AppIconCache 那条路是给抽屉/图标用的（自己搓 HttpURLConnection + 预加载），
     * 这条只给「发消息时推导 appid」兜底：本地缓存还没加载完时才走（见
     * MessageViewModel.deriveSendAppId）。
     */
    suspend fun getApplications(): List<GotifyApp> = try {
        service.getApplications()
    } catch (e: Exception) {
        AppLog.e(TAG, "getApplications failed: ${e.message}")
        emptyList()
    }

    // ==================== 账号 / 设备 ====================

    /** 当前账号；失败返回 null */
    suspend fun getMe(): ChatzMe? = try {
        service.getMe()
    } catch (e: Exception) {
        AppLog.e(TAG, "getMe failed: ${e.message}")
        null
    }

    /**
     * 改昵称
     *
     * @return 更新后的账号；null = 失败（含服务端拒绝：昵称空或超 32 字符）
     */
    suspend fun updateProfile(displayName: String): ChatzMe? = try {
        val resp = service.updateProfile(ChatzProfileBody(displayName))
        if (resp.isSuccessful) resp.body()?.user else {
            AppLog.e(TAG, "updateProfile failed: HTTP ${resp.code()}")
            null
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "updateProfile failed: ${e.message}")
        null
    }

    /**
     * 改用户名
     *
     * @return [AccountOpResult]
     *   - [AccountOpResult.Ok]：更新后的账号
     *   - [AccountOpResult.Rejected]：[message] 是服务端原文（如「用户名已被占用」），可直接展示
     *   - [AccountOpResult.Failed]：[message] 是本地拼的提示（网络错 / 非 2xx 且无正文）
     *
     * 为什么不返回 null 了事：改名有两种失败（重名 409 / 格式 400），
     * 用户必须知道是哪一种才能改对，笼统的"保存失败"没有意义。
     */
    suspend fun updateUsername(username: String): AccountOpResult<ChatzMe> = try {
        val resp = service.updateUsername(ChatzUsernameBody(username))
        when {
            resp.isSuccessful -> {
                val user = resp.body()?.user
                if (user == null) AccountOpResult.Failed("服务器未返回账号信息")
                else AccountOpResult.Ok(user)
            }
            resp.code() == 400 || resp.code() == 409 ->
                AccountOpResult.Rejected(errorMessageOf(resp.errorBody()?.string())
                    ?: "用户名不可用（2-32 位，仅字母、数字和 _ - .）")
            resp.code() == 429 ->
                AccountOpResult.Rejected("操作过于频繁，请稍后再试")
            else -> {
                AppLog.e(TAG, "updateUsername failed: HTTP ${resp.code()}")
                AccountOpResult.Failed("保存失败（HTTP ${resp.code()}）")
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "updateUsername failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 改邮箱；[email] 传空串表示清除
     *
     * 失败语义同 [updateUsername]，另外 409 = 邮箱已被别的账号占用。
     */
    suspend fun updateEmail(email: String): AccountOpResult<ChatzMe> = try {
        val resp = service.updateEmail(ChatzEmailBody(email))
        when {
            resp.isSuccessful -> {
                val user = resp.body()?.user
                if (user == null) AccountOpResult.Failed("服务器未返回账号信息")
                else AccountOpResult.Ok(user)
            }
            resp.code() == 400 || resp.code() == 409 ->
                AccountOpResult.Rejected(errorMessageOf(resp.errorBody()?.string())
                    ?: "邮箱格式不正确或已被占用")
            resp.code() == 429 ->
                AccountOpResult.Rejected("操作过于频繁，请稍后再试")
            else -> {
                AppLog.e(TAG, "updateEmail failed: HTTP ${resp.code()}")
                AccountOpResult.Failed("保存失败（HTTP ${resp.code()}）")
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "updateEmail failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 改密码（自己）
     *
     * @param revokeDevices true = 顺带登出其它设备（当前设备保留）
     * @return 成功时 [AccountOpResult.Ok]，[AccountOpResult.Ok.value] 是吊销的设备台数
     */
    suspend fun updatePassword(
        newPassword: String,
        revokeDevices: Boolean = false
    ): AccountOpResult<Int> = try {
        val resp = service.updatePassword(
            ChatzPasswordBody(newPassword, revokeDevices.takeIf { it })
        )
        when {
            resp.isSuccessful -> AccountOpResult.Ok(revokedDevicesOf(resp.body()))
            resp.code() == 400 || resp.code() == 403 ->
                AccountOpResult.Rejected(errorMessageOf(resp.errorBody()?.string())
                    ?: "密码不符合要求（6-128 位，且不能和当前密码相同）")
            resp.code() == 429 ->
                AccountOpResult.Rejected("操作过于频繁，请稍后再试")
            else -> {
                AppLog.e(TAG, "updatePassword failed: HTTP ${resp.code()}")
                AccountOpResult.Failed("修改失败（HTTP ${resp.code()}）")
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "updatePassword failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 传头像
     *
     * ⚠️ [mime] 必须是**图片类**的媒体类型（image/png、image/jpeg 等）：
     *    服务端的 raw 解析器只匹配图片类型，传 application/octet-stream 会拿到空 body → 400。
     *
     * 返回 [AccountOpResult] 而不是 `String?`：上传失败有**两种完全不同的原因** ——
     * 「图片格式不支持」和「网络断了」。以前两者都返回 null，调用方只能猜一个，
     * 结果断网时提示用户"仅支持 png/jpg/gif/webp"，完全误导。
     */
    suspend fun uploadAvatar(bytes: ByteArray, mime: String): AccountOpResult<String> = try {
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        val resp = service.uploadAvatar(body)
        when {
            resp.isSuccessful -> {
                val avatar = resp.body()?.avatar
                if (avatar == null) AccountOpResult.Failed("服务器未返回头像地址")
                else AccountOpResult.Ok(avatar)
            }
            // 400 = raw 解析器没认出图片魔数（格式不支持 / 内容为空）
            resp.code() == 400 || resp.code() == 413 ->
                AccountOpResult.Rejected("仅支持 png / jpg / gif / webp，且不超过 5MB")
            resp.code() == 401 ->
                AccountOpResult.Failed("登录状态已失效，请重新登录")
            else -> {
                AppLog.e(TAG, "uploadAvatar failed: HTTP ${resp.code()}")
                AccountOpResult.Failed("上传失败（HTTP ${resp.code()}）")
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "uploadAvatar failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /**
     * 删头像
     *
     * 404 也算成功 —— 本来就没有。与 [uploadAvatar] 同理，用 [AccountOpResult]
     * 区分「网络断了」和「服务端拒绝」，别让调用方自己猜。
     */
    suspend fun deleteAvatar(): AccountOpResult<Unit> = try {
        val resp = service.deleteAvatar()
        when {
            resp.isSuccessful || resp.code() == 404 -> AccountOpResult.Ok(Unit)
            resp.code() == 401 -> AccountOpResult.Failed("登录状态已失效，请重新登录")
            else -> {
                AppLog.e(TAG, "deleteAvatar failed: HTTP ${resp.code()}")
                AccountOpResult.Failed("移除失败（HTTP ${resp.code()}）")
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "deleteAvatar failed: ${e.message}")
        AccountOpResult.Failed("网络异常，请检查连接")
    }

    /** 自己的设备（token）列表；失败返回 null（与"空列表"区分） */
    suspend fun getDevices(): List<ChatzDevice>? = try {
        service.getDevices()
    } catch (e: Exception) {
        AppLog.e(TAG, "getDevices failed: ${e.message}")
        null
    }

    /** 新建一台设备，返回它的一次性 token（只有这一次能拿到） */
    suspend fun createDevice(name: String): ChatzDevice? = try {
        val resp = service.createDevice(ChatzCreateDeviceBody(name))
        if (resp.isSuccessful) resp.body() else {
            AppLog.e(TAG, "createDevice failed: HTTP ${resp.code()}")
            null
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "createDevice failed: ${e.message}")
        null
    }

    suspend fun deleteDevice(id: Int): Boolean = try {
        val resp = service.deleteDevice(id)
        resp.isSuccessful || resp.code() == 404
    } catch (e: Exception) {
        AppLog.e(TAG, "deleteDevice failed: ${e.message}")
        false
    }

    /**
     * 服务端返回的相对路径 → 可直接加载的绝对地址
     *
     * 头像存的是 `/user-avatars/xxx.png`，和图标同理：绝对地址在客户端拼，
     * 这样换域名 / 局域网 IP 都不会把存下来的地址搞坏。
     */
    fun absoluteUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = normalizeBase(serverUrl).trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    // ==================== 工具 ====================

    private suspend inline fun safeCall(block: () -> Response<Unit>): Boolean = try {
        block().isSuccessful
    } catch (e: Exception) {
        AppLog.e(TAG, "call failed: ${e.message}")
        false
    }

    companion object {
        private const val TAG = "ChatzApi"

        /**
         * 共享 OkHttpClient
         *
         * 与 GotifyApi 逻辑一致（各自实例化，但配置相同）。
         * 用 lazy 保证全局唯一。
         */
        private val sharedOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private val sharedGson: Gson = GsonBuilder().create()
        private val sharedGsonConverter = GsonConverterFactory.create(sharedGson)

        private val instanceCache = ConcurrentHashMap<String, ChatzApi>()

        fun getInstance(serverUrl: String, token: String): ChatzApi {
            val key = "$serverUrl|$token"
            return instanceCache.getOrPut(key) { ChatzApi(serverUrl, token) }
        }

        private fun normalizeBase(url: String): String {
            var s = url.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "https://$s"
            }
            return "$s/"
        }
    }
}