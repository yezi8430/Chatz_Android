package asia.guojuice.yezigotify.ui.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import asia.guojuice.yezigotify.MessageViewModel
import asia.guojuice.yezigotify.capabilities.AccountOpResult
import asia.guojuice.yezigotify.capabilities.ChatzApi
import asia.guojuice.yezigotify.capabilities.ChatzDevice
import asia.guojuice.yezigotify.capabilities.ChatzMe
import asia.guojuice.yezigotify.utils.roleLabel
import asia.guojuice.yezigotify.ui.common.DialogTabBar
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 服务端对头像的限制（见 users.js 的 POST /user/avatar） */
private const val MAX_AVATAR_BYTES = 5L * 1024 * 1024

/**
 * 个人信息：改昵称 / 头像 / 用户名 / 邮箱 / 密码
 *
 * 这些接口（`PATCH /user/profile`、`POST /user/avatar`、`PATCH /user/username`、
 * `PATCH /user/email`、`PATCH /user/password`）只有 Chatz 有，所以入口挂在
 * Chatz 服务卡上 —— 这些字段属于**某个服务器上的那个账号**，多服务器下不能笼统地"改"。
 *
 * 昵称同时是消息卡片和通知里显示的发送者名字，改完对**新消息**生效；
 * 已发出的消息带的是当时的快照，不会回溯（见 utils.MessageSender 的说明）。
 *
 * ── 布局：三段标签页（昵称头像 / 账号 / 密码）──────────────────
 * 五段内容平铺会让弹窗长得要滚很久，所以拆成三个标签。
 * 弹窗高度用 [AccountTab] 的内容高度**固定**（`heightIn(min = ...)`），
 * 切标签不会让弹窗忽高忽低。
 *
 * ── 保存：底部一个统一按钮 ──────────────────────────────
 * 点保存时**先本地校验全部三项**，任何一项不合格就自动跳到那个标签并高亮输入框
 * （只报第一个错误，避免用户一次面对三条红字不知从何下手）；
 * 全部通过后才**逐项提交**（四个接口相互独立，没有事务可言）。
 *
 * ⚠️ 提交是"部分成功"语义：某项成功就立刻落地（比如用户名已改），
 *    后面的项失败不影响已经成功的。最终提示会说明成功几项、剩下哪项失败。
 */
@Composable
fun AccountProfileDialog(
    serverUrl: String,
    token: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(serverUrl, token) { ChatzApi.getInstance(serverUrl, token) }

    var loading by remember { mutableStateOf(true) }
    var me by remember { mutableStateOf<ChatzMe?>(null) }
    var avatarPath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AccountTab.Profile) }

    // ===== 三个标签各自的草稿（初值来自服务端，保存时只提交"有改动"的项）=====
    var name by remember { mutableStateOf("") }
    var usernameDraft by remember { mutableStateOf("") }
    var emailDraft by remember { mutableStateOf("") }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var revokeOthers by remember { mutableStateOf(false) }
    var pwdVisible by remember { mutableStateOf(false) }

    // ===== 待提交的头像改动（与昵称/用户名/邮箱一样，点「保存」才生效）=====
    //
    // 反直觉的坑：以前选完图就立刻上传，不点保存也已经生效了，
    // 但同一个弹窗里昵称/用户名/邮箱都要点保存 —— 同一个弹窗两套语义必然让人困惑。
    // 现在选图只落到本地草稿，真正的 upload/delete 推迟到 submitAll()。
    //
    // pendingAvatar 三态（关键：要能区分「没动」和「清空」）
    //   null              → 没动过头像，什么都不做
    //   ByteArray         → 用户选了新图，待上传
    //   AVATAR_CLEARED    → 用户点了「移除」，待删除（不能用 null 表示，见上）
    var pendingAvatar by remember { mutableStateOf<ByteArray?>(null) }
    var pendingAvatarMime by remember { mutableStateOf("image/jpeg") }
    var avatarCleared by remember { mutableStateOf(false) }
    // 选图后本地立刻预览（`remember` 里按内容做 Bitmap → ImageBitmap），不必等上传
    var pendingAvatarPreview by remember { mutableStateOf<ImageBitmap?>(null) }

    // ===== 各字段的错误态：由保存前的统一校验写入，用来高亮输入框 =====
    var nameError by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf(false) }
    var pwdError by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf(false) }

    // ===== 登录设备（第四个标签）=====
    // 原来是个独立弹窗（DeviceManagerDialog），2026-10-02 合并进这里。
    // 列表**懒加载**：只有第一次切到「登录设备」才去拉，别在开弹窗时就多发一次请求。
    var devices by remember { mutableStateOf<List<ChatzDevice>?>(null) }
    var deviceBusy by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf("") }
    /** 新建成功后一次性显示的 token（服务端只在创建时返回） */
    var createdDeviceToken by remember { mutableStateOf<String?>(null) }

    // 服务端当前的头像文件名。和 `avatarPath` 分开保存：
    // `avatarPath` 是「界面正在显示的那个」（选完图会立刻变成待上传的新图，
    // 用于预览），`savedAvatarPath` 是「服务端真有的那个」——
    // 只有它变化才说明要对服务端发 upload/delete 请求。
    var savedAvatarPath by remember { mutableStateOf<String?>(null) }

    /** 从服务端重读账号信息，覆盖三个标签的草稿 */
    suspend fun reloadFromServer() {
        val loaded = withContext(Dispatchers.IO) { api.getMe() }
        if (loaded == null) {
            message = "读取账号失败，请检查网络"
            messageError = true
            return
        }
        me = loaded
        name = loaded.displayName.orEmpty().ifBlank { loaded.username }
        avatarPath = loaded.avatar
        savedAvatarPath = loaded.avatar
        usernameDraft = loaded.username
        emailDraft = loaded.email.orEmpty()
        // 服务端现在就是最新的 ⇒ 之前排队的头像改动已经没有必要（多半是别处改过了）
        pendingAvatar = null
        pendingAvatarPreview = null
        avatarCleared = false
        loading = false
    }

    LaunchedEffect(serverUrl, token) { reloadFromServer() }

    // 设备列表：第一次切到「登录设备」才加载（懒加载，见上面的说明）
    LaunchedEffect(tab) {
        if (tab == AccountTab.Device && devices == null) {
            devices = withContext(Dispatchers.IO) { api.getDevices() }
        }
    }

    // 服务端推来「账号信息变了」（多半是别处改了头像）→ 重拉一次
    //
    // 只在弹窗开着时才注册监听，关掉即注销 —— 没有弹窗就没有网络请求。
    // 不覆盖用户正在输入的草稿：仅当该输入框**没有焦点**时才改它的值。
    DisposableEffect(serverUrl, token) {
        val ctx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != MessageViewModel.ACTION_USER_UPDATED) return
                scope.launch { reloadFromServer() }
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver,
            IntentFilter(MessageViewModel.ACTION_USER_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    /** 清掉所有错误态与提示（任何一次保存尝试开始时调用） */
    fun clearErrors() {
        nameError = false
        usernameError = false
        emailError = false
        pwdError = false
        avatarError = false
        message = null
        messageError = false
    }

    // 选图 → 只做本地校验 + 预览，**不上传**（真上传在点保存时，见 submitAll）
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 读图要碰磁盘，放到 IO 线程；但很轻（单张图），不置 busy、不打断用户操作
        scope.launch {
            val resolver = context.contentResolver
            // 服务端的 raw 解析器只认 image/*，所以这里必须给出 image 开头的类型
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null || bytes.isEmpty() -> {
                    message = "无法读取该图片"
                    messageError = true
                }
                // 先卡在本地，避免让用户点了保存才发现白选一张图
                bytes.size.toLong() > MAX_AVATAR_BYTES -> {
                    message = "图片太大（上限 5MB）"
                    messageError = true
                }
                else -> {
                    pendingAvatar = bytes
                    pendingAvatarMime = mime
                    avatarCleared = false
                    avatarError = false
                    pendingAvatarPreview = withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                            .getOrNull()
                    }
                    message = "已选择新头像，点「保存」生效"
                    messageError = false
                }
            }
        }
    }

    /** 同步提交：逐个跑各接口，把结果汇总成一句话 */
    suspend fun submitAll() {
        val origName = me?.displayName.orEmpty().ifBlank { me?.username.orEmpty() }
        val origUsername = me?.username.orEmpty()
        val origEmail = me?.email.orEmpty()

        val newName = name.trim()
        val newUsername = usernameDraft.trim()
        val newEmail = emailDraft.trim()

        val changedName = newName != origName
        val changedUsername = newUsername != origUsername
        val changedEmail = newEmail != origEmail
        val changedPwd = pwd1.isNotEmpty()
        // 头像：要与「服务端已有的那个」比，不能跟 `avatarPath` 比
        // （选完图 avatarPath 就已经是新预览图了，两值必然不等）
        val changedAvatar = pendingAvatar != null || avatarCleared

        if (!changedAvatar && !changedName && !changedUsername && !changedEmail && !changedPwd) {
            message = "没有需要保存的改动"
            return
        }

        val okList = mutableListOf<String>()
        var failure: String? = null

        // 头像（放在最前：它是唯一"先传文件"的项，失败越早暴露越好）
        //
        // ⚠️ 失败时**保留** pendingAvatar / avatarCleared，用户改好网络再点一次「保存」
        //    就能重试，不必重新选图（这是刻意的：只在成功分支里清这些标记）。
        if (changedAvatar && failure == null) {
            val bytes = pendingAvatar
            if (bytes != null) {
                when (val r = withContext(Dispatchers.IO) {
                    api.uploadAvatar(bytes, pendingAvatarMime)
                }) {
                    is AccountOpResult.Ok -> {
                        avatarPath = r.value
                        savedAvatarPath = r.value
                        pendingAvatar = null
                        pendingAvatarPreview = null
                        okList += "头像"
                    }
                    is AccountOpResult.Rejected -> {
                        // 服务端明确说格式/大小不行 —— 这张图重试也没用，清掉
                        failure = r.message
                        avatarError = true
                        tab = AccountTab.Profile
                        pendingAvatar = null
                        pendingAvatarPreview = null
                    }
                    is AccountOpResult.Failed -> {
                        // 网络/服务端临时故障 —— 保留待上传内容，允许原样重试
                        failure = r.message
                        avatarError = true
                        tab = AccountTab.Profile
                    }
                }
            } else {
                when (val r = withContext(Dispatchers.IO) { api.deleteAvatar() }) {
                    is AccountOpResult.Ok -> {
                        avatarPath = null
                        savedAvatarPath = null
                        avatarCleared = false
                        okList += "头像（已移除）"
                    }
                    is AccountOpResult.Rejected -> {
                        failure = r.message
                        avatarError = true
                        tab = AccountTab.Profile
                    }
                    is AccountOpResult.Failed -> {
                        // 同上：保留 avatarCleared，允许重试
                        failure = r.message
                        avatarError = true
                        tab = AccountTab.Profile
                    }
                }
            }
        }

        // 昵称
        if (changedName && failure == null) {
            val updated = withContext(Dispatchers.IO) { api.updateProfile(newName) }
            if (updated == null) {
                failure = "昵称保存失败"
            } else {
                me = updated
                name = updated.displayName.orEmpty().ifBlank { updated.username }
                okList += "昵称"
            }
        }

        // 用户名
        if (changedUsername && failure == null) {
            when (val r = withContext(Dispatchers.IO) { api.updateUsername(newUsername) }) {
                is AccountOpResult.Ok<ChatzMe> -> {
                    me = r.value
                    usernameDraft = r.value.username
                    okList += "用户名"
                }
                is AccountOpResult.Rejected -> {
                    failure = r.message
                    usernameError = true
                    tab = AccountTab.Account
                }
                is AccountOpResult.Failed -> {
                    failure = r.message
                    tab = AccountTab.Account
                }
            }
        }

        // 邮箱
        if (changedEmail && failure == null) {
            when (val r = withContext(Dispatchers.IO) { api.updateEmail(newEmail) }) {
                is AccountOpResult.Ok<ChatzMe> -> {
                    me = r.value
                    emailDraft = r.value.email.orEmpty()
                    okList += "邮箱"
                }
                is AccountOpResult.Rejected -> {
                    failure = r.message
                    emailError = true
                    tab = AccountTab.Account
                }
                is AccountOpResult.Failed -> {
                    failure = r.message
                    tab = AccountTab.Account
                }
            }
        }

        // 密码
        if (changedPwd && failure == null) {
            when (val r = withContext(Dispatchers.IO) { api.updatePassword(pwd1, revokeOthers) }) {
                is AccountOpResult.Ok<Int> -> {
                    pwd1 = ""
                    pwd2 = ""
                    val n = r.value
                    okList += when {
                        !revokeOthers -> "密码"
                        n > 0 -> "密码（已登出其它 $n 台设备）"
                        else -> "密码（没有其它已登录设备）"
                    }
                }
                is AccountOpResult.Rejected -> {
                    failure = r.message
                    pwdError = true
                    tab = AccountTab.Password
                }
                is AccountOpResult.Failed -> {
                    failure = r.message
                    tab = AccountTab.Password
                }
            }
        }

        message = when {
            failure != null && okList.isEmpty() -> failure
            failure != null -> "已保存：${okList.joinToString("、")}；失败：$failure"
            else -> "已保存：${okList.joinToString("、")}"
        }
        messageError = failure != null
    }

    /**
     * 新建设备 —— ⚠️ **即时生效**，不走下面的「保存」
     *
     * 为什么不能跟昵称/头像一样排到「保存」里：服务端只在**创建那一刻**返回 token，
     * 而且只返回这一次（之后只能看到脱敏前缀）。排到"保存"里用户就得先猜到要去哪儿找它。
     * 所以设备的新建 / 吊销都做成标签内的独立按钮，点一下立刻生效并当场把 token 显示出来。
     */
    fun createDevice() {
        scope.launch {
            deviceBusy = true
            createdDeviceToken = null
            val created = withContext(Dispatchers.IO) {
                api.createDevice(newDeviceName.trim().ifBlank { "New Device" })
            }
            if (created == null) {
                message = "创建设备失败，请检查网络"
                messageError = true
            } else {
                createdDeviceToken = created.token
                newDeviceName = ""
                devices = withContext(Dispatchers.IO) { api.getDevices() }
                message = "已创建设备"
                messageError = false
            }
            deviceBusy = false
        }
    }

    /** 吊销设备 —— 同样**即时生效**（见 [createDevice] 的说明） */
    fun revokeDevice(device: ChatzDevice) {
        scope.launch {
            deviceBusy = true
            val ok = withContext(Dispatchers.IO) { api.deleteDevice(device.id) }
            deviceBusy = false
            if (ok) {
                devices = devices?.filterNot { it.id == device.id }
                message = "已吊销"
                messageError = false
            } else {
                message = "吊销失败"
                messageError = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个人信息") },
        text = {
            // ⚠️ 内容结构从第一帧就**必须完整存在**，绝不能写 `if (loading) return@Column`。
            //    AlertDialog 进场自带缩放/淡入动画，如果那一刻内容是"一行读取中"，
            //    数据回来时突然撑成整屏，动画就作用在这个剧变的尺寸上 ——
            //    表现就是用户报的「从右下角往中间弹」的鬼畜抖动。
            //    同一条规矩在「管理频道」弹窗（ui.channel.ChannelManageDialog）和
            //    「发现频道」弹窗（ui.channel.ChannelDiscoverDialog）上都踩过：
            //    前者第一帧只有一行「读取中…」、后者列表区是个 140~420dp 的**范围高度**，
            //    结果都是弹窗尺寸剧变 ⇒ 动画抽搐 / 漂移。现在两处都已按这套修法钉死。
            Column {
                // ===== 当前账号（常驻，不随标签切换）=====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarPreview(
                        url = api.absoluteUrl(avatarPath),
                        fallback = name.ifBlank { me?.username.orEmpty() },
                        localPreview = pendingAvatarPreview
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (loading) "…" else "@" + me?.username.orEmpty(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 徽标文案统一走 utils.roleLabel（isSuper 优先）——
                        // 服务端拆成两级角色后，isAdmin 已经不等于"全知"了
                        roleLabel(me?.isAdmin == true, me?.isSuper == true)?.let { label ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
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
                }

                Spacer(Modifier.height(14.dp))

                // ===== 标签栏 =====
                // 与「管理频道」弹窗共用同一份实现（ui.common.DialogTabBar）——
                // 各写一份的话，以后改样式必然只改一处、另一处静默漂移。
                DialogTabBar(
                    labels = AccountTab.values().map { it.label },
                    selectedIndex = tab.ordinal,
                    // 有未保存错误的标签打个点，切走了也看得见
                    errorIndexes = buildSet {
                        if (nameError || avatarError) add(AccountTab.Profile.ordinal)
                        if (usernameError || emailError) add(AccountTab.Account.ordinal)
                        if (pwdError) add(AccountTab.Password.ordinal)
                    },
                    onSelect = { index -> tab = AccountTab.values()[index] }
                )

                Spacer(Modifier.height(14.dp))

                // 固定高度：切标签时弹窗不跳动；内容超了内部滚
                Box(modifier = Modifier.heightIn(min = 236.dp, max = 236.dp)) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (tab) {
                            AccountTab.Profile -> ProfileTabContent(
                                name = name,
                                nameError = nameError,
                                avatarPath = avatarPath,
                                // 选了新图就显示本地预览，否则显示服务端那张
                                localPreview = pendingAvatarPreview,
                                avatarUrl = api.absoluteUrl(avatarPath),
                                busy = busy,
                                onNameChange = { name = it; nameError = false },
                                onPickAvatar = { avatarPicker.launch(arrayOf("image/*")) },
                                onRemoveAvatar = {
                                    // 只标记待删除，真正调接口在 submitAll()
                                    pendingAvatar = null
                                    pendingAvatarPreview = null
                                    avatarCleared = true
                                    avatarPath = null
                                    avatarError = false
                                    message = "已标记移除头像，点「保存」生效"
                                    messageError = false
                                }
                            )

                            AccountTab.Account -> AccountTabContent(
                                username = usernameDraft,
                                usernameError = usernameError,
                                email = emailDraft,
                                emailError = emailError,
                                onUsernameChange = { usernameDraft = it; usernameError = false },
                                onEmailChange = { emailDraft = it; emailError = false }
                            )

                            AccountTab.Password -> PasswordTabContent(
                                pwd1 = pwd1,
                                pwd2 = pwd2,
                                pwdError = pwdError,
                                visible = pwdVisible,
                                revokeOthers = revokeOthers,
                                onPwd1Change = { pwd1 = it; pwdError = false },
                                onPwd2Change = { pwd2 = it; pwdError = false },
                                onToggleVisible = { pwdVisible = !pwdVisible },
                                onRevokeChange = { revokeOthers = it }
                            )

                            AccountTab.Device -> DeviceTabContent(
                                devices = devices,
                                busy = deviceBusy,
                                newName = newDeviceName,
                                createdToken = createdDeviceToken,
                                onNewNameChange = { newDeviceName = it },
                                onCreate = { createDevice() },
                                onRevoke = { revokeDevice(it) },
                                onCopyToken = { tk ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("token", tk))
                                    Toast.makeText(context, "已复制 token", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                // ===== 汇总提示 =====
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
                enabled = !loading && !busy,
                onClick = {
                    clearErrors()

                    // ── 全部校验通过才提交 ──
                    // 只报「第一个」错误：一次给三条红字，用户反而不知道先改哪个。
                    val newName = name.trim()
                    val newUsername = usernameDraft.trim()
                    val newEmail = emailDraft.trim()
                    val origName = me?.displayName.orEmpty().ifBlank { me?.username.orEmpty() }
                    val origUsername = me?.username.orEmpty()
                    val origEmail = me?.email.orEmpty()

                    val changedName = newName != origName
                    val changedUsername = newUsername != origUsername
                    val changedEmail = newEmail != origEmail
                    val changedPwd = pwd1.isNotEmpty()

                    val err: String? = when {
                        // 只填了「再输一次」却没填新密码 → 容易以为改过了，必须提醒
                        !changedPwd && pwd2.isNotEmpty() -> {
                            pwdError = true; tab = AccountTab.Password; "请输入新密码"
                        }
                        changedName && newName.isEmpty() -> {
                            nameError = true; tab = AccountTab.Profile; "昵称不能为空"
                        }
                        changedName && newName.length > 32 -> {
                            nameError = true; tab = AccountTab.Profile; "昵称最长 32 个字符"
                        }
                        changedUsername && newUsername.length < 2 -> {
                            usernameError = true; tab = AccountTab.Account; "用户名至少 2 个字符"
                        }
                        changedUsername && newUsername.length > 32 -> {
                            usernameError = true; tab = AccountTab.Account; "用户名最长 32 个字符"
                        }
                        changedUsername && newUsername.any { !(it.isLetterOrDigit() || it in "_-.") } -> {
                            usernameError = true; tab = AccountTab.Account
                            "用户名只能用字母、数字和 _ - ."
                        }
                        changedEmail && !isPlausibleEmail(newEmail) -> {
                            emailError = true; tab = AccountTab.Account; "邮箱格式看起来不正确"
                        }
                        changedPwd && pwd1.length < 6 -> {
                            pwdError = true; tab = AccountTab.Password; "密码至少 6 位"
                        }
                        changedPwd && pwd1.length > 128 -> {
                            pwdError = true; tab = AccountTab.Password; "密码最长 128 位"
                        }
                        changedPwd && pwd1 != pwd2 -> {
                            pwdError = true; tab = AccountTab.Password; "两次输入的密码不一致"
                        }
                        else -> null
                    }

                    if (err != null) {
                        message = err
                        messageError = true
                        return@TextButton
                    }

                    scope.launch {
                        busy = true
                        submitAll()
                        busy = false
                    }
                }
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 个人信息弹窗的三个标签 */
private enum class AccountTab(val label: String) {
    // ⚠️ 文案刻意用两个字：这一排有四个标签，均分下来每个只有 ~70dp，
    //    四个汉字配 TextButton 的内边距刚好截断（2026-10-02 实机踩过）。
    //    短标签 + DialogTabBar 里收窄的 contentPadding，两边都有余量。
    Profile("头像"),
    Account("账号"),
    Password("密码"),
    Device("设备")
}

/** 标签一：头像 + 昵称 */
@Composable
private fun ProfileTabContent(
    name: String,
    nameError: Boolean,
    avatarPath: String?,
    // 刚选中、还没上传的本地图片；非 null 时优先于 avatarUrl 显示（即时预览）
    localPreview: ImageBitmap?,
    avatarUrl: String?,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("昵称") },
        singleLine = true,
        isError = nameError,
        supportingText = { Text("最长 32 个字符，可随意重名") }
    )

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onPickAvatar, enabled = !busy) {
            Text(if (avatarPath == null && localPreview == null) "设置头像" else "更换头像")
        }
        if (avatarPath != null || localPreview != null) {
            TextButton(onClick = onRemoveAvatar, enabled = !busy) { Text("移除头像") }
        }
    }
    Spacer(Modifier.height(8.dp))
    AvatarPreview(url = avatarUrl, fallback = name, localPreview = localPreview)
}

/** 标签二：用户名 + 邮箱 */
@Composable
private fun AccountTabContent(
    username: String,
    usernameError: Boolean,
    email: String,
    emailError: Boolean,
    onUsernameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("登录用户名") },
        singleLine = true,
        isError = usernameError,
        supportingText = { Text("2-32 位，仅字母、数字和 _ - .，全站唯一") }
    )

    Spacer(Modifier.height(10.dp))

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("邮箱（选填）") },
        singleLine = true,
        isError = emailError,
        supportingText = { Text("用于「忘记密码」；留空则清除，全站唯一") }
    )
}

/** 标签三：改密码 */
@Composable
private fun PasswordTabContent(
    pwd1: String,
    pwd2: String,
    pwdError: Boolean,
    visible: Boolean,
    revokeOthers: Boolean,
    onPwd1Change: (String) -> Unit,
    onPwd2Change: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onRevokeChange: (Boolean) -> Unit
) {
    val transform = if (visible) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    OutlinedTextField(
        value = pwd1,
        onValueChange = onPwd1Change,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("新密码") },
        singleLine = true,
        isError = pwdError,
        visualTransformation = transform,
        trailingIcon = {
            TextButton(onClick = onToggleVisible) {
                Text(if (visible) "隐藏" else "显示", fontSize = 12.sp)
            }
        },
        supportingText = { Text("6-128 位；不需要输入旧密码") }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = pwd2,
        onValueChange = onPwd2Change,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("再输一次") },
        singleLine = true,
        isError = pwdError,
        visualTransformation = transform
    )

    Spacer(Modifier.height(4.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = revokeOthers, onCheckedChange = onRevokeChange)
        Spacer(Modifier.width(4.dp))
        Text("同时登出其它设备", fontSize = 13.sp)
    }
    Text(
        text = if (revokeOthers) {
            "其它设备上的登录会立即失效，需要重新输入密码。当前设备不受影响。"
        } else {
            "改密码不会影响已经登录的设备。"
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/**
 * 邮箱的**粗略**格式检查
 *
 * 只挡明显的手滑（没有 @、没有点、有空格），**不做严格 RFC 校验** ——
 * 真正的判定在服务端（`users.js` 的 normalizeEmail + 格式校验），
 * 客户端卡太严反而会把合法但少见的邮箱挡在门外。
 */
private fun isPlausibleEmail(s: String): Boolean {
    if (s.isEmpty()) return true          // 空 = 清除，合法
    if (s.any { it.isWhitespace() }) return false
    val at = s.indexOf('@')
    if (at <= 0 || at != s.lastIndexOf('@')) return false
    val domain = s.substring(at + 1)
    return domain.isNotEmpty() && domain.contains('.') &&
            !domain.startsWith('.') && !domain.endsWith('.')
}

/**
 * 设备管理：看自己的 token 列表、吊销、新建
 *
 * 「吊销」对应的场景是：某个 token 泄漏了（贴到聊天里、旧手机丢了），要能把它作废。
 *
 * ⚠️ 当前这台设备的条目**不给删** —— 删掉它等于立刻把自己踢下线，
 *    要退出登录应该走登出（服务端会连带删除该设备）。
 */
/**
 * 标签四：登录设备
 *
 * 2026-10-02：从独立弹窗（原来的 `DeviceManagerDialog`）合并进「个人信息」。
 *
 * ⚠️ 这一段的**提交语义和另外三个标签不一样**，是刻意的：
 *   昵称 / 头像 / 用户名 / 邮箱 / 密码都是「排草稿 → 点底部『保存』」，
 *   而设备的**新建 / 吊销必须即时生效** —— 服务端只在创建那一刻返回 token，
 *   而且只返回这一次（之后列表里只有脱敏前缀）。要是也排进「保存」，
 *   用户点完保存根本不知道去哪儿找那串 token。
 *   所以这里用标签内的独立按钮，并在顶部把「即时生效」写明。
 */
@Composable
private fun DeviceTabContent(
    devices: List<ChatzDevice>?,
    busy: Boolean,
    newName: String,
    createdToken: String?,
    onNewNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onRevoke: (ChatzDevice) -> Unit,
    onCopyToken: (String) -> Unit
) {
    Text(
        text = "这里的按钮即时生效，不走底部的「保存」",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Spacer(Modifier.height(8.dp))

    when {
        devices == null -> Text("读取中…", fontSize = 13.sp)
        devices.isEmpty() -> Text("没有设备", fontSize = 13.sp)
        else -> devices.forEach { device ->
            DeviceRow(device = device, busy = busy, onDelete = { onRevoke(device) })
        }
    }

    createdToken?.let { tk ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = "新 token（只显示这一次，请立刻保存）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tk,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onCopyToken(tk) }) { Text("复制") }
        }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = newName,
        onValueChange = onNewNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("新设备名称") },
        singleLine = true,
        enabled = !busy
    )

    Spacer(Modifier.height(4.dp))

    TextButton(onClick = onCreate, enabled = !busy) { Text("新建设备") }
}

@Composable
private fun DeviceRow(
    device: ChatzDevice,
    busy: Boolean,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name.orEmpty().ifBlank { "未命名设备" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (device.isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "当前设备",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "创建于 " + formatDate(device.createdAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        TextButton(onClick = onDelete, enabled = !busy && !device.isCurrent) {
            Text(if (device.isCurrent) "不可吊销" else "吊销")
        }
    }
}

/** 头像预览：有地址就加载，否则显示首字母（和频道/应用的首字母头像同一套语义） */
/**
 * 圆形头像预览
 *
 * [localPreview] 非 null 时**直接用它**，不走网络 —— 用于"刚选中还没上传"的即时预览
 * （此时服务端还没有这张图，`url` 只指向旧图或为 null）。
 */
@Composable
private fun AvatarPreview(url: String?, fallback: String, localPreview: ImageBitmap? = null) {
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
        val display = localPreview ?: bitmap?.asImageBitmap()
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

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

private fun formatDate(ms: Long): String =
    if (ms <= 0) "未知" else DATE_FMT.format(Date(ms))
