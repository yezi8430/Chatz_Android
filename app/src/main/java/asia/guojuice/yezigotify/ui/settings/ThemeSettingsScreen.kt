package asia.guojuice.yezigotify.ui.settings

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import asia.guojuice.yezigotify.utils.ThemeColorManager
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    prefs: SharedPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var dynamicColor by remember { mutableStateOf(ThemeColorManager.isDynamicColorEnabled()) }
    var followSystem by remember { mutableStateOf(prefs.getBoolean("follow_system_dark_mode", false)) }
    var selectedColor by remember { mutableIntStateOf(ThemeColorManager.getEffectiveColor()) }

    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    // ── 颜色选择器弹窗要用的两个值，**必须在 Composable 作用域里先取好** ──
    // `MaterialTheme.colorScheme` 和 `LocalDensity.current` 都是 @Composable 读取，
    // 不能写进 onClick 那个普通 lambda 里 —— 编译器会报
    // 「@Composable invocations can only happen from the context of a @Composable function」。
    // 所以在这里先算成普通值，onClick 里只用现成的 Int / Float。
    val pickerSurfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    val pickerRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题设置") },
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
            // 顶栏颜色预览
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "顶栏颜色预览",
                        style = MaterialTheme.typography.titleMedium,
                        color = onCard
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ComposeColor(selectedColor))
                    ) {
                        Text(
                            "示例标题",
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp),
                            color = ComposeColor(ThemeColorManager.contrastTextColor(selectedColor)),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // 动态取色（Android 12+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                                "动态取色",
                                style = MaterialTheme.typography.titleMedium,
                                color = onCard
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "从壁纸提取主题色（Android 12+）",
                                style = MaterialTheme.typography.bodySmall,
                                color = onCardSecondary
                            )
                        }
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { checked ->
                                dynamicColor = checked
                                ThemeColorManager.setDynamicColorEnabled(checked)
                                if (checked) {
                                    (context as? Activity)?.let {
                                        ThemeColorManager.updateDynamicColorFromWallpaper(it)
                                    }
                                } else {
                                    ThemeColorManager.updateColorAndNotify()
                                }
                                selectedColor = ThemeColorManager.getEffectiveColor()
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }
            }

            // 跟随系统深色模式
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
                            "跟随系统深色模式",
                            style = MaterialTheme.typography.titleMedium,
                            color = onCard
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "关闭后可在主界面手动切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = onCardSecondary
                        )
                    }
                    Switch(
                        checked = followSystem,
                        onCheckedChange = { checked ->
                            followSystem = checked
                            prefs.edit().putBoolean("follow_system_dark_mode", checked).apply()
                        },
                        colors = settingsSwitchColors()
                    )
                }
            }

            // 手动选色
            if (!dynamicColor) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "自定义颜色",
                            style = MaterialTheme.typography.titleMedium,
                            color = onCard
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ComposeColor(selectedColor))
                            )
                            Button(
                                onClick = {
                                    val activity = context as? Activity ?: return@Button
                                    val builder = ColorPickerDialog.Builder(activity)
                                        .setTitle("选择主题色")
                                        .setPreferenceName("GotifyColorPicker")
                                        .setPositiveButton("确定", ColorEnvelopeListener { envelope, _ ->
                                            selectedColor = envelope.color
                                        })
                                        .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
                                        .attachAlphaSlideBar(false)
                                        .attachBrightnessSlideBar(true)
                                        .apply { colorPickerView.setInitialColor(selectedColor) }

                                    // ── 圆角 ──────────────────────────────────────
                                    // `ColorPickerDialog extends AlertDialog`（库源码确认），
                                    // 默认窗口背景是个直角矩形，跟 App 里一水的圆角卡片不搭。
                                    // 库没给圆角参数 ⇒ 拿到 window 把背景换成圆角 drawable。
                                    // 纯代码、不新增 res 文件。
                                    // ⚠️ 颜色和半径都在 Composable 作用域里先算好了（见函数开头），
                                    //    这里不能直接读 MaterialTheme / LocalDensity —— onClick 不是
                                    //    @Composable 上下文，写了就是编译错误。
                                    val dialog = builder.create()
                                    dialog.show()
                                    dialog.window?.setBackgroundDrawable(
                                        GradientDrawable().apply {
                                            setColor(pickerSurfaceArgb)
                                            cornerRadius = pickerRadiusPx
                                        }
                                    )
                                }
                            ) { Text("选择颜色") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 保存按钮
            Button(
                onClick = {
                    if (!dynamicColor) {
                        prefs.edit().putInt("top_bar_color", selectedColor).apply()
                        ThemeColorManager.updateColorAndNotify()
                    }
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}