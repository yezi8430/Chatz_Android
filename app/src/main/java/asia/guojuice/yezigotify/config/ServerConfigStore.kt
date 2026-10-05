package asia.guojuice.yezigotify.config

import android.content.Context
import android.content.SharedPreferences
import asia.guojuice.yezigotify.AppLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 服务卡（多服务器）配置的唯一读写入口
 *
 * 为什么用 SharedPreferences 里的一个 JSON 数组，而不是 Room 表：
 *   - 读取点全在冷启动早期路径（BootCompletedReceiver /
 *     GotifyApplication.onCreate），SharedPreferences 可以同步读；放进 Room 会把
 *     DB 初始化依赖拖进开机路径。
 *   - 数量很小（< 20），不需要查询能力。
 *   - 并发风险（整个数组重写）由内部 Mutex + 单写者（只有设置页写）消除。
 *
 * ## 凭据加密
 *
 * servers_json 里带着 token / 用户名 / 密码，所以落盘时走 [CredentialCrypto]
 * 加密成 `servers_json_enc`（AES-256-GCM，密钥在 Keystore）。
 * 加密不可用时（API < 23 或 Keystore 异常）退回旧的明文 `servers_json` ——
 * 读的时候两份都认，优先密文。
 *
 * ⚠️ 任何地方都不应该直接 prefs.edit() 写这两个 key，必须走本类的 saveAll()。
 */
object ServerConfigStore {

    private const val TAG = "ServerConfigStore"
    private const val PREFS_NAME = "gotify"

    /**
     * 配置数组的存储 key（明文，旧格式）
     *
     * 只在两种情况下还会出现：
     *   1. 老版本升级上来、还没跑过 [encryptExistingIfNeeded] 的存量数据
     *   2. [CredentialCrypto] 不可用（API < 23 / Keystore 异常）时的回落
     */
    private const val PREFS_KEY = "servers_json"

    /** 配置数组的存储 key（加密后，新格式）。读取优先用这个 */
    private const val PREFS_KEY_ENC = "servers_json_enc"

    private const val KEY_REVISION = "servers_revision"

    /** 旧配置迁移出来的两张卡的固定 id */
    const val LEGACY_SERVER_ID = "legacy-gotify"
    const val LEGACY_NTFY_SERVER_ID = "legacy-ntfy"

    /** 槽位 0 保留给旧配置：此时 `本地id == 远程id`，旧数据零改写 */
    const val SLOT_LEGACY = 0

    /** 槽位上限：127 shl 56 与 127 shl 24 均为正数，不越界 */
    const val MAX_SLOT = 127

    // ===== 旧版单源 key =====
    // 只在 migrateLegacyIfNeeded() 里作为**一次性迁移输入**读取，
    // 迁移完成后本类不再读它们（P7 起已取消镜像回写）。
    //
    // 其中 4 个凭据类 key（clientToken / ntfy_token / ntfy_username / ntfy_password）
    // 在迁移成功写入新格式后会被**主动删除** —— 不删的话明文副本一直留在
    // gotify.xml 里，加密就白做了。删除是安全的：这 10 个 key 全项目只有本类读。
    private const val K_SERVER_URL = "serverUrl"
    private const val K_CLIENT_TOKEN = "clientToken"
    private const val K_CAPS_TYPE = "server_caps_type"
    private const val K_GOTIFY_KEEPALIVE = "gotify_keepalive_interval"
    private const val K_NTFY_URL = "ntfy_server_url"
    private const val K_NTFY_TOPIC = "ntfy_topic"
    private const val K_NTFY_AUTH_TYPE = "ntfy_auth_type"
    private const val K_NTFY_TOKEN = "ntfy_token"
    private const val K_NTFY_USERNAME = "ntfy_username"
    private const val K_NTFY_PASSWORD = "ntfy_password"
    private const val K_NTFY_KEEPALIVE = "ntfy_keepalive_interval"

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val mutex = Mutex()

    /** 进程内缓存：key 是 revision，避免每次调用都解析 JSON */
    @Volatile private var cache: List<ServerConfig>? = null
    @Volatile private var cacheRevision: Int = Int.MIN_VALUE

    private val _configs = MutableStateFlow<List<ServerConfig>>(emptyList())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== 读 ====================

    /** 全部服务卡（含未启用）。同步、任意线程可调。 */
    fun all(context: Context): List<ServerConfig> {
        val p = prefs(context)
        val rev = p.getInt(KEY_REVISION, 0)
        cache?.let { if (cacheRevision == rev) return it }
        val list = readFromPrefs(p)
        cache = list
        cacheRevision = rev
        publishIfChanged(list)
        return list
    }

    /** 已启用的服务卡 */
    fun enabled(context: Context): List<ServerConfig> =
        all(context).filter { it.enabled }

    fun byId(context: Context, id: String): ServerConfig? =
        all(context).firstOrNull { it.id == id }

    fun bySlot(context: Context, slot: Int): ServerConfig? =
        all(context).firstOrNull { it.slot == slot }

    /** 是否至少有一台可用的服务器（等价于旧版"url + token 非空"） */
    fun hasAnyEnabled(context: Context): Boolean =
        enabled(context).any { it.isUsable }

    /** 已启用的协议服务器（Chatz / Gotify），它们才有 HTTP + WS 协议 */
    fun enabledProtocolServers(context: Context): List<ServerConfig> =
        enabled(context).filter { it.isProtocolServer }

    fun revision(context: Context): Int = prefs(context).getInt(KEY_REVISION, 0)

    /**
     * P2 临时收口：旧配置迁移出的「协议服务器」（Chatz / Gotify）
     *
     * P2 阶段多连接尚未接入，WS / 全量同步拿到的消息都归属 slot 0 的这台服务器，
     * 所以统一从这里取「来源服务器」的身份 + slot。找不到配置时给一个 slot 0 的兜底，
     * 避免 NPE（此时本来也没有数据）。
     *
     * ⚠️ P3/P5 会替换为「连接自带的 ServerConfig」，调用点不要再扩散。
     */
    fun legacyProtocolServer(context: Context): ServerConfig =
        byId(context, LEGACY_SERVER_ID)
            ?: ServerConfig(id = LEGACY_SERVER_ID, slot = SLOT_LEGACY, kind = ServerKind.GOTIFY)

    /**
     * 观察配置变化（UI / ViewModel 用）
     *
     * 用 onStart 补一次当前值：_configs 在冷启动时是空的，
     * 直到有人调用 all() 才会填上。
     */
    fun observe(context: Context): Flow<List<ServerConfig>> =
        _configs.asStateFlow().onStart { emit(all(context)) }

    // ==================== 写 ====================

    /**
     * 全量保存
     *
     * ⚠️ 这里**不再**把 slot 0 的卡回写成旧 key。P1 阶段那套镜像回写是为了让尚未
     * 改造的读取点也能拿到正确值；现在所有读取点都已改走本类，
     * 旧 key 只作为 [migrateLegacyIfNeeded] 的**迁移输入**保留。
     */
    suspend fun saveAll(context: Context, list: List<ServerConfig>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val p = prefs(context)
                val rev = p.getInt(KEY_REVISION, 0) + 1
                val editor = p.edit().putInt(KEY_REVISION, rev)
                val encrypted = writeServersJson(editor, gson.toJson(list))
                editor.apply()

                cache = list
                cacheRevision = rev
                publishIfChanged(list)

                AppLog.d(
                    TAG,
                    "保存服务卡 ${list.size} 张，revision=$rev，" +
                        if (encrypted) "已加密" else "明文（Keystore 不可用）"
                )
            }
        }
    }

    /** 删除一张卡（调用方负责同时清理该 serverId 的本地数据） */
    suspend fun remove(context: Context, id: String) {
        saveAll(context, all(context).filterNot { it.id == id })
    }

    /**
     * 分配一个新的命名空间槽位（1..MAX_SLOT 中最小的空闲值）
     *
     * ⚠️ 槽位**永不复用**：删卡时必须先 deleteByServer 清掉该槽数据，
     *    否则新服务器会继承旧槽，远程 id 撞上残留本地 id。
     *    这里只保证当前未被占用，历史占用过的由调用方保证已清理数据。
     */
    fun allocateSlot(context: Context): Int {
        val used = all(context).map { it.slot }.toSet()
        for (s in 1..MAX_SLOT) if (s !in used) return s
        throw IllegalStateException("服务卡数量已达上限（$MAX_SLOT 张）")
    }

    // ==================== 旧配置迁移 ====================

    /**
     * 把旧版那 10 个 key 迁成服务卡列表。幂等，重复调用安全。
     *
     * @return 是否真的执行了迁移
     */
    fun migrateLegacyIfNeeded(context: Context): Boolean {
        val p = prefs(context)

        // 主保险：只要任何一种格式的 servers_json 存在（哪怕是 []）就不再迁移
        if (p.contains(PREFS_KEY) || p.contains(PREFS_KEY_ENC)) return false

        val list = buildLegacyList(p)
        val rev = p.getInt(KEY_REVISION, 0) + 1

        val editor = p.edit().putInt(KEY_REVISION, rev)
        writeServersJson(editor, gson.toJson(list))

        // 凭据已经写进新格式了，旧 key 里的明文副本必须抹掉 ——
        // 否则"加密"只是加了一份密文，明文还躺在同一个 xml 里，等于没做。
        // 这几个 key 全项目只有本类读（已 grep 确认），删掉不会影响别处。
        editor.remove(K_CLIENT_TOKEN)
            .remove(K_NTFY_TOKEN)
            .remove(K_NTFY_USERNAME)
            .remove(K_NTFY_PASSWORD)
            // 地址 / topic / 保活间隔不是秘密，留着不影响安全，也省得
            // 别处（历史代码路径）还有引用时出问题
            .commit()          // 同步提交：迁移必须在后续代码读配置之前落盘

        cache = list
        cacheRevision = rev
        publishIfChanged(list)

        AppLog.d(TAG, "旧配置迁移完成：${list.size} 张卡（slot=$SLOT_LEGACY），已清除明文凭据副本")
        return true
    }

    /**
     * 把已经存在的明文 `servers_json` 转成加密格式
     *
     * 为什么需要单独一步：[migrateLegacyIfNeeded] 的守卫是"servers_json 已存在就跳过"，
     * 而 P7 起存量用户**已经有**明文 servers_json 了 —— 光靠迁移函数永远不会触发加密，
     * 明文会一直留到用户手动改一次服务卡为止。所以启动时显式补这一步。
     *
     * 幂等：加密版本已存在时，只负责清掉残留的明文副本。
     *
     * @return 是否真的执行了加密
     */
    fun encryptExistingIfNeeded(context: Context): Boolean {
        if (!CredentialCrypto.isAvailable) return false

        val p = prefs(context)

        // 密文已在 → 只清明文残留（例如加密后又曾因 Keystore 异常回落写过明文）
        if (p.contains(PREFS_KEY_ENC)) {
            if (p.contains(PREFS_KEY)) {
                p.edit().remove(PREFS_KEY).commit()
                AppLog.d(TAG, "已清除残留的明文 servers_json")
            }
            return false
        }

        val plain = p.getString(PREFS_KEY, null) ?: return false
        val enc = CredentialCrypto.encrypt(plain)
        if (enc == null) {
            AppLog.w(TAG, "加密失败，继续沿用明文存储")
            return false
        }

        p.edit()
            .putString(PREFS_KEY_ENC, enc)
            .remove(PREFS_KEY)
            .commit()

        // 缓存里的 revision 没变，内容也没变，不用动 cache / _configs
        AppLog.d(TAG, "存量 servers_json 已加密")
        return true
    }

    /**
     * 把旧 key 转成服务卡
     *
     * 判定条件刻意对齐旧版**用户可见**的"已配置"判定
     * （MainActivity.isAnyServiceConfigured / ServerSettingsScreen.isNtfyConfigured），
     * 而不是 GotifyService 内部的默认值，否则会给用户凭空多出一张卡。
     *
     * 注意 ntfy 的判定用 url + topic **都非空**：
     * GotifyService 里 topic 的兜底默认是 "dy"，但用户仍看不到入口 —— 若按 "dy" 建卡，
     * 升级后会突然冒出一个 ntfy 条目，属于行为回归。
     */
    private fun buildLegacyList(p: SharedPreferences): List<ServerConfig> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<ServerConfig>()

        val url = p.getString(K_SERVER_URL, "")?.trim().orEmpty()
        val token = p.getString(K_CLIENT_TOKEN, "")?.trim().orEmpty()
        if (url.isNotEmpty() && token.isNotEmpty()) {
            out += ServerConfig(
                id = LEGACY_SERVER_ID,
                slot = SLOT_LEGACY,
                kind = if (p.getString(K_CAPS_TYPE, null) == "CHATZ") ServerKind.CHATZ
                else ServerKind.GOTIFY,
                enabled = true,
                url = url,
                authMode = AuthMode.TOKEN,
                token = token,
                keepAliveSeconds = p.getInt(K_GOTIFY_KEEPALIVE, 0),
                createdAt = now
            )
        }

        val nUrl = p.getString(K_NTFY_URL, "")?.trim().orEmpty()
        val nTopic = p.getString(K_NTFY_TOPIC, "")?.trim().orEmpty()
        if (nUrl.isNotEmpty() && nTopic.isNotEmpty()) {
            val isToken = p.getString(K_NTFY_AUTH_TYPE, "userpass") == "token"
            out += ServerConfig(
                id = LEGACY_NTFY_SERVER_ID,
                slot = SLOT_LEGACY,       // 与旧协议服务器共享 slot 0：旧数据零改写
                kind = ServerKind.NTFY,
                enabled = true,
                url = nUrl,
                authMode = if (isToken) AuthMode.TOKEN else AuthMode.USER_PASSWORD,
                token = if (isToken) p.getString(K_NTFY_TOKEN, "").orEmpty() else "",
                username = if (isToken) "" else p.getString(K_NTFY_USERNAME, "").orEmpty(),
                password = if (isToken) "" else p.getString(K_NTFY_PASSWORD, "").orEmpty(),
                topic = nTopic,
                keepAliveSeconds = p.getInt(K_NTFY_KEEPALIVE, 0),
                createdAt = now
            )
        }
        return out
    }

    // ==================== 内部工具 ====================

    private fun readFromPrefs(p: SharedPreferences): List<ServerConfig> {
        // 优先读加密版本
        val enc = p.getString(PREFS_KEY_ENC, null)
        if (!enc.isNullOrBlank()) {
            val plain = CredentialCrypto.decrypt(enc)
            if (plain != null) {
                return parseServersJson(plain, encrypted = true)
            }
            // 解不开（Keystore 条目丢了）→ 掉落去试明文副本，
            // 明文也读不到才算真的没有配置
            AppLog.e(TAG, "解密 servers_json_enc 失败，回退明文 servers_json")
        }

        // 旧格式 / 加密不可用时的回落
        val json = p.getString(PREFS_KEY, null)
        if (json.isNullOrBlank()) return emptyList()
        return parseServersJson(json, encrypted = false)
    }

    private fun parseServersJson(json: String, encrypted: Boolean): List<ServerConfig> {
        return runCatching {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson<List<ServerConfig>>(json, type) ?: emptyList()
        }.getOrElse {
            // 解析失败不能清空配置（否则用户会莫名其妙丢服务器），返回空并留痕
            val label = if (encrypted) "servers_json_enc" else "servers_json"
            AppLog.e(TAG, "解析 $label 失败，按空列表处理: ${it.message}")
            emptyList()
        }
    }

    /**
     * 写入 servers_json：优先加密
     *
     * 加密成功时**顺手删掉明文副本** —— 不然老版本的明文会一直留在 xml 里。
     * 加密失败时只写明文，且**不删**可能存在的密文副本（那份是好的，
     * 读的时候优先用密文，下次加密可用时会自动覆盖回来）。
     */
    private fun writeServersJson(editor: SharedPreferences.Editor, json: String): Boolean {
        val enc = CredentialCrypto.encrypt(json)
        return if (enc != null) {
            editor.putString(PREFS_KEY_ENC, enc).remove(PREFS_KEY)
            true
        } else {
            editor.putString(PREFS_KEY, json)
            false
        }
    }

    private fun publishIfChanged(list: List<ServerConfig>) {
        if (_configs.value != list) _configs.value = list
    }
}