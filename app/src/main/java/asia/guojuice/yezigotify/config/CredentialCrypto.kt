package asia.guojuice.yezigotify.config

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import asia.guojuice.yezigotify.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 服务卡凭据的静态加密（AES-256-GCM，密钥存放在 Android Keystore）
 *
 * ## 为什么不用 EncryptedSharedPreferences
 *
 * androidx.security:security-crypto 在 **1.1.0（2025-07-30）把全部 API 标记为弃用**，
 * 官方明确「不会再有后续版本」，并建议直接使用平台 API + Android Keystore。
 * 继续依赖它就等于把一个已冻结的库钉进依赖树，所以这里直接用 Keystore 实现。
 *
 * 代价是自己写几十行加解密，换来的是**零新增依赖**（不用动 build.gradle），
 * 而且只加密 servers_json 这一个值 —— 比整包 EncryptedSharedPreferences 更贴合：
 * `gotify.xml` 里还有 developer_mode / 主题色 / 勿扰配置等一堆非敏感项，
 * 全加密只会徒增开机路径上的 Keystore 开销。
 *
 * ## 威胁模型（讲清楚，免得高估它的作用）
 *
 * Keystore 密钥绑在设备上且不可导出，所以：
 *   - ✅ 挡得住：`adb backup` 导出的 xml、root 后直接 cat 数据目录、取证镜像
 *             —— 这些拿到的都是密文，密钥拿不走
 *   - ❌ 挡不住：设备已解锁时的运行时攻击（以本 App 身份调用即可解密）、
 *             root 后直接调 Keystore 解密
 * 即**静态数据（at-rest）保护**，不是运行时保护。配合已有的 `allowBackup="false"`，
 * 能覆盖最常见的「备份 / 镜像泄露」场景。
 *
 * ## 失败策略
 *
 * 所有失败路径返回 null，由调用方退回明文，**绝不在开机路径上抛异常**。
 * 这个类会被 BootCompletedReceiver → ServerConfigStore 的同步读触发，
 * 崩在这里 App 直接起不来。宁可明文可用，也不能让用户的服务器配置凭空消失。
 */
object CredentialCrypto {

    private const val TAG = "CredentialCrypto"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * 密钥别名。改这个字符串 = 已存在的密文全部解不开（只能退回明文重新加密），
     * 所以一旦上线就不要动。
     */
    private const val KEY_ALIAS = "chatz_server_config_v1"

    private const val IV_LEN_BYTES = 12      // GCM 的标准 IV 长度
    private const val GCM_TAG_BITS = 128     // 认证标签长度

    /**
     * Keystore 支持 AES 是从 API 23（M）开始的；
     * 21 / 22 上只能明文存（那两档的 Keystore 根本没有 AES）。
     */
    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    @Volatile
    private var cachedKey: SecretKey? = null

    private val keyLock = Any()

    /** 取（或首次生成）Keystore 里的 AES-256 密钥；拿不到返回 null */
    private fun secretKey(): SecretKey? {
        if (!isAvailable) return null
        cachedKey?.let { return it }
        return synchronized(keyLock) {
            // 双检：SecretKey 生成要走 Keystore，别让并发调用各生成一次
            cachedKey?.let { return@synchronized it }
            loadOrCreateKey()
        }
    }

    // 这里全是 API 23+ 的 Keystore API。调用链上已经有 isAvailable 的 SDK_INT 判断，
    // 但 lint 看不穿那层间接判断，会报 NewApi，所以显式压掉
    @SuppressLint("NewApi")
    private fun loadOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        // load(null) 是 Keystore 的标准写法 —— 它不从输入流加载
        ks.load(null)

        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            cachedKey = existing
            existing
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // true = 每次加密强制随机 IV。这是默认值，写出来是防止以后有人手滑关掉：
                    // GCM 复用 IV 会直接泄漏两段明文的异或关系，等于把认证密钥交出去
                    .setRandomizedEncryptionRequired(true)
                    // 刻意不开 setUserAuthenticationRequired：
                    // 开机广播 / 后台保活要在用户解锁前就读取配置，开了就永远连不上服务器
                    .build()
            )
            val generated = generator.generateKey()
            cachedKey = generated
            generated
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "获取 Keystore 密钥失败: ${e.message}")
        null
    }

    /**
     * 加密。输出 = Base64(IV ‖ 密文‖GCM标签)
     *
     * IV 不需要保密，和密文放在一起是标准做法；GCM 的 doFinal 会把 128 位认证标签
     * 追加在密文尾部，所以解密时不用单独拆标签。
     *
     * @return Base64 密文；不可用或失败时返回 null
     */
    fun encrypt(plain: String): String? {
        val key = secretKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.e(TAG, "加密失败: ${e.message}")
            null
        }
    }

    /**
     * 解密 [encrypt] 的输出。
     *
     * Keystore 条目被清掉（换 ROM、清密钥、部分厂商的密钥存储异常）时这里会失败，
     * 属于**预期内**的失败：调用方要退回明文或让用户重新配置，不能当崩溃处理。
     *
     * @return 明文；失败返回 null
     */
    fun decrypt(blob: String): String? {
        val key = secretKey() ?: return null

        val raw = try {
            Base64.decode(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.e(TAG, "密文不是合法 Base64: ${e.message}")
            return null
        }
        if (raw.size <= IV_LEN_BYTES) {
            AppLog.e(TAG, "密文长度异常: ${raw.size}")
            return null
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // GCMParameterSpec 的 4 参构造直接指定 raw 里 IV 的偏移和长度，
            // 下面 doFinal 也用偏移量跳过 IV，省掉两次数组拷贝
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LEN_BYTES)
            )
            String(
                cipher.doFinal(raw, IV_LEN_BYTES, raw.size - IV_LEN_BYTES),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "解密失败: ${e.message}")
            null
        }
    }
}
