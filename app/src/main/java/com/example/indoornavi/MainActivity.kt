package com.example.indoornavi

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.isSystemInDarkTheme // 确保顶部加了这个 import
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch

@SuppressLint("InlinedApi")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
            ), 1
        )
        setContent { MainAppRouter() }
    }
}

@Composable
fun MainAppRouter() {
    val sharedViewModel: SharedViewModel = viewModel()
    val context = LocalContext.current

    // 🌟 1. 拦截深色模式判断逻辑
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (sharedViewModel.darkModeConfig) {
        DarkModeConfig.SYSTEM -> isSystemDark
        DarkModeConfig.LIGHT -> false
        DarkModeConfig.DARK -> true
    }

    val preset = sharedViewModel.currentThemePreset
    val isDynamic = preset == ThemePreset.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 🌟 2. 构建真正的 M3 调色板引擎
    val colorScheme = if (isDynamic) {
        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = preset.color
        if (isDarkTheme) {
            darkColorScheme(
                primary = seed,
                // 主色底板：深色模式下提取 30% 浓度作为基站卡片、Slider轨道的底色
                primaryContainer = seed.copy(alpha = 0.3f),
                onPrimaryContainer = seed.copy(alpha = 0.9f),
                // 辅助面板底色：极其微弱的主题色渲染，替代死板的纯黑/纯灰
                surfaceVariant = seed.copy(alpha = 0.1f).compositeOver(Color(0xFF202020)),
                onSurfaceVariant = Color.White.copy(alpha = 0.8f),
                background = Color(0xFF121212),
                surface = Color(0xFF121212)
            )
        } else {
            lightColorScheme(
                primary = seed,
                // 主色底板：浅色模式下提取 15% 浓度作为基站卡片、Slider轨道的底色，极其优雅
                primaryContainer = seed.copy(alpha = 0.15f),
                onPrimaryContainer = seed,
                // 辅助面板底色：8% 浓度的主题色渲染
                surfaceVariant = seed.copy(alpha = 0.08f).compositeOver(Color(0xFFF5F5F5)),
                onSurfaceVariant = Color.Black.copy(alpha = 0.8f),
                background = Color(0xFFFAFAFA),
                surface = Color(0xFFFAFAFA)
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) { MainAppScreen(sharedViewModel) }
    }
}

@Composable
fun MainAppScreen(sharedViewModel: SharedViewModel) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }

    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val topPadding = systemBarsPadding.calculateTopPadding()
    val bottomNavPadding = systemBarsPadding.calculateBottomPadding()

    val dockHeight = 64.dp
    val dockBottomMargin = 24.dp + bottomNavPadding
    val dockTotalHeight = dockHeight + dockBottomMargin

    // 🌟 1. 浮岛显示状态
    var isDockVisible by remember { mutableStateOf(true) }

    // 🌟 2. 页面切换时，强制唤醒浮岛，保证导航不迷路
    LaunchedEffect(pagerState.currentPage) {
        isDockVisible = true
    }

    // 🌟 3. 全局滑动旁路监听引擎 (核心逻辑)
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 如果用户手指向上滑动（看下面的内容），available.y 为负数 -> 浮岛沉底隐藏
                if (available.y < -5f) {
                    isDockVisible = false
                }
                // 如果用户手指向下滑动（回看上面的内容），available.y 为正数 -> 浮岛升起显现
                else if (available.y > 5f) {
                    isDockVisible = true
                }
                // 返回 Zero 代表我们不“吃掉”滑动的像素，只是做个旁观者监听，绝不影响地图和列表原本的滑动
                return Offset.Zero
            }
        }
    }

    // 🌟 4. 浮岛升降的物理弹簧计算
    val dockOffsetY by animateDpAsState(
        targetValue = if (isDockVisible) 0.dp else (dockTotalHeight + 40.dp), // 隐藏时彻底沉入屏幕外
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), // 带有 Q 弹惯性
        label = "dock_hide_anim"
    )

    // 🌟 5. 把 nestedScroll 挂载到整个 App 的最外层容器上
    Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().haze(state = hazeState).padding(top = topPadding),
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> BaseStationManagerScreen(sharedViewModel.selectedDevices, { sharedViewModel.selectedDevices = it }, dockTotalHeight)
                // 🌟 1 & 2 页直接传 ViewModel，内部自动读取全局状态
                1 -> FingerprintManagerScreen(sharedViewModel)
                2 -> PositioningTestScreen(sharedViewModel)
                3 -> SettingsScreen(sharedViewModel, dockTotalHeight)
            }
        }

        Box(
            modifier = Modifier
                .align(
                    when (sharedViewModel.dockAlignment) {
                        DockAlignment.LEFT -> Alignment.BottomStart
                        DockAlignment.CENTER -> Alignment.BottomCenter
                        DockAlignment.RIGHT -> Alignment.BottomEnd
                    }
                )
                // 🌟 6. 把计算出的物理升降 Offset 挂载到浮岛上
                .offset(y = dockOffsetY)
                .padding(bottom = dockBottomMargin, start = 16.dp, end = 16.dp)
                .fillMaxWidth(sharedViewModel.dockWidthRatio)
                .height(dockHeight)
                .clip(CircleShape)
                .hazeChild(state = hazeState, shape = CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
        ) {
            var trackWidthPx by remember { mutableFloatStateOf(0f) }
            val density = LocalDensity.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .onSizeChanged { trackWidthPx = it.width.toFloat() }
            ) {
                // 确保使用了这套圆润版统一字重的图标
                val navIcons = listOf(Icons.Rounded.List, Icons.Rounded.Edit, Icons.Rounded.LocationOn, Icons.Rounded.Settings)
                val targetIndex = pagerState.targetPage

                val animatedDropIndex by animateFloatAsState(
                    targetValue = targetIndex.toFloat(),
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow),
                    label = "water_drop_slide"
                )

                if (trackWidthPx > 0f) {
                    val trackWidthDp = with(density) { trackWidthPx.toDp() }
                    val cellWidth = trackWidthDp / navIcons.size

                    val fraction = kotlin.math.abs(animatedDropIndex - kotlin.math.round(animatedDropIndex))
                    val baseIndicatorWidth = cellWidth
                    val stretchWidth = (cellWidth * 0.8f) * fraction
                    val dynamicWidth = baseIndicatorWidth + stretchWidth
                    val indicatorHeight = 48.dp

                    val dropOffsetX = (cellWidth * animatedDropIndex) + (cellWidth / 2) - (dynamicWidth / 2)

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = dropOffsetX)
                            .size(width = dynamicWidth, height = indicatorHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    )
                }

                // ================= 这段替换掉原来的 Row =================
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    navIcons.forEachIndexed { index, icon ->
                        val isSelected = targetIndex == index

                        // 🌟 核心优化 1：抛弃生硬的弹簧，改用带有缓入缓出的平滑时长过渡 (Tween)
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 0.85f,
                            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                            label = "icon_scale"
                        )

                        // 🌟 核心优化 2：为颜色也加上 350ms 的渐变过渡！告别瞬间变色！
                        val iconColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                            label = "icon_color"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconColor, // 🌟 使用渐变颜色
                                modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                            )
                        }
                    }
                }
            }
        }
    }
}