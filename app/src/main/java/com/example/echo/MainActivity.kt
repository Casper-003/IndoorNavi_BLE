package com.example.echo

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

@SuppressLint("InlinedApi")
class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent { MainAppRouter() }
        permissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        )
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
        // 当 DYNAMIC 在 Android < 12 时回退到 Miku Green，避免出现 Material3 默认紫色
        val seed = if (preset == ThemePreset.DYNAMIC) ThemePreset.MIKU.color else preset.color
        if (isDarkTheme) {
            val bg = Color(0xFF121212)
            val surf = Color(0xFF1E1E1E)
            darkColorScheme(
                primary = seed,
                onPrimary = Color.Black,
                primaryContainer = seed.copy(alpha = 0.3f).compositeOver(bg),
                onPrimaryContainer = seed.copy(alpha = 0.9f),
                secondary = seed,
                onSecondary = Color.Black,
                secondaryContainer = seed.copy(alpha = 0.25f).compositeOver(bg),
                onSecondaryContainer = seed.copy(alpha = 0.9f),
                tertiary = seed,
                onTertiary = Color.Black,
                tertiaryContainer = seed.copy(alpha = 0.25f).compositeOver(bg),
                onTertiaryContainer = seed.copy(alpha = 0.9f),
                background = bg,
                onBackground = Color.White.copy(alpha = 0.87f),
                surface = bg,
                onSurface = Color.White.copy(alpha = 0.87f),
                surfaceVariant = seed.copy(alpha = 0.1f).compositeOver(surf),
                onSurfaceVariant = Color.White.copy(alpha = 0.8f),
                outline = Color.White.copy(alpha = 0.2f),
                outlineVariant = Color.White.copy(alpha = 0.12f)
            )
        } else {
            val bg = Color(0xFFFAFAFA)
            lightColorScheme(
                primary = seed,
                onPrimary = Color.White,
                primaryContainer = seed.copy(alpha = 0.15f).compositeOver(bg),
                onPrimaryContainer = seed,
                secondary = seed,
                onSecondary = Color.White,
                secondaryContainer = seed.copy(alpha = 0.12f).compositeOver(bg),
                onSecondaryContainer = seed,
                tertiary = seed,
                onTertiary = Color.White,
                tertiaryContainer = seed.copy(alpha = 0.12f).compositeOver(bg),
                onTertiaryContainer = seed,
                background = bg,
                onBackground = Color.Black.copy(alpha = 0.87f),
                surface = bg,
                onSurface = Color.Black.copy(alpha = 0.87f),
                surfaceVariant = seed.copy(alpha = 0.08f).compositeOver(Color(0xFFF5F5F5)),
                onSurfaceVariant = Color.Black.copy(alpha = 0.8f),
                outline = Color.Black.copy(alpha = 0.2f),
                outlineVariant = Color.Black.copy(alpha = 0.12f)
            )
        }
    }

    // 根据当前主题动态更新状态栏图标颜色：深色模式用白色图标，浅色模式用深色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme) { Surface(color = MaterialTheme.colorScheme.background) { MainAppScreen(sharedViewModel) } }
}

@Composable
fun MainAppScreen(sharedViewModel: SharedViewModel) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(72.dp), // 🌟 调整底栏高度
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                val navIcons = listOf(Icons.Rounded.CellTower, Icons.Rounded.Fingerprint, Icons.Rounded.Explore, Icons.Rounded.Settings)
                val navLabels = listOf("基站", "指纹", "定位", "设置")
                val selectedTabIndex = pagerState.targetPage

                navIcons.forEachIndexed { index, icon ->
                    val isSelected = selectedTabIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(imageVector = icon, contentDescription = navLabels[index]) },
                        label = { Text(text = navLabels[index], style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
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
            // 🌟 核心修复：在此处统一消费 Bottom Padding，解决地图被挡住的问题
            modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
            userScrollEnabled = false,
            beyondViewportPageCount = 3
        ) { page ->
            when (page) {
                0 -> BaseStationManagerScreen(sharedViewModel, 0.dp)
                1 -> FingerprintManagerScreen(sharedViewModel, 0.dp)
                2 -> PositioningTestScreen(sharedViewModel, 0.dp)
                3 -> SettingsScreen(sharedViewModel, 0.dp)
            }
        }
    }
}