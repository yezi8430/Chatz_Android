package asia.guojuice.yezigotify.config

import asia.guojuice.yezigotify.AppLog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Chatz 账号登录的结果
 *
 * 刻意用 sealed class 而不是 `Result<String>`：调用方需要拿到**给用户看的中文文案**
 * （401 / 429 / 网络错误各有不同提示），用 Result 的 Throwable 会让调用方去猜。
 */
sealed class LoginResult {
    /** 登录成功，[token] 为服务端 devices.token，可直接当 Bearer / `?token=` 用 */
    data class Success(val token: String) : LoginResult()

    /** 失败，[message] 是可直接 Toast 的中文文案 */
    data class Failure(val message: String) : LoginResult()
}

/**
 * 用「用户名 + 密码」登录 Chatz，换取设备 Token
 *
 * 服务端实现见 `users.js` 的 `POST /auth/login`：
 *   - 请求体 `{ username, password, deviceName }`
 *   - 成功 200 → `{ user: {...}, token: "..." }`（token 在**顶层**，不在 user 里）
 *   - 401 → `{ error: '用户名或密码错误' }`（用户不存在或密码错，两种情况同一句）
 *   - 429 → 限速（每 IP 每 5 分钟 30 次；每 IP + 用户名每 5 分钟 5 次，见 `rateLimit.js`）
 *
 * ⚠️ 只在保存时调用一次，**不持久化明文密码**：服务端 devices.token 无过期时间，
 *    换到 Token 后即可长期使用，不需要自动重登。
 *
 * 用裸 OkHttp 而不是 Retrofit：只有一个请求，且 token 在顶层 / 错误体不是标准 Gotify 结构，
 * 手工解析比新增一个接口 + DTO 更简单（也避免 `Map`/`List` 作为 `@Body` 的坑）。
 */
suspend fun loginChatz(
    baseUrl: String,
    username: String,
    password: String,
    deviceName: String
): LoginResult = withContext(Dispatchers.IO) {
    val base = normalizeBaseUrl(baseUrl)
    if (base.isEmpty()) return@withContext LoginResult.Failure("请先填写服务器地址")
    if (username.isBlank() || password.isBlank()) {
        return@withContext LoginResult.Failure("请填写用户名和密码")
    }

    try {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
            addProperty("deviceName", deviceName)
        }
        val request = Request.Builder()
            .url("$base/auth/login")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        authClient.newCall(request).execute().use { response ->
            val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()

            if (response.isSuccessful) {
                val token = extractToken(raw)
                if (token.isNullOrBlank()) {
                    AppLog.e(TAG, "登录成功但响应里没有 token: ${raw.take(200)}")
                    LoginResult.Failure("登录成功但服务器未返回 Token，请检查服务端版本")
                } else {
                    AppLog.d(TAG, "Chatz 登录成功（device=$deviceName）")
                    LoginResult.Success(token)
                }
            } else {
                when (response.code) {
                    400 -> LoginResult.Failure("用户名和密码不能为空")
                    401 -> LoginResult.Failure("用户名或密码错误")
                    429 -> LoginResult.Failure("尝试过于频繁，请稍后再试")
                    else -> {
                        AppLog.e(TAG, "登录失败 HTTP ${response.code}: ${raw.take(200)}")
                        LoginResult.Failure("登录失败（HTTP ${response.code}）")
                    }
                }
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "登录请求异常: ${e.message}")
        LoginResult.Failure("网络错误，无法连接服务器")
    }
}

/**
 * 从响应体里取 token
 *
 * 服务端当前返回顶层 `token`（users.js）；同时兼容 `user.token` 这种嵌套写法，
 * 防止服务端版本差异导致登录"成功但没 token"。
 */
private fun extractToken(rawJson: String): String? {
    val obj = runCatching { JsonParser.parseString(rawJson).asJsonObject }.getOrNull() ?: return null
    obj.get("token")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching {
        obj.getAsJsonObject("user")?.get("token")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()
}

/** 去掉尾部斜杠；没写协议时按 https 补（与 ServerCapabilitiesHolder.normalizeBase 一致） */
private fun normalizeBaseUrl(url: String): String {
    var s = url.trim().trimEnd('/')
    if (s.isEmpty()) return ""
    if (!s.startsWith("http://") && !s.startsWith("https://")) {
        s = "https://$s"
    }
    return s
}

private const val TAG = "ServerAuth"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * 登录专用的 OkHttpClient
 *
 * 不复用 GotifyApi 的 client（那是 public 的 companion 私有字段，拿不到），
 * 自己建一个短超时的：登录要么很快成功，要么快速失败给用户提示。
 */
private val authClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}