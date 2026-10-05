package asia.guojuice.yezigotify.ui.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.capabilities.AccountOpResult
import asia.guojuice.yezigotify.capabilities.ChatzApi
import asia.guojuice.yezigotify.capabilities.ChatzChannel
import asia.guojuice.yezigotify.capabilities.ChatzMe
import asia.guojuice.yezigotify.db.LocalIds
import asia.guojuice.yezigotify.ui.common.DialogTabBar
import asia.guojuice.yezigotify.ui.settings.settingsSwitchColors
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 频道图标上限 5MB（与服务端 express.raw 的 limit 一致） */
private const val MAX_ICON_BYTES = 5L * 1024 * 1024

/** 频道管理弹窗的三段标签（与「个人信息」弹窗的三段对应） */
private enum class ChannelTab(val label: String) {
    Info("基本信息"),
    Icon("频道图标"),
    Password("订阅密码")
}

/**
 * 管理频道：改名称 / 描述 / 公开性 / 图标 / 订阅密码
 *
 * ── 布局：完全对齐「个人信息」弹窗（ui.settings.AccountProfileDialog）──────────
 *   1. 头部一行：图标 + 频道名 + 「公开 / 私有」徽标
 *   2. 一排分段标签（基本信息 / 频道图标 / 订阅密码）—— 用的是共用组件
 *      `ui.common.DialogTabBar`，和个人信息那一排是同一份实现
 *   3. 中段**固定高度**（236dp）+ 内部滚动，切标签时弹窗不忽高忽低
 *   4. 底部一行汇总提示
 *
 * ── 为什么必须分标签 ──
 * 五个字段平铺会让弹窗要滚很久；拆成三段后每段都很短，一眼看得全。
 *
 * ── 内容结构从第一帧就完整 ──────────────────────────────
 * ⚠️ 绝不能写 `if (loading) return@Column` / `if (loading) Text("读取中…")`。
 *    AlertDialog 进场自带缩放+淡入动画，如果第一帧只有一行「读取中…」，
 *    数据回来时突然撑成整屏，动画就作用在这个剧变上 ⇒ 用户看到的是
 *    「弹窗从角落往中间抽搐」。参照个人信息弹窗：它 loading 时只是把
 *    名字显示成「…」，字段结构始终在，所以从来没这个毛病。
 *
 * ── 保存：一个统一按钮，先校验再提交 ──────────────────
 * 校验不过时**跳到出错的那个标签**并高亮输入框（只报第一个错误），
 * 跟个人信息弹窗一致。
 *
 * 图标 / 密码都是「先落草稿、点保存才提交」：
 *   图标：选完只预览，保存时才 upload / remove
 *   密码：服务端只回「有没有密码」，从不回密码本身，所以只能让用户输新值
 *
 * 权限与服务端一致：`isSuper || creator_id === 当前用户`。
 * 没有权限时字段置灰、保存按钮不可点。
 *
 * @param serverUrl 该频道所在服务器的地址（用于构造 ChatzApi）
 * @param token     该服务器的凭据
 */
@Composable
fun ChannelManageDialog(
    serverUrl: String,
    token: String,
    channel: ChatzChannel,
    onDismiss: () -> Unit,
    /** 全部保存成功时回调（调用方据此刷新抽屉里的频道信息并关闭） */
    onSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(serverUrl, token) { ChatzApi.getInstance(serverUrl, token) }
    // 出站用远程 id；本地 id 高位带了 slot
    val remoteId = if (channel.remoteId != 0) channel.remoteId
    else LocalIds.remoteChannelIdOf(channel.id)

    var me by remember { mutableStateOf<ChatzMe?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ChannelTab.Info) }

    // ===== 草稿（初值来自频道对象）=====
    var name by remember { mutableStateOf(channel.name) }
    var desc by remember { mutableStateOf(channel.description.orEmpty()) }
    var isPublic by remember { mutableStateOf(channel.isPublic) }
    var nameError by remember { mutableStateOf(false) }

    // ===== 待提交的图标改动（点「保存」才生效，见 submitAll）=====
    // 三态：null = 没动；ByteArray = 选了新图；iconCleared = 点了移除
    var pendingIcon by remember { mutableStateOf<ByteArray?>(null) }
    var pendingIconMime by remember { mutableStateOf("image/jpeg") }
    var iconCleared by remember { mutableStateOf(false) }
    var pendingIconPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    // 服务端当前图标（绝对地址）。选完图 / 点移除只改草稿，不改它，
    // 保存时才用它与服务端对齐。
    var savedIconPath by remember { mutableStateOf(channel.image) }

    // ===== 订阅密码（服务端只回「有没有密码」，从不回密码本身，所以这里只能让用户输新值）=====
    // pwdDraft 非空 = 要设置/更换密码；pwdCleared = 点了「清除密码」；两者都没动 = 不动密码
    var pwdDraft by remember { mutableStateOf("") }
    var pwdCleared by remember { mutableStateOf(false) }
    var pwdError by remember { mutableStateOf(false) }

    val permitted = me != null && (me!!.isSuper || channel.creatorId == me!!.id)
    // 读身份期间先按「可编辑」渲染：绝大多数情况是从「管理频道」入口进来的、本来就有权限，
    // 等 /auth/me 回来确认没权限再置灰 —— 免得第一帧全灰、回来又变亮地闪一下。
    val fieldsEnabled = me == null || permitted
    val canSave = !loading && !busy && permitted

    LaunchedEffect(serverUrl, token) {
        val loaded = withContext(Dispatchers.IO) { api.getMe() }
        if (loaded == null) {
            message = "读取账号失败，请检查网络"
            messageError = true
        } else {
            me = loaded
            if (!(loaded.isSuper || channel.creatorId == loaded.id)) {
                message = "你没有管理这个频道的权限（仅频道创建者或超级管理员可改）"
                messageError = true
            }
        }
        loading = false
    }

    // 选图 → 只做本地校验 + 预览，**不上传**（真上传在点保存时）
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            // 服务端 raw 解析器只认 image/*，这里必须给 image 开头的类型
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null || bytes.isEmpty() -> {
                    message = "无法读取该图片"
                    messageError = true
                }
                bytes.size.toLong() > MAX_ICON_BYTES -> {
                    message = "图片太大（上限 5MB）"
                    messageError = true
                }
                else -> {
                    pendingIcon = bytes
                    pendingIconMime = mime
                    iconCleared = false
                    pendingIconPreview = withContext(Dispatchers.Default) {
                        runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    message = "已选择新图标，点「保存」生效"
                    messageError = false
                }
            }
        }
    }

    /** 提交（**调用方必须先跑完校验**）：文字字段走 PATCH，图标单独 upload / remove */
    suspend fun submitAll() {
        val newName = name.trim()
        val newDesc = desc.trim()
        val newPwd = pwdDraft.trim()
        val changedText = newName != channel.name ||
                newDesc != channel.description.orEmpty() ||
                isPublic != channel.isPublic
        val changedIcon = pendingIcon != null || iconCleared
        val changedPassword = pwdCleared || newPwd.isNotEmpty()

        // ⚠️ 一项都没改就别发请求：否则点一下「保存」会真的 PATCH 一次，
        //    然后回报「已保存：频道信息」—— 用户什么都没改却说保存成功，很误导。
        //    （校验在 confirmButton 里跑完了，这里只补这条"无改动"判断）
        if (!changedText && !changedIcon && !changedPassword) {
            message = "没有需要保存的改动"
            messageError = false
            return
        }

        val okList = mutableListOf<String>()
        var failure: String? = null

        // 文字字段 + 密码（名称 / 描述 / 公开性 / 密码，一起走 PATCH）
        if ((changedText || changedPassword) && failure == null) {
            val passwordParam = when {
                pwdCleared -> ""
                newPwd.isNotEmpty() -> newPwd
                else -> null
            }
            when (val r = withContext(Dispatchers.IO) {
                api.updateChannel(remoteId, newName, newDesc, isPublic, passwordParam)
            }) {
                is AccountOpResult.Ok -> {
                    okList += "频道信息"
                    if (changedPassword) {
                        okList += if (pwdCleared) "密码（已清除）" else "密码"
                        pwdDraft = ""
                        pwdCleared = false
                    }
                }
                is AccountOpResult.Rejected -> { failure = r.message; nameError = true }
                is AccountOpResult.Failed -> failure = r.message
            }
        }

        // 图标
        if (changedIcon && failure == null) {
            val bytes = pendingIcon
            if (bytes != null) {
                when (val r = withContext(Dispatchers.IO) {
                    api.uploadChannelIcon(remoteId, bytes, pendingIconMime)
                }) {
                    is AccountOpResult.Ok -> {
                        savedIconPath = api.absoluteUrl(r.value.image)
                        pendingIcon = null
                        pendingIconPreview = null
                        okList += "图标"
                    }
                    is AccountOpResult.Rejected -> failure = r.message
                    is AccountOpResult.Failed -> failure = r.message
                }
            } else {
                when (val r = withContext(Dispatchers.IO) { api.removeChannelIcon(remoteId) }) {
                    is AccountOpResult.Ok -> {
                        savedIconPath = null
                        iconCleared = false
                        okList += "图标（已移除）"
                    }
                    is AccountOpResult.Rejected -> failure = r.message
                    is AccountOpResult.Failed -> failure = r.message
                }
            }
        }

        message = when {
            failure != null && okList.isEmpty() -> failure
            failure != null -> "已保存：${okList.joinToString("、")}；失败：$failure"
            else -> "已保存：${okList.joinToString("、")}"
        }
        messageError = failure != null

        // 全部成功才关闭；部分成功留在原地让用户看清是哪项失败
        if (failure == null) onSaved()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理频道") },
        text = {
            Column {
                // ===== 头部：图标 + 频道名 + 公开/私有徽标（对齐个人信息弹窗头部）=====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelIconPreview(
                        url = savedIconPath,
                        fallback = channel.name,
                        localPreview = pendingIconPreview,
                        cleared = iconCleared
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = channel.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (channel.isPublic) "公开频道" else "私有频道",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== 标签栏（与个人信息弹窗共用同一份实现）=====
                DialogTabBar(
                    labels = ChannelTab.values().map { it.label },
                    selectedIndex = tab.ordinal,
                    // 有未保存错误的标签打个点，切走了也看得见
                    errorIndexes = buildSet {
                        if (nameError) add(ChannelTab.Info.ordinal)
                        if (pwdError) add(ChannelTab.Password.ordinal)
                    },
                    onSelect = { index ->
                        tab = ChannelTab.values()[index]
                    }
                )

                Spacer(Modifier.height(14.dp))

                // 固定高度：切标签时弹窗不跳动；内容超了内部滚
                // ⚠️ 与个人信息弹窗取同一个值（236dp），两边观感才一致。
                Box(modifier = Modifier.heightIn(min = 236.dp, max = 236.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        when (tab) {
                            ChannelTab.Info -> ChannelInfoTabContent(
                                name = name,
                                nameError = nameError,
                                desc = desc,
                                isPublic = isPublic,
                                enabled = fieldsEnabled,
                                onNameChange = { name = it; nameError = false },
                                onDescChange = { desc = it },
                                onPublicChange = { isPublic = it }
                            )

                            ChannelTab.Icon -> ChannelIconTabContent(
                                hasIcon = savedIconPath != null || pendingIcon != null,
                                enabled = fieldsEnabled && !busy,
                                localPreview = pendingIconPreview,
                                cleared = iconCleared,
                                fallback = channel.name,
                                savedUrl = savedIconPath,
                                onPick = { iconPicker.launch(arrayOf("image/*")) },
                                onRemove = {
                                    pendingIcon = null
                                    pendingIconPreview = null
                                    iconCleared = true
                                    message = "已标记移除图标，点「保存」生效"
                                    messageError = false
                                }
                            )

                            ChannelTab.Password -> ChannelPasswordTabContent(
                                pwd = pwdDraft,
                                pwdError = pwdError,
                                enabled = fieldsEnabled,
                                protected = channel.passwordProtected,
                                cleared = pwdCleared,
                                onPwdChange = {
                                    pwdDraft = it
                                    pwdError = false
                                    pwdCleared = false
                                },
                                onClear = {
                                    pwdDraft = ""
                                    pwdCleared = true
                                    pwdError = false
                                    message = "已标记清除密码，点「保存」生效"
                                    messageError = false
                                },
                                onCancelClear = {
                                    pwdCleared = false
                                    message = null
                                }
                            )
                        }
                    }
                }

                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = if (messageError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    message = null
                    messageError = false

                    // ── 先本地校验，再提交 ──
                    // 跟个人信息弹窗一样：**跳到出错的那个标签**并高亮，
                    // 只报第一个错误，别一次甩三个红字给用户。
                    val newName = name.trim()
                    val newPwd = pwdDraft.trim()
                    when {
                        newName.isEmpty() -> {
                            nameError = true
                            tab = ChannelTab.Info
                            message = "名称不能为空"
                            messageError = true
                        }
                        newPwd.isNotEmpty() && (newPwd.length < 4 || newPwd.length > 64) -> {
                            pwdError = true
                            tab = ChannelTab.Password
                            message = "频道密码需 4-64 位"
                            messageError = true
                        }
                        else -> scope.launch {
                            busy = true
                            submitAll()
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 标签一：名称 + 描述 + 公开性 */
@Composable
private fun ChannelInfoTabContent(
    name: String,
    nameError: Boolean,
    desc: String,
    isPublic: Boolean,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onDescChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("名称") },
        singleLine = true,
        enabled = enabled,
        isError = nameError,
        supportingText = { Text("频道名，不能为空") }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = desc,
        onValueChange = onDescChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("描述（选填）") },
        minLines = 1,
        maxLines = 3,
        enabled = enabled,
        supportingText = { Text("留空则清除") }
    )

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("公开频道", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "公开后所有人都能在「发现频道」里看到并订阅",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = isPublic,
            onCheckedChange = onPublicChange,
            enabled = enabled,
            colors = settingsSwitchColors()
        )
    }
}

/** 标签二：图标（选图只落草稿，点保存才上传） */
@Composable
private fun ChannelIconTabContent(
    hasIcon: Boolean,
    enabled: Boolean,
    localPreview: ImageBitmap?,
    cleared: Boolean,
    fallback: String,
    savedUrl: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    ChannelIconPreview(
        url = savedUrl,
        fallback = fallback,
        localPreview = localPreview,
        cleared = cleared
    )

    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onPick, enabled = enabled) {
            Text(if (hasIcon) "更换图标" else "设置图标")
        }
        if (hasIcon) {
            TextButton(onClick = onRemove, enabled = enabled) { Text("移除图标") }
        }
    }
}

/** 标签三：订阅密码（门槛） */
@Composable
private fun ChannelPasswordTabContent(
    pwd: String,
    pwdError: Boolean,
    enabled: Boolean,
    protected: Boolean,
    cleared: Boolean,
    onPwdChange: (String) -> Unit,
    onClear: () -> Unit,
    onCancelClear: () -> Unit
) {
    Text(
        text = "设置后任何人订阅都要输密码（创建者与超级管理员免密）",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = pwd,
        onValueChange = onPwdChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (protected) "更换订阅密码（选填）" else "设置订阅密码（选填）") },
        singleLine = true,
        enabled = enabled,
        isError = pwdError,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = { Text("留空则不修改；4-64 位") }
    )

    if (protected && !cleared) {
        TextButton(onClick = onClear, enabled = enabled) { Text("清除密码") }
    } else if (cleared) {
        TextButton(onClick = onCancelClear, enabled = enabled) { Text("取消清除") }
    }
}

/**
 * 频道图标预览：圆形（对齐抽屉里频道的圆形图标与个人信息头像的 48dp 圆形）
 *
 * [cleared] = true 时即使有 url 也显示占位符（用户已点「移除」，只等保存）。
 */
@Composable
private fun ChannelIconPreview(
    url: String?,
    fallback: String,
    localPreview: ImageBitmap? = null,
    cleared: Boolean = false
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching { Glide.with(context).asBitmap().load(url).submit().get() }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        val display = if (cleared) null else (localPreview ?: bitmap?.asImageBitmap())
        if (display != null) {
            Image(
                bitmap = display,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = fallback.trim().take(1).uppercase().ifBlank { "?" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
