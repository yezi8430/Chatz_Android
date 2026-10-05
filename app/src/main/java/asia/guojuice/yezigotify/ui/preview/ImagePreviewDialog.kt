package asia.guojuice.yezigotify.ui.preview

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import asia.guojuice.yezigotify.ui.message.ImageBitmapCache
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose 版图片预览
 *
 * 手势架构（两个 pointerInput 各司其职）：
 * - 手势 1（awaitEachGesture）：拖拽 + 缩放，只在真正拖拽/缩放时消费事件
 * - 手势 2（detectTapGestures）：单击 / 双击 / 长按，官方 API 处理，长按无延迟
 *
 * 缩放 = 手势缩放 × 入场缩放
 *   - scaleAnim：手势缩放（捏合、双击、1x 按钮控制）
 *   - entryScale：入场缩放（初值 1.2f，图片就绪后 spring 弹回 1f）
 *   两者独立管理，渲染时相乘，避免互相干扰
 *
 * 入场动画的触发时机（关键修复）：
 *   等 bitmap 加载完成才播放入场缩放，而不是一进页面就播
 *   - 图片秒加载（缓存命中）→ 立即弹
 *   - 图片慢加载（网络请求）→ 图片出现时才弹
 *   避免"动画跑完了图片才出现"，用户看不到 duang
 */
@Composable
fun ImagePreviewDialogContent(
    imageUrl: String,
    existingBitmap: Bitmap? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    // 手势缩放 / 位移
    val scaleAnim = remember { Animatable(1f) }
    val offsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    //  入场缩放：独立 Animatable，初值 1.2f，等 bitmap 就绪后 spring 弹回 1f
    //    与 scaleAnim 相乘渲染，不干扰捏合/双击的手势逻辑
    val entryScale = remember { Animatable(1.2f) }



    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var backgroundAlpha by remember { mutableFloatStateOf(0f) }

    val animatedBgAlpha by animateFloatAsState(
        targetValue = backgroundAlpha,
        animationSpec = tween(200),
        label = "bgAlpha"
    )
    val imageAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(250),
        label = "imageAlpha"
    )

    // 双击/按钮动画的 spring 参数
    val zoomSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val offsetSpring = spring<Offset>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // 带淡出动画的关闭
    val dismissWithAnimation: () -> Unit = {
        if (!closing) {
            closing = true
            entered = false
            backgroundAlpha = 0f
            scope.launch {
                delay(250)
                onDismiss()
            }
        }
    }

    //  背景立即变暗（跟图片是否加载无关）
    //
    //   拆出独立 LaunchedEffect(Unit) 的原因：
    //   入场缩放的触发依赖 bitmap 就绪，但背景变暗是"页面出现就该发生"的事，
    //   两者拆开才能各管各的。
    LaunchedEffect(Unit) {
        backgroundAlpha = 0.5f
    }

    //  图片就绪后：淡入 + 入场缩放
    //
    //   用 LaunchedEffect(bitmap) 而不是 LaunchedEffect(Unit)：
    //   保证动画在图片真正显示时才播，而不是页面一打开就跑
    //   （如果图片从网络加载需要几百毫秒，用 Unit 会导致动画在"空白期"跑完）
    LaunchedEffect(bitmap) {
        if (bitmap == null) return@LaunchedEffect
        entered = true
        entryScale.snapTo(1.2f)
        entryScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    // 三级缓存查找
    LaunchedEffect(imageUrl) {
        if (existingBitmap != null) {
            bitmap = existingBitmap.asImageBitmap()
            return@LaunchedEffect
        }

        val cached = ImageBitmapCache.getImage(imageUrl)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }

        val rawBitmap: Bitmap? = withContext(Dispatchers.IO) {
            try {
                Glide.with(context)
                    .asBitmap()
                    .load(imageUrl)
                    .submit()
                    .get()
            } catch (e: Exception) {
                null
            }
        }

        val imgBitmap = rawBitmap?.asImageBitmap()
        bitmap = imgBitmap

        if (imgBitmap != null) {
            ImageBitmapCache.putImage(imageUrl, imgBitmap)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = animatedBgAlpha))
            // ===== 手势 1：拖拽 + 缩放 =====
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPosition = down.position

                    //  入场动画未结束 → 立即 snap 到终点
                    //
                    //   为什么：入场期间视觉上图片是 1.15x（entryScale），
                    //   但代码里的 scaleAnim 是 1.0，导致拖动判断会走"未放大"分支
                    //   （应该是平移，实际走"下拉关闭"）。
                    //   用户按下的瞬间把 entryScale snap 到 1f，
                    //   后续所有基于 scaleAnim.value 的判断就和视觉一致了。
                    //
                    //   用 scope.launch 是因为 snapTo 是 suspend 函数；
                    //   可能晚一帧生效，但视觉上无法感知。
                    if (entryScale.value != 1f) {
                        scope.launch { entryScale.snapTo(1f) }
                    }

                    var totalDrag = Offset.Zero
                    var hadMultiTouch = false
                    var isDragging = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (pressedCount >= 2) {
                            // 多指：缩放 + 平移
                            hadMultiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scaleAnim.value * zoomChange).coerceIn(1f, 5f)

                            scope.launch { scaleAnim.snapTo(newScale) }
                            scope.launch {
                                val newOffset = if (newScale > 1.02f) {
                                    offsetAnim.value + panChange
                                } else {
                                    Offset.Zero
                                }
                                offsetAnim.snapTo(newOffset)
                            }

                            event.changes.forEach { if (it.pressed) it.consume() }
                        } else if (pressedCount == 1 && !hadMultiTouch) {
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                val currentPos = change.position
                                val movedDistance = (currentPos - downPosition).getDistance()

                                if (movedDistance > 20f) {
                                    isDragging = true
                                }

                                if (isDragging) {
                                    val dragAmount = currentPos - change.previousPosition
                                    if (scaleAnim.value > 1.02f) {
                                        // 放大状态：平移
                                        scope.launch {
                                            offsetAnim.snapTo(offsetAnim.value + dragAmount)
                                        }
                                    } else {
                                        // 未放大：下拉关闭
                                        totalDrag += dragAmount
                                        dragOffset = totalDrag
                                        backgroundAlpha = (0.5f - totalDrag.getDistance() / 1000f)
                                            .coerceIn(0.2f, 0.5f)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // 拖拽结束：判断关闭 or 回弹
                    if (!hadMultiTouch && scaleAnim.value <= 1.02f && isDragging) {
                        val threshold = 150f
                        if (totalDrag.getDistance() > threshold) {
                            dismissWithAnimation()
                        } else {
                            dragOffset = Offset.Zero
                            backgroundAlpha = 0.5f
                        }
                    }
                }
            }
            // ===== 手势 2：单击 / 双击 / 长按 =====
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        //  用 effectiveScale 判断：入场动画期间视觉上是放大的，
                        //    单击不应该关闭
                        val effectiveScale = scaleAnim.value * entryScale.value
                        if (effectiveScale <= 1.02f &&
                            dragOffset == Offset.Zero &&
                            !closing
                        ) {
                            dismissWithAnimation()
                        }
                    },
                    onDoubleTap = {
                        // 双击倍率 1.5x，判断阈值 1.1f
                        //
                        // 用 scaleAnim.value 判断（不含 entryScale）：
                        //   入场动画期间用户双击，走"放大到 1.5x"分支 —— 符合直觉
                        //   （用户此刻想放大的意图大于想缩回）
                        if (entryScale.value > 1.01f) return@detectTapGestures
                        if (scaleAnim.value > 1.1f) {
                            scope.launch { scaleAnim.animateTo(1f, zoomSpring) }
                            scope.launch { offsetAnim.animateTo(Offset.Zero, offsetSpring) }
                        } else {
                            scope.launch { scaleAnim.animateTo(1.5f, zoomSpring) }
                            scope.launch { offsetAnim.animateTo(Offset.Zero, offsetSpring) }
                        }
                    },
                    onLongPress = {
                        if (!closing) {
                            showSaveDialog = true
                        }
                    }
                )
            }
    ) {
        bitmap?.let { img ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val imgRatio = img.width.toFloat() / img.height.toFloat()
                val containerRatio = maxWidth.value / maxHeight.value

                val sizeModifier = if (imgRatio > containerRatio) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(imgRatio)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(imgRatio)
                }

                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = sizeModifier
                        .graphicsLayer(
                            alpha = imageAlpha,
                            //  手势缩放 × 入场缩放
                            scaleX = scaleAnim.value * entryScale.value,
                            scaleY = scaleAnim.value * entryScale.value,
                            translationX = offsetAnim.value.x + dragOffset.x,
                            translationY = offsetAnim.value.y + dragOffset.y,
                            shape = RoundedCornerShape(16.dp),
                            clip = true
                        )
                )
            }
        }
    }

    // 1x 按钮：只在"用户主动放大"（scaleAnim）时显示，不看 entryScale
    //    入场动画期间不显示 —— 用户没主动操作，不需要"缩回 1x"的选项
    if (scaleAnim.value > 1.02f) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            IconButton(
                onClick = {
                    scope.launch { scaleAnim.animateTo(1f, zoomSpring) }
                    scope.launch { offsetAnim.animateTo(Offset.Zero, offsetSpring) }
                },
                modifier = Modifier
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "1x",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存图片") },
            text = { Text("是否将图片保存到设备？") },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    downloadImage(context, imageUrl)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun downloadImage(context: Context, url: String) {
    try {
        val fileName = generateFileName(url)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("正在下载图片")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun generateFileName(url: String): String {
    return try {
        val uri = Uri.parse(url)
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            val base = lastSegment.substringBefore('?')
            if (base.contains('.')) base else "$base.jpg"
        } else {
            "image_${System.currentTimeMillis()}.jpg"
        }
    } catch (e: Exception) {
        "image_${System.currentTimeMillis()}.jpg"
    }
}