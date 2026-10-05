package asia.guojuice.yezigotify.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.AppDatabase
import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.GotifyApi
import asia.guojuice.yezigotify.GotifyService
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
import asia.guojuice.yezigotify.config.AuthMode
import asia.guojuice.yezigotify.config.LoginResult
import asia.guojuice.yezigotify.config.ServerConfig
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.config.ServerKind
import asia.guojuice.yezigotify.config.loginChatz
import asia.guojuice.yezigotify.connection.ConnectionState
import asia.guojuice.yezigotify.utils.ServerDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器设置（P6：动态服务卡列表）
 *
 * 形态：N 张服务卡 + 「添加服务」+（测试连接全部 / 保存）
 *
 * 顶部原先那张「Chatz 能力检测」卡已移除：能力会自动刷新，手动探测用不上；
 * 卡片底部也去掉了「测试连接 / 探测能力」，只留「删除」—— 单独测某台请取消
 * 勾选其它卡后点底部的「测试连接全部」。
 *
 * 数据流：本页维护一份 `drafts`（草稿列表），输入框改动**即时**写回草稿，
 *        只有点「保存」才 `ServerConfigStore.saveAll` 落盘并通知 Service 重载连接。
 *        删除是即刻生效的（写 store + 清该 serverId 的本地消息）。
 *
 * ⚠️ 这里不引入 LazyColumn：外层是 `Column + verticalScroll`，嵌套 LazyColumn 会
 *    触发 Compose 的无限高度约束异常。
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    prefs: SharedPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ===== 草稿列表（进入页面读一次 store；保存时整体写回）=====
    var drafts by remember { mutableStateOf(ServerConfigStore.all(context)) }

    // 每张卡的展开态；key = config.id，卡片增删/重排不会串台
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    /** 「测试连接」的结果（非 null 时弹对话框）。用对话框而不是 Toast，见下面的说明 */
    var testResults by remember { mutableStateOf<List<String>?>(null) }

    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    // 写 store 后通知 Service 差分重载（不要 stopService + startService，那会让 N 条连接全部断开重连）
    fun reloadConfigs() {
        context.startService(
            Intent(context, GotifyService::class.java)
                .setAction(GotifyService.ACTION_RELOAD_CONFIGS)
        )
    }

    // ===== 添加服务 =====
    fun addServer(kind: ServerKind) {
        val slot = runCatching { ServerConfigStore.allocateSlot(context) }.getOrElse {
            Toast.makeText(
                context,
                "服务卡数量已达上限（${ServerConfigStore.MAX_SLOT} 张）",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val cfg = ServerConfig(slot = slot, kind = kind)
        drafts = drafts + cfg
        expandedStates[cfg.id] = true        // 新卡自动展开
    }

    // ===== 删除一张卡：清该 serverId 的本地数据 + 通知重载 =====
    fun deleteServer(cfg: ServerConfig) {
        scope.launch {
            ServerConfigStore.remove(context, cfg.id)
            withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.getDatabase(context).messageDao().deleteByServer(cfg.id)
                }.onFailure { AppLog.e("ServerSettings", "清理本地消息失败: ${it.message}") }
            }
            drafts = drafts.filterNot { it.id == cfg.id }
            expandedStates.remove(cfg.id)
            Toast.makeText(context, "已删除「${ServerDisplay.labelOf(cfg)}」", Toast.LENGTH_SHORT).show()
            reloadConfigs()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 动态服务卡列表 =====
            if (drafts.isEmpty()) {
                Text(
                    text = "尚未添加服务，点击下方「添加服务」新建一张卡片",
                    style = MaterialTheme.typography.bodySmall,
                    color = onCardSecondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            drafts.forEach { config ->
                key(config.id) {
                    ServerCard(
                        config = config,
                        connectionState = GotifyService.serverStates[config.id],
                        expanded = expandedStates[config.id] == true,
                        onToggleExpand = {
                            expandedStates[config.id] = expandedStates[config.id] != true
                        },
                        onUpdate = { updated ->
                            drafts = drafts.map { if (it.id == updated.id) updated else it }
                        },
                        onDelete = { pendingDelete = config }
                    )
                }
            }

            // ===== 添加服务 =====
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加服务") }

            // ===== 操作按钮 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            // ⚠️ 只测**已启用**的卡。
                            //    关掉开关 = 用户明确表示不用这台，还去测它只是噪音
                            //    （2026-10-03 用户反馈：关掉的卡也在被测）。
                            val targets = drafts.filter {
                                it.enabled &&
                                        it.url.isNotBlank() &&
                                        (it.token.isNotBlank() || it.authMode == AuthMode.USER_PASSWORD)
                            }
                            if (targets.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    "没有可测试的服务：卡片开关关掉的、或地址/凭据没填的都会被跳过",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            val results = targets.map { cfg ->
                                val ok = testServerConfig(cfg)
                                "${ServerDisplay.labelOf(cfg)}: ${if (ok) "成功" else "失败"}"
                            }
                            // 交给对话框显示：Toast 两行就截断，多台时后面的结果会看不到
                            testResults = results
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("测试连接") }

                Button(
                    onClick = {
                        scope.launch {
                            val deviceName = android.os.Build.MODEL ?: "Android"

                            // 1) Gotify 卡的认证方式恒为 Token。
                            //    卡片上已经不提供"用户名密码"了（官方没有 /auth/login），
                            //    这里再兜一次：存量配置里若存了别的值，保存时顺手纠正。
                            val normalized = drafts.map { cfg ->
                                if (cfg.kind == ServerKind.GOTIFY) {
                                    cfg.copy(authMode = AuthMode.TOKEN)
                                } else {
                                    cfg
                                }
                            }

                            // 2) Chatz + 用户名密码 → 调 /auth/login 换设备 Token（不落盘明文密码）
                            val resolved = mutableListOf<ServerConfig>()
                            for (cfg in normalized) {
                                val needLogin = cfg.kind == ServerKind.CHATZ &&
                                        cfg.authMode == AuthMode.USER_PASSWORD
                                if (!needLogin) {
                                    resolved += cfg
                                    continue
                                }
                                if (cfg.url.isBlank() || cfg.username.isBlank() || cfg.password.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        "「${ServerDisplay.labelOf(cfg)}」：请填写服务器地址、用户名和密码",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                when (val r = loginChatz(cfg.url, cfg.username, cfg.password, deviceName)) {
                                    is LoginResult.Success -> {
                                        // 登录成功后 authMode 换成 TOKEN 并清空用户名/密码：
                                        // 若保留 USER_PASSWORD，hasCredentials 会因密码被清空而恒为 false，
                                        // 该卡会被判成"未配置"、永不发起连接（明文密码又不允许持久化）
                                        resolved += cfg.copy(
                                            authMode = AuthMode.TOKEN,
                                            token = r.token,
                                            username = "",
                                            password = ""
                                        )
                                    }
                                    is LoginResult.Failure -> {
                                        Toast.makeText(
                                            context,
                                            "「${ServerDisplay.labelOf(cfg)}」登录失败：${r.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }
                                }
                            }

                            // 3) S1-2：已存在的卡改了 url → 必须先清该 serverId 的本地数据。
                            //    否则新服务器远程 id 从 1 开始，与旧本地 id 完全相同，
                            //    insert 走 IGNORE → 消息被静默丢弃且无法补回。
                            var addressChanged = false
                            withContext(Dispatchers.IO) {
                                val dao = AppDatabase.getDatabase(context).messageDao()
                                for (cfg in resolved) {
                                    val old = ServerConfigStore.byId(context, cfg.id) ?: continue
                                    if (old.url.isNotBlank() && old.url.trim() != cfg.url.trim()) {
                                        runCatching { dao.deleteByServer(cfg.id) }
                                            .onFailure { AppLog.e("ServerSettings", "清理本地消息失败: ${it.message}") }
                                        addressChanged = true
                                    }
                                }
                            }

                            // 4) 落盘 + 通知 Service 差分重载
                            ServerConfigStore.saveAll(context, resolved)
                            drafts = resolved
                            reloadConfigs()

                            Toast.makeText(
                                context,
                                if (addressChanged) {
                                    "已保存；服务器地址已变更，本地该服务器的历史消息已清理"
                                } else {
                                    "已保存"
                                },
                                Toast.LENGTH_SHORT
                            ).show()

                            // 登录回填的 token 只写进了 store；返回上一页避免输入框残留旧值
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    }

    // ===== 添加服务弹窗 =====
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加服务") },
            text = {
                Column {
                    ServerKind.values().forEach { kind ->
                        Text(
                            text = ServerDisplay.kindLabel(kind),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAddDialog = false
                                    addServer(kind)
                                }
                                .padding(vertical = 14.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    // ===== 测试连接结果 =====
    //
    // ⚠️ 为什么不用 Toast（原来是 `Toast.makeText(..., results.joinToString("\n"), LENGTH_LONG)`）：
    //    Android 12 起系统把文本 Toast 标准化了，**最多显示两行** ——
    //    三台以上服务器时，第 3 条往后的结果会被直接截掉，看起来就像"某台没被测"。
    //    2026-10-03 用户报的「gotify、ntfy 有，chatz 没有」就是这个：
    //    chatz 不是没测，是排在第 3 位、被 Toast 截了。
    testResults?.let { lines ->
        AlertDialog(
            onDismissRequest = { testResults = null },
            title = { Text("测试连接结果") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { testResults = null }) { Text("关闭") }
            }
        )
    }

    // ===== 删除确认 =====
    pendingDelete?.let { cfg ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务") },
            text = { Text("确定删除「${ServerDisplay.labelOf(cfg)}」吗？该服务器的本地历史消息也会被清理。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteServer(cfg)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 单张服务卡
 *
 * 输入框状态用 `remember(config.id)`：卡片增删 / 重排时不会把 A 卡的文字串到 B 卡上。
 * 每次改动都通过 [onUpdate] 写回草稿列表，保证「保存」时拿到的是最新值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    config: ServerConfig,
    connectionState: ConnectionState?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (ServerConfig) -> Unit,
    onDelete: () -> Unit
) {
    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()
    val fieldShape = settingsTextFieldShape()

    var url by remember(config.id) { mutableStateOf(config.url) }
    var displayName by remember(config.id) { mutableStateOf(config.displayName) }
    var token by remember(config.id) { mutableStateOf(config.token) }
    var username by remember(config.id) { mutableStateOf(config.username) }
    var password by remember(config.id) { mutableStateOf(config.password) }
    var topic by remember(config.id) { mutableStateOf(config.topic) }
    var authMode by remember(config.id) { mutableStateOf(config.authMode) }
    var keepAliveIdx by remember(config.id) {
        mutableStateOf(KEEP_ALIVE_VALUES.indexOf(config.keepAliveSeconds).coerceAtLeast(0))
    }

    // Chatz 账号相关弹窗（改昵称/头像、设备管理）
    var showAccountDialog by remember { mutableStateOf(false) }

    /** 标题区点击用的空交互源：只为把水波纹关掉，不影响点击本身 */
    val plainInteraction = remember { MutableInteractionSource() }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "serverCardArrow"
    )

    val titleAlpha = if (config.enabled) 1f else 0.5f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // ===== 标题行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 标题 + 状态这两行本身就贴着自己那块矩形的边界：裁 16dp 圆角会把
                        // 左上角的字一起裁掉，留着方形水波纹又难看。所以干脆不要水波纹 ——
                        // 点它的反馈就是卡片展开/收起本身。
                        .clickable(
                            interactionSource = plainInteraction,
                            indication = null
                        ) { onToggleExpand() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ServerDisplay.labelOf(config),
                            style = MaterialTheme.typography.titleMedium,
                            color = onCard.copy(alpha = titleAlpha)
                        )
                        // ⚠️ 只在标题**没有自带类型**时才补这个徽标。
                        //    labelOf() 在 displayName 为空时已经拼成「Chatz(域名)」了，
                        //    再并排一个「Chatz」就是重复（2026-10-03 用户反馈）。
                        if (ServerDisplay.showsKindBadge(config)) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = ServerDisplay.kindLabel(config.kind),
                                style = MaterialTheme.typography.bodySmall,
                                color = onCardSecondary.copy(alpha = titleAlpha)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusText(config, connectionState),
                        style = MaterialTheme.typography.bodySmall,
                        color = onCard.copy(alpha = if (config.enabled) 0.9f else 0.5f)
                    )
                }

                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    colors = settingsSwitchColors()
                )

                // 用 IconButton 而不是 Icon + clickable：后者只是给 32dp 的图标矩形加了点击，
                // 水波纹是方的、触控区也偏小；IconButton 是 40dp 圆形涟漪 + 48dp 触控区。
                IconButton(onClick = { onToggleExpand() }) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = onCard,
                        modifier = Modifier.rotate(arrowRotation)
                    )
                }
            }

            // ===== 展开区 =====
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = {
                            displayName = it
                            onUpdate(config.copy(displayName = it))
                        },
                        label = { Text("显示名（可选）") },
                        placeholder = { Text(ServerDisplay.kindLabel(config.kind) + "(域名)") },
                        colors = settingsTextFieldColors(),
                        shape = fieldShape,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            onUpdate(config.copy(url = it))
                        },
                        label = { Text("服务器地址") },
                        placeholder = { Text(urlPlaceholder(config.kind)) },
                        colors = settingsTextFieldColors(),
                        shape = fieldShape,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (config.isNtfy) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = topic,
                            onValueChange = {
                                topic = it
                                onUpdate(config.copy(topic = it))
                            },
                            label = { Text("订阅主题") },
                            colors = settingsTextFieldColors(),
                            shape = fieldShape,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    // Gotify 官方没有账号登录接口（/auth/login 是 Chatz 的扩展），
                    // 所以这张卡**不提供**"用户名密码"，只留 Token，连下拉都不显示。
                    if (config.kind == ServerKind.GOTIFY) {
                        Text(
                            text = "认证方式：Token",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onCard
                        )
                    } else {
                        Text(
                            "认证方式",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onCard
                        )
                        Spacer(Modifier.height(4.dp))
                        SimpleDropdown(
                            options = AUTH_OPTIONS,
                            selectedIndex = if (authMode == AuthMode.TOKEN) 0 else 1,
                            onSelected = { idx ->
                                val newMode = if (idx == 0) AuthMode.TOKEN else AuthMode.USER_PASSWORD
                                authMode = newMode
                                onUpdate(config.copy(authMode = newMode))
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    // 有效认证方式：Gotify 恒为 Token（即使存量配置里存了别的值）
                    val effectiveAuthMode =
                        if (config.kind == ServerKind.GOTIFY) AuthMode.TOKEN else authMode
                    if (effectiveAuthMode == AuthMode.TOKEN) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                onUpdate(config.copy(token = it))
                            },
                            label = { Text("Token") },
                            colors = settingsTextFieldColors(),
                            shape = fieldShape,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it
                                onUpdate(config.copy(username = it))
                            },
                            label = { Text("用户名") },
                            colors = settingsTextFieldColors(),
                            shape = fieldShape,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                onUpdate(config.copy(password = it))
                            },
                            label = { Text("密码") },
                            colors = settingsTextFieldColors(),
                            shape = fieldShape,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    // ===== 类型专属提示 =====
                    authHint(config.kind, effectiveAuthMode)?.let { hint ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = onCardSecondary
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "保活间隔",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onCard
                    )
                    Spacer(Modifier.height(4.dp))
                    SimpleDropdown(
                        options = KEEP_ALIVE_OPTIONS,
                        selectedIndex = keepAliveIdx,
                        onSelected = { idx ->
                            keepAliveIdx = idx
                            onUpdate(config.copy(keepAliveSeconds = KEEP_ALIVE_VALUES[idx]))
                        }
                    )

                    Spacer(Modifier.height(16.dp))

                    // ===== Chatz 账号入口 =====
                    //
                    // 昵称 / 设备 token 都属于"某个服务器上的那个账号"，多服务器下不能笼统地
                    // 放一个全局入口，所以挂在每张 Chatz 卡里。
                    //
                    // 判定刻意宽松：探测结果 **或** 卡片自己声明的类型，任一成立就显示。
                    // 能力探测要手动点一次才有结果，只按探测结果判会让"刚装完看不到入口"。
                    // 缺 Token 时也不藏起来，而是置灰 + 给提示，否则用户只会以为没这功能。
                    val looksChatz = ServerCapabilitiesHolder.isChatz(config.id) ||
                            config.kind == ServerKind.CHATZ
                    val hasToken = config.token.isNotBlank()
                    // ===== 卡片底部：个人信息 + 删除 同一行 =====
                    //
                    // 「设备管理」2026-10-02 起已并入「个人信息」弹窗（第四个标签「登录设备」），
                    // 不再是独立入口 —— 所以这一行只剩两个按钮。
                    // 非 Chatz 卡（Gotify / ntfy）没有个人信息，删除独占整行。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (looksChatz) {
                            Button(
                                onClick = { showAccountDialog = true },
                                enabled = hasToken,
                                modifier = Modifier.weight(1f)
                            ) { Text("个人信息") }
                        }

                        Button(
                            onClick = onDelete,
                            contentPadding = CARD_BUTTON_CONTENT_PADDING,
                            modifier = Modifier.weight(1f)
                        ) { Text("删除", maxLines = 1, softWrap = false) }
                    }

                    if (looksChatz && !hasToken) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "先用 Token 或用户名密码连接一次，才能管理账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = onCardSecondary
                        )
                    }
                }
            }
        }
    }

    // ===== Chatz 账号弹窗 =====
    // 渲染在 Card 之外：卡片收起时按钮会消失，但已打开的弹窗不该跟着没了
    if (showAccountDialog) {
        AccountProfileDialog(
            serverUrl = config.url,
            token = config.token,
            onDismiss = { showAccountDialog = false }
        )
    }
}

/** 保活间隔选项 / 取值（原设置页的取值，索引一一对应） */
private val KEEP_ALIVE_OPTIONS = listOf(
    "自动（WiFi 45/ 移动 180）",
    "20 秒", "30 秒", "45 秒", "60 秒", "120 秒", "180 秒", "300 秒"
)
private val KEEP_ALIVE_VALUES = listOf(0, 20, 30, 45, 60, 120, 180, 300)

/** 认证方式选项（三种服务统一提供 Token / 用户名密码） */
private val AUTH_OPTIONS = listOf("Token", "用户名密码")

/**
 * 服务卡里操作按钮的紧凑内边距
 *
 * Material3 的 Button 默认左右各 24dp。360dp 宽的屏上卡片里并列两个按钮时，
 * 每个约 142dp，扣掉 48dp 后文字区只剩 ~94dp —— 系统字体调到 1.5 倍，
 * 「探测能力」就要 ~84dp，再大一点就折行了。收紧到 4dp 后文字区约 134dp，
 * 放到两倍字号也还有余量。
 */
private val CARD_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 4.dp, vertical = 10.dp)

/** 地址输入框的占位提示 */
private fun urlPlaceholder(kind: ServerKind): String = when (kind) {
    ServerKind.CHATZ -> "https://chatz.example.com"
    ServerKind.GOTIFY -> "https://gotify.example.com"
    ServerKind.NTFY -> "https://ntfy.sh"
}

/**
 * 认证方式提示；返回 null 表示无需提示
 *
 * Gotify 不会走到这里：那张卡的认证方式恒为 Token（见 ServerCard 里的 effectiveAuthMode）。
 */
private fun authHint(kind: ServerKind, authMode: AuthMode): String? {
    if (authMode == AuthMode.TOKEN) return null
    return when (kind) {
        ServerKind.CHATZ -> "将调用 /auth/login 换取设备 Token，仅保存 Token（不保存密码）"
        ServerKind.NTFY -> "使用 HTTP Basic 认证，密码会保存在本地"
        else -> null
    }
}

/** 卡片的配置 / 连接状态文案 */
private fun statusText(config: ServerConfig, state: ConnectionState?): String = when {
    !config.enabled -> "已暂停"
    !config.isUsable -> "未配置"
    state == null -> "等待连接"
    else -> when (state) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.RECONNECTING -> "重连中"
        ConnectionState.DISCONNECTED -> "未连接"
        ConnectionState.PARTIAL -> "部分连接"
    }
}

/**
 * 单张卡的连接测试
 *
 * ntfy 走 NtfyClient 同款 HTTP（poll=1）；协议服务器走 GotifyApi.testConnection。
 * USER_PASSWORD 且尚未登录的 Chatz 卡没有 Token，这里直接判失败（提示用户先保存登录）。
 *
 * 只被页面底部的「测试连接」（全部）调用；卡片上已不再有单张测试按钮。
 */
private suspend fun testServerConfig(cfg: ServerConfig): Boolean {
    if (cfg.kind == ServerKind.NTFY) {
        return testNtfyConnection(
            serverUrl = cfg.url.trim(),
            topic = cfg.topic.trim(),
            authType = if (cfg.authMode == AuthMode.TOKEN) "token" else "userpass",
            token = cfg.token.trim(),
            username = cfg.username.trim(),
            password = cfg.password.trim()
        )
    }
    val url = cfg.url.trim()
    val token = cfg.token.trim()
    if (url.isBlank() || token.isBlank()) return false
    return withContext(Dispatchers.IO) {
        runCatching { GotifyApi(url, token).testConnection() }.getOrDefault(false)
    }
}

private suspend fun testNtfyConnection(
    serverUrl: String,
    topic: String,
    authType: String,
    token: String,
    username: String,
    password: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val base = serverUrl.trimEnd('/')
        val requestBuilder = okhttp3.Request.Builder()
            .url("$base/$topic/json?since=now&poll=1")
            .header("Accept", "application/x-ndjson")

        when (authType) {
            "token" -> {
                if (token.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
            }
            else -> {
                if (username.isNotBlank() && password.isNotBlank()) {
                    val credential = android.util.Base64.encodeToString(
                        "$username:$password".toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                    requestBuilder.header("Authorization", "Basic $credential")
                }
            }
        }

        ntfyTestClient.newCall(requestBuilder.build()).execute().use { response ->
            response.isSuccessful || response.code in 400..499
        }
    } catch (e: Exception) {
        AppLog.e("Settings", "ntfy 测试连接失败: ${e.message}")
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = options.getOrElse(selectedIndex) { options.first() },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = settingsTextFieldColors(),
            shape = settingsTextFieldShape(),      //
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            //  与输入框圆角统一：默认 MenuDefaults.shape 只有 4dp，看起来像直角
            shape = RoundedCornerShape(16.dp)
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 *  测试连接专用的共享 OkHttpClient
 *
 * 原实现：每次 testNtfyConnection 都 new OkHttpClient.Builder().build()，
 *   每次都会创建新的连接池、Dispatcher 线程池、DNS 缓存。
 *   用户反复点"测试连接"会产生大量短命对象。
 *
 * 新实现：文件级 lazy 单例，整个 App 生命周期只创建一个。
 *   - connectTimeout 10s：握手不能等太久
 *   - readTimeout 12s：ntfy 的 poll 请求需要等到有消息或超时，比默认 10s 长一点
 */
private val ntfyTestClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}