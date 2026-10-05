package asia.guojuice.yezigotify.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import asia.guojuice.yezigotify.AppLog
import asia.guojuice.yezigotify.utils.BubbleHelper
import asia.guojuice.yezigotify.utils.ServerDisplay

private enum class SettingsPage { MAIN, SERVER, THEME }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDnd: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gotify", Context.MODE_PRIVATE) }

    var currentPage by remember { mutableStateOf(SettingsPage.MAIN) }

    BackHandler(enabled = currentPage != SettingsPage.MAIN) {
        currentPage = SettingsPage.MAIN
    }

    when (currentPage) {
        SettingsPage.MAIN -> MainPage(
            prefs = prefs,
            onOpenServer = { currentPage = SettingsPage.SERVER },
            onOpenTheme = { currentPage = SettingsPage.THEME },
            onOpenDnd = onOpenDnd,
            onBack = onBack
        )
        SettingsPage.SERVER -> ServerSettingsScreen(
            prefs = prefs,
            onBack = { currentPage = SettingsPage.MAIN }
        )
        SettingsPage.THEME -> ThemeSettingsScreen(
            prefs = prefs,
            onBack = { currentPage = SettingsPage.MAIN }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainPage(
    prefs: SharedPreferences,
    onOpenServer: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDnd: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val bubbleHelper = remember {
        val activity = context as? Activity
        if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
            BubbleHelper(activity, lifecycleOwner.lifecycleScope)
        } else null
    }

    var serverSubtitle by remember {
        mutableStateOf(ServerDisplay.getServerDisplayText(context, " + ", "未配置"))
    }
    var imageBg by remember { mutableStateOf(prefs.getBoolean("image_as_background", false)) }
    var devMode by remember { mutableStateOf(prefs.getBoolean("developer_mode", false)) }
    var mergeNotif by remember { mutableStateOf(prefs.getBoolean("notification_merge", false)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(
                title = "服务器设置",
                subtitle = serverSubtitle,
                onClick = onOpenServer
            )

            SettingCard(
                title = "主题设置",
                subtitle = "自定义主题色或跟随壁纸",
                onClick = onOpenTheme
            )

            SettingCard(
                title = "勿扰设置",
                subtitle = "配置免打扰时段和优先级豁免",
                onClick = onOpenDnd
            )

            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = "图片作为背景",
                subtitle = "消息有图片时，图片半透明显示在卡片背景",
                checked = imageBg,
                onCheckedChange = { checked ->
                    imageBg = checked
                    prefs.edit().putBoolean("image_as_background", checked).apply()
                    bubbleHelper?.show(if (checked) "已开启图片置底" else "已关闭图片置底")
                }
            )

            SwitchRow(
                title = "合并通知",
                subtitle = "同一应用的消息合并到一条通知",
                checked = mergeNotif,
                onCheckedChange = { checked ->
                    mergeNotif = checked
                    prefs.edit().putBoolean("notification_merge", checked).apply()
                    bubbleHelper?.show(if (checked) "已开启合并通知" else "已关闭合并通知")
                }
            )

            SwitchRow(
                title = "开发者模式",
                subtitle = "输出调试日志到 Logcat",
                checked = devMode,
                onCheckedChange = { checked ->
                    devMode = checked
                    prefs.edit().putBoolean("developer_mode", checked).apply()
                    AppLog.refresh(context)
                    bubbleHelper?.show(if (checked) "adb 日志已开启" else "已关闭 adb 日志")
                }
            )

            SettingCard(
                title = "数据保留策略",
                subtitle = "每台服务器本地最多保留的消息条数（多台各算各的）",
                trailing = {
                    RetentionDropdown(
                        prefs = prefs,
                        modifier = Modifier.fillMaxWidth(),
                        onSelected = { label -> bubbleHelper?.show("已设为$label") }
                    )
                }
            )

            SettingCard(
                title = "自动全量同步",
                subtitle = "定期与服务器比对完整消息列表",
                trailing = {
                    AutoSyncDropdown(
                        prefs = prefs,
                        modifier = Modifier.fillMaxWidth(),
                        onSelected = { msg -> bubbleHelper?.show(msg) }
                    )
                }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val cardModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = onCard
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onCardSecondary
            )
            trailing?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = onCard
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onCardSecondary
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = settingsSwitchColors()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionDropdown(
    prefs: SharedPreferences,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit = {}
) {
    val options = listOf("500条", "1000条", "2000条", "5000条", "无限制")
    val values = listOf(500, 1000, 2000, 5000, -1)
    var idx by remember {
        mutableStateOf(values.indexOf(prefs.getInt("db_retention_limit", -1)).coerceAtLeast(0))
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options[idx],
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = settingsTextFieldColors(),
            shape = settingsTextFieldShape(),    // 
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            //  与输入框圆角统一
            shape = RoundedCornerShape(16.dp)
        ) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        idx = i
                        prefs.edit().putInt("db_retention_limit", values[i]).apply()
                        expanded = false
                        onSelected(opt)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoSyncDropdown(
    prefs: SharedPreferences,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit = {}
) {
    val options = listOf("从不", "1 天", "3 天", "7 天", "14 天", "30 天")
    val values = listOf(0, 1, 3, 7, 14, 30)
    var idx by remember {
        val current = prefs.getString("auto_sync_days", "7")?.toIntOrNull() ?: 7
        mutableStateOf(values.indexOf(current).coerceAtLeast(0))
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options[idx],
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = settingsTextFieldColors(),
            shape = settingsTextFieldShape(),    // 
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            //  与输入框圆角统一
            shape = RoundedCornerShape(16.dp)
        ) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        idx = i
                        prefs.edit().putString("auto_sync_days", values[i].toString()).apply()
                        if (values[i] > 0) {
                            prefs.edit().putLong("last_full_sync_time", 0L).apply()
                        }
                        expanded = false
                        val msg = if (values[i] == 0) "已关闭自动全量同步" else "已设为每 $opt 自动全量同步"
                        onSelected(msg)
                    }
                )
            }
        }
    }
}