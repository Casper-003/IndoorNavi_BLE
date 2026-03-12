package com.example.echo

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

@SuppressLint("InlinedApi")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )

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

@SuppressLint("NewApi")
@Composable
fun MainAppRouter() {
    val sharedViewModel: SharedViewModel = viewModel()
    val context = LocalContext.current

    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (sharedViewModel.darkModeConfig) {
        DarkModeConfig.SYSTEM -> isSystemDark
        DarkModeConfig.LIGHT -> false
        DarkModeConfig.DARK -> true
    }

    val preset = sharedViewModel.currentThemePreset

    val isDynamic = preset == ThemePreset.DYNAMIC && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    val colorScheme = if (isDynamic) {
        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = preset.color
        if (isDarkTheme) {
            darkColorScheme(
                primary = seed,
                primaryContainer = seed.copy(alpha = 0.3f).compositeOver(Color(0xFF121212)),
                onPrimaryContainer = seed.copy(alpha = 0.9f),
                surfaceVariant = seed.copy(alpha = 0.1f).compositeOver(Color(0xFF202020)),
                onSurfaceVariant = Color.White.copy(alpha = 0.8f),
                background = Color(0xFF121212),
                surface = Color(0xFF121212)
            )
        } else {
            lightColorScheme(
                primary = seed,
                primaryContainer = seed.copy(alpha = 0.15f).compositeOver(Color(0xFFFAFAFA)),
                onPrimaryContainer = seed,
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

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                val navIcons = listOf(Icons.Rounded.CellTower, Icons.Rounded.Fingerprint, Icons.Rounded.Explore, Icons.Rounded.Settings)
                val navLabels = listOf("基站", "指纹", "定位", "设置")

                // 🌟 核心修复 1：使用 targetPage 而不是 currentPage
                // 这样底部的图标会瞬间切换到目标页，不再出现中间按钮闪烁的“跑马灯”现象
                val selectedTabIndex = pagerState.targetPage

                navIcons.forEachIndexed { index, icon ->
                    val isSelected = selectedTabIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            coroutineScope.launch {
                                // 恢复你想要的经典横向普通滑动动画
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = { Icon(imageVector = icon, contentDescription = navLabels[index]) },
                        label = { Text(text = navLabels[index], style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false, // 如果你想允许手指在屏幕上左右滑动切换页面，可以把这里改成 true
            beyondViewportPageCount = 3 // 🌟 核心保活 2：强制将非当前页面的所有视图保存在内存中，绝不销毁任何状态！
        ) { page ->
            when (page) {
                0 -> BaseStationManagerScreen(sharedViewModel, innerPadding.calculateBottomPadding())
                1 -> FingerprintManagerScreen(sharedViewModel, innerPadding.calculateBottomPadding())
                2 -> PositioningTestScreen(sharedViewModel, innerPadding.calculateBottomPadding())
                3 -> SettingsScreen(sharedViewModel, innerPadding.calculateBottomPadding())
            }
        }
    }
}