package asia.guojuice.yezigotify.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.guojuice.yezigotify.R
import asia.guojuice.yezigotify.utils.AppThemeState
import asia.guojuice.yezigotify.utils.ThemeColorManager

/**
 * Compose 版顶部栏
 *
 * 非展开态：[汉堡] ... [搜索(居中)] ... [主题切换]
 * 展开态：  [返回] [搜索输入框] [清空]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanding: Boolean,
    onSearchExpandingChange: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeColorInt = ThemeColorManager.getEffectiveColor()
    val themeColor = Color(themeColorInt)
    val onThemeColor = Color(ThemeColorManager.contrastTextColor(themeColorInt))

    val isDark = if (AppThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AppThemeState.darkMode.value
    }
    val showThemeToggle = !AppThemeState.followSystem.value

    var searchText by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery != searchText) searchText = searchQuery
    }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(isSearchExpanding) {
        if (isSearchExpanding) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    //  键盘收起时自动关闭搜索展开态
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible && isSearchExpanding) {
            // 键盘从显示 → 隐藏 → 关闭搜索
            onSearchExpandingChange(false)
            searchText = ""
            onSearchQueryChange("")
        }
        wasImeVisible = imeVisible
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(themeColor)
    ) {
        // ===== 非展开态：Box + 3 个 align 实现精确居中 =====
        AnimatedVisibility(
            visible = !isSearchExpanding,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 左：汉堡
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "打开抽屉",
                        tint = onThemeColor
                    )
                }

                // 中：搜索（精确居中）
                IconButton(
                    onClick = { onSearchExpandingChange(true) },
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = onThemeColor
                    )
                }

                // 右：主题切换
                if (showThemeToggle) {
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isDark) R.drawable.ic_sun else R.drawable.ic_moon
                            ),
                            contentDescription = "切换主题",
                            tint = onThemeColor
                        )
                    }
                }
            }
        }

        // ===== 展开态 =====
        AnimatedVisibility(
            visible = isSearchExpanding,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onSearchExpandingChange(false)
                    searchText = ""
                    onSearchQueryChange("")
                    keyboard?.hide()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "取消搜索",
                        tint = onThemeColor
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(onThemeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchText.isEmpty()) {
                        Text(
                            text = "搜索消息",
                            color = onThemeColor.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = searchText,
                        onValueChange = {
                            searchText = it
                            onSearchQueryChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = onThemeColor,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(onThemeColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                onSearchQueryChange(searchText.trim())
                            }
                        )
                    )
                }

                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        searchText = ""
                        onSearchQueryChange("")
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空",
                            tint = onThemeColor
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}
