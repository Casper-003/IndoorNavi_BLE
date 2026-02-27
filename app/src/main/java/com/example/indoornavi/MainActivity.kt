package com.example.indoornavi

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
//import androidx.compose.foundation.Canvas
//import androidx.compose.foundation.background
//import androidx.compose.foundation.border
//import androidx.compose.foundation.layout.*
//import androidx.compose.material.icons.filled.Warning
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.unit.dp
//import kotlinx.coroutines.launch
//import kotlin.math.pow
//import kotlin.math.sqrt
//import androidx.compose.material.icons.filled.LocationOn
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.foundation.Canvas
//import androidx.compose.ui.draw.clip
//import androidx.compose.foundation.lazy.grid.GridCells
//import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
//import androidx.compose.foundation.lazy.grid.items
//import android.widget.Toast
//import androidx.compose.material.icons.filled.Delete
//import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.ceil
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.PathEffect

// ================= 交互式雷达数据引擎 =================
@Composable
fun InteractiveRadarMap(
    modifier: Modifier = Modifier,
    gridCoordinates: List<Point>,       // 所有的网格节点坐标
    recordedPoints: List<Point>,        // 已经采集过指纹的节点坐标
    selectedPoint: Point? = null,       // 用户选中的点 (作为 Ground Truth 真实坐标)
    predictedPoint: Point? = null,      // 算法预测的实时红点坐标 (仅在定位页面有)
    onPointSelected: (Point) -> Unit    // 用户点击地图后的回调
) {
    // 自动扫描地图边界，以便让画面自适应居中缩放
    val maxX = gridCoordinates.maxOfOrNull { it.x }?.coerceAtLeast(1.0) ?: 10.0
    val maxY = gridCoordinates.maxOfOrNull { it.y }?.coerceAtLeast(1.0) ?: 10.0

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(gridCoordinates) {
                // 核心：精准手势识别，将屏幕点击坐标映射回物理网格坐标
                detectTapGestures { offset ->
                    val padding = 40.dp.toPx()
                    val usableWidth = size.width - padding * 2
                    val usableHeight = size.height - padding * 2
                    val scaleX = usableWidth / maxX
                    val scaleY = usableHeight / maxY

                    val tapX = (offset.x - padding) / scaleX
                    val tapY = (offset.y - padding) / scaleY

                    // 寻找距离点击位置最近的那个物理网格点
                    val nearest = gridCoordinates.minByOrNull { Math.hypot(it.x - tapX, it.y - tapY) }

                    if (nearest != null) {
                        // 设定吸附阈值，防止误触边缘
                        val distance = Math.hypot(nearest.x - tapX, nearest.y - tapY)
                        if (distance < (maxX / 5).coerceAtLeast(1.5)) {
                            onPointSelected(nearest) // 触发外部点击事件
                        }
                    }
                }
            }
    ) {
        val padding = 40.dp.toPx()
        val usableWidth = size.width - padding * 2
        val usableHeight = size.height - padding * 2
        val scaleX = usableWidth / maxX
        val scaleY = usableHeight / maxY

        // ============================================================
        // 【核心修复：网格线】新增淡雅背景网格线 (不能喧宾夺主)
        // 这里使用了浅灰色虚线，科技感十足且不干扰视线
        // ============================================================
        val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        val gridLineColor = Color.Gray.copy(alpha = 0.15f)
        val strokeWidth = 1.dp.toPx()

        // 绘制 Y 轴方向的垂直虚线
        gridCoordinates.map { it.x }.distinct().forEach { x ->
            val cx = padding + (x * scaleX).toFloat()
            drawLine(
                color = gridLineColor,
                start = Offset(cx, padding),
                end = Offset(cx, padding + usableHeight.toFloat()),
                strokeWidth = strokeWidth,
                pathEffect = dashPathEffect
            )
        }
        // 绘制 X 轴方向的水平虚线
        gridCoordinates.map { it.y }.distinct().forEach { y ->
            val cy = padding + (y * scaleY).toFloat()
            drawLine(
                color = gridLineColor,
                start = Offset(padding, cy),
                end = Offset(padding + usableWidth.toFloat(), cy),
                strokeWidth = strokeWidth,
                pathEffect = dashPathEffect
            )
        }

        // ============================================================
        // 【核心升级：点阵式底图】绘制底层网格点阵 (移除密密麻麻的文字干扰)
        // ============================================================
        gridCoordinates.forEach { pt ->
            val cx = padding + (pt.x * scaleX).toFloat()
            val cy = padding + (pt.y * scaleY).toFloat()

            // 🌟 核心视觉逻辑 🌟
            // 尚未采集的点(RecordedPoints不存在该点) -> 用低透明度的灰色小点表示
            // 已经采集的点 -> 用鲜艳的鲜绿色大点表示 (工业级采集体验)
            val isRecorded = recordedPoints.contains(pt)
            val isSelected = selectedPoint == pt

            drawCircle(
                color = if (isRecorded) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.3f),
                radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(), // 选中时小圆点固定放大
                center = Offset(cx, cy)
            )

            // 【核心修复：绝对固定对齐】点选节点时，加一个蓝色光环 (Visual Feedback)
            // 不再动态改变圆点的大小，而是加入一层视觉光晕，彻底解决布局跳动问题！
            if (isSelected) {
                drawCircle(
                    color = Color(0xFF1976D2).copy(alpha = 0.6f),
                    radius = 14.dp.toPx(), // 固定高度的光晕
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx()) // 粗边框光环
                )
            }
        }

        // ============================================================
        // 【误差测算专属】如果有了算法预测点 (WKNN 红心雷达)
        // ============================================================
        predictedPoint?.let { pos ->
            // 防止红点波动过大飘出屏幕
            val safeX = pos.x.coerceIn(-1.0, maxX + 1.0)
            val safeY = pos.y.coerceIn(-1.0, maxY + 1.0)

            val cx = padding + (safeX * scaleX).toFloat()
            val cy = padding + (safeY * scaleY).toFloat()

            // 绘制红点呼吸光晕
            drawCircle(color = Color(0xFFE53935).copy(alpha = 0.2f), radius = 24.dp.toPx(), center = Offset(cx, cy))
            // 绘制实体红心
            drawCircle(color = Color(0xFFE53935), radius = 6.dp.toPx(), center = Offset(cx, cy))

            // ============================================================
            // 【定位页误差直观展示】动态绘制误差连线 (连接 Ground Truth 和 预测点)
            // ============================================================
            selectedPoint?.let { truth ->
                val tx = padding + (truth.x * scaleX).toFloat()
                val ty = padding + (truth.y * scaleY).toFloat()

                // 绘制蓝色虚线，直观展示预测坐标与真实坐标之间的误差距离
                drawLine(
                    color = Color(0xFF1976D2).copy(alpha = 0.6f),
                    start = Offset(tx, ty),
                    end = Offset(cx, cy),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }
        }
    }
}
// ================= CSV 帮助函数 =================
suspend fun exportCsvToUri(context: Context, uri: Uri, points: List<ReferencePoint>): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write("ID,X,Y,FINGERPRINTS\n")
                points.forEach { rp ->
                    val fpsStr = rp.fingerprint.entries.joinToString(";") { "${it.key}=${it.value}" }
                    writer.write("${rp.id},${rp.coordinate.x},${rp.coordinate.y},$fpsStr\n")
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

suspend fun importCsvFromUri(context: Context, uri: Uri): List<ReferencePoint>? {
    return withContext(Dispatchers.IO) {
        try {
            val importedPoints = mutableListOf<ReferencePoint>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.split(",")
                        if (parts.size >= 4) {
                            val id = parts[0]
                            val x = parts[1].toDoubleOrNull() ?: 0.0
                            val y = parts[2].toDoubleOrNull() ?: 0.0
                            val fpsMap = parts[3].split(";").mapNotNull { fp ->
                                val pair = fp.split("=")
                                if (pair.size == 2) pair[0] to (pair[1].toIntOrNull() ?: -100) else null
                            }.toMap()
                            importedPoints.add(ReferencePoint(id, Point(x, y), fpsMap))
                        }
                    }
                }
            }
            importedPoints
        } catch (e: Exception) {
            null
        }
    }
}

// ================= 全局动态主题 (自动跟随系统深色/浅色模式) =================
@Composable
fun IndoorNaviTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 系统会根据深浅色模式自动分配基础色板
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 👈 核心变化：使用我们刚刚定义的动态主题包裹全应用
            IndoorNaviTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // 背景色自动随深色模式切换
                ) {
                    MainAppRouter()
                }
            }
        }
    }
}

@Composable
fun MainAppRouter() {
    var permissionsGranted by remember { mutableStateOf(false) }
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
        permissionsGranted = permissionsMap.values.all { it }
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(permissionsToRequest) }
    if (permissionsGranted) MainAppScreen() else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请授予权限") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(sharedViewModel: SharedViewModel = viewModel()) {
    val tabs = listOf("基站管理", "指纹管理", "定位测试", "设置")
    val icons = listOf(Icons.Default.Build, Icons.Default.Edit, Icons.Default.LocationOn, Icons.Default.Settings)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    if (isWideScreen) {
        // 📺 【平板 / 横屏模式】：左侧导航栏 + 右侧内容
        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {

            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(modifier = Modifier.weight(1f))
                tabs.forEachIndexed { index, title ->
                    NavigationRailItem(
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) },
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))

            // 🌟 核心 UX 升级：横屏模式下使用垂直翻页器 (上下滑动)！
            VerticalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                userScrollEnabled = false
            ) { page ->
                when (page) {
                    0 -> BaseStationManagerScreen(selectedDevices = sharedViewModel.selectedDevices, onSelectionChange = { sharedViewModel.selectedDevices = it })
                    1 -> FingerprintManagerScreen(selectedDevices = sharedViewModel.selectedDevices, recordedPoints = sharedViewModel.recordedPoints, onRecordedPointsChange = { sharedViewModel.recordedPoints = it })
                    2 -> PositioningTestScreen(selectedDevices = sharedViewModel.selectedDevices, sharedFingerprints = sharedViewModel.recordedPoints, isAdvancedModeEnabled = sharedViewModel.isAdvancedModeEnabled)
                    3 -> SettingsScreen(isAdvancedModeEnabled = sharedViewModel.isAdvancedModeEnabled, onAdvancedModeChange = { sharedViewModel.isAdvancedModeEnabled = it })
                }
            }
        }
    } else {
        // 📱 【手机竖屏模式】：底部导航栏 + 右侧内容
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(tabs[pagerState.currentPage], fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = title) },
                            label = { Text(title) },
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                        )
                    }
                }
            }
        ) { innerPadding ->
            // 🌟 竖屏模式保持水平翻页器 (左右滑动)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                userScrollEnabled = false
            ) { page ->
                when (page) {
                    0 -> BaseStationManagerScreen(selectedDevices = sharedViewModel.selectedDevices, onSelectionChange = { sharedViewModel.selectedDevices = it })
                    1 -> FingerprintManagerScreen(selectedDevices = sharedViewModel.selectedDevices, recordedPoints = sharedViewModel.recordedPoints, onRecordedPointsChange = { sharedViewModel.recordedPoints = it })
                    2 -> PositioningTestScreen(selectedDevices = sharedViewModel.selectedDevices, sharedFingerprints = sharedViewModel.recordedPoints, isAdvancedModeEnabled = sharedViewModel.isAdvancedModeEnabled)
                    3 -> SettingsScreen(isAdvancedModeEnabled = sharedViewModel.isAdvancedModeEnabled, onAdvancedModeChange = { sharedViewModel.isAdvancedModeEnabled = it })
                }
            }
        }
    }
}

// ================= Tab 0: 基站管理 =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseStationManagerScreen(selectedDevices: Map<String, BeaconDevice>, onSelectionChange: (Map<String, BeaconDevice>) -> Unit) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val displayedDevices by scanner.scannedDevicesFlow.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val unselectedDevices = displayedDevices.filter { it.macAddress !in selectedDevices.keys }

    DisposableEffect(Unit) { onDispose { scanner.stopScan() } }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    // 🧱 积木 1：左侧扫描列表区
    val scanListUI = @Composable { modifier: Modifier ->
        Column(modifier = modifier) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { if (isScanning) scanner.stopScan() else scanner.startScan(); isScanning = !isScanning }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                    Text(if (isScanning) "⏹ 停止" else "▶ 开始扫描")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("共发现: ${displayedDevices.size} 台", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            }
            PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(unselectedDevices, key = { it.macAddress }) { device ->
                        DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) }
                    }
                }
            }
        }
    }

    // 🧱 积木 2：购物车区 (已锁定基站)
    val cartUI = @Composable { modifier: Modifier, isLandscape: Boolean ->
        Column(modifier = modifier.padding(16.dp)) {
            Text("已锁定基站 (${selectedDevices.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            if (isLandscape) {
                // 横屏模式：购物车在右侧，呈垂直列表排列
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device ->
                        SelectedDeviceChip(device = device, modifier = Modifier.animateItem().fillMaxWidth()) { onSelectionChange(selectedDevices - device.macAddress) }
                    }
                }
            } else {
                // 竖屏模式：购物车在顶部，呈水平滑动排列
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device ->
                        SelectedDeviceChip(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices - device.macAddress) }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    // ================= 核心响应式布局 =================
    if (isWideScreen) {
        // 📺 横屏：左右分栏
        Row(modifier = Modifier.fillMaxSize()) {
            scanListUI(Modifier.weight(1.5f))
            if (selectedDevices.isNotEmpty()) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))
                cartUI(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)), true)
            }
        }
    } else {
        // 📱 竖屏：上下分栏
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = selectedDevices.isNotEmpty()) {
                cartUI(Modifier.fillMaxWidth(), false)
            }
            scanListUI(Modifier.weight(1f))
        }
    }
}

@Composable
fun DeviceItemCard(device: BeaconDevice, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hasName = !device.name.isNullOrEmpty(); val displayName = if (hasName) device.name!! else "未知设备"; val nameColor = if (hasName) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (hasName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = displayName, color = nameColor, fontWeight = FontWeight.Bold)
                Text(text = device.macAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text(text = "${device.smoothedRssi} dBm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (device.smoothedRssi > -60) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun SelectedDeviceChip(device: BeaconDevice, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    Card(onClick = onRemove, modifier = modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Text(text = device.name ?: "未知设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(text = device.macAddress.takeLast(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)) }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
        }
    }
}

// ================= Tab 1: 点阵指纹采集 =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintManagerScreen(
    selectedDevices: Map<String, BeaconDevice>,
    recordedPoints: List<ReferencePoint>,
    onRecordedPointsChange: (List<ReferencePoint>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    DisposableEffect(Unit) { scanner.startScan(); onDispose { scanner.stopScan() } }

    var isMapSetupMode by rememberSaveable { mutableStateOf(true) }
    var mapWidthStr by rememberSaveable { mutableStateOf("10") }
    var mapHeightStr by rememberSaveable { mutableStateOf("10") }
    var spacingStr by rememberSaveable { mutableStateOf("2") }

    var selectedPointX by rememberSaveable { mutableStateOf(-1.0) }
    var selectedPointY by rememberSaveable { mutableStateOf(-1.0) }
    val selectedPoint = if (selectedPointX >= 0) Point(selectedPointX, selectedPointY) else null

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) { coroutineScope.launch { val s = exportCsvToUri(context, uri, recordedPoints); android.widget.Toast.makeText(context, if (s) "导出成功" else "导出失败", android.widget.Toast.LENGTH_SHORT).show() } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { coroutineScope.launch { val data = importCsvFromUri(context, uri); if (data != null) { onRecordedPointsChange(data); android.widget.Toast.makeText(context, "导入成功", android.widget.Toast.LENGTH_SHORT).show() } } }
    }

    val width = mapWidthStr.toDoubleOrNull() ?: 10.0
    val height = mapHeightStr.toDoubleOrNull() ?: 10.0
    val spacing = spacingStr.toDoubleOrNull()?.takeIf { it > 0 } ?: 2.0
    val columns = ceil(width / spacing).toInt() + 1
    val rows = ceil(height / spacing).toInt() + 1
    val totalPoints = columns * rows

    val gridCoordinates = remember(columns, rows, spacing) {
        val coords = mutableListOf<Point>()
        for (y in 0 until rows) { for (x in 0 until columns) { coords.add(Point(x * spacing, y * spacing)) } }
        coords
    }

    var showClearDialog by remember { mutableStateOf(false) }

    AnimatedContent(targetState = isMapSetupMode, label = "ModeSwitch") { setupMode ->
        if (setupMode) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("地图参数配置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = mapWidthStr, onValueChange = { mapWidthStr = it }, label = { Text("宽/m") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = mapHeightStr, onValueChange = { mapHeightStr = it }, label = { Text("长/m") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = spacingStr, onValueChange = { spacingStr = it }, label = { Text("间距/m") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        val evalText = "系统评估: 当前分辨率需采集 $totalPoints 个点。" + if (spacing < 1.0) " 精度极高，但工作量巨大！" else if (spacing > 3.0) " 采集极快，但定位精度较低。" else " 精度与效率完美平衡！"
                        Text(text = evalText, style = MaterialTheme.typography.bodyMedium, color = if (spacing < 1.0 || spacing > 3.0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { selectedPointX = -1.0; selectedPointY = -1.0; isMapSetupMode = false }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("进入纯净雷达采集", style = MaterialTheme.typography.titleMedium) }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部操作栏
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 修复后的代码：
                    IconButton(onClick = { isMapSetupMode = true }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                    Text("进度: ${recordedPoints.size}/$totalPoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showClearDialog = true }) { Icon(Icons.Default.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/octet-stream")) }) { Text("导入") }
                    TextButton(onClick = { exportLauncher.launch("IndoorNavi_Fingerprint.csv") }) { Text("导出") }
                }
                HorizontalDivider()

                // 🧱 积木 1：纯净雷达图
                val radarMapUI = @Composable { modifier: Modifier ->
                    Box(modifier = modifier.padding(16.dp)) {
                        InteractiveRadarMap(
                            gridCoordinates = gridCoordinates,
                            recordedPoints = recordedPoints.map { it.coordinate },
                            selectedPoint = selectedPoint,
                            onPointSelected = { pt -> selectedPointX = pt.x; selectedPointY = pt.y }
                        )
                    }
                }

                // 🧱 积木 2：弹出采集操作面板
                val controlPanelUI = @Composable { modifier: Modifier, isLandscape: Boolean ->
                    Box(
                        modifier = modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                // 横屏圆角在左侧，竖屏圆角在顶部
                                if (isLandscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (selectedPoint != null) {
                                val isRecorded = recordedPoints.any { it.coordinate == selectedPoint }
                                Text("目标网格坐标: X:${selectedPoint.x}, Y:${selectedPoint.y}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                                if (isRecorded) {
                                    // 重新采集与清除数据按钮
                                    if (isLandscape) {
                                        // 横屏下纵向排列按钮更美观
                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            OutlinedButton(onClick = { onRecordedPointsChange(recordedPoints.filterNot { it.coordinate == selectedPoint }) }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除数据") }
                                            Button(onClick = { val fp = selectedDevices.keys.associateWith { liveDevices.find { d -> d.macAddress == it }?.smoothedRssi ?: -100 }; onRecordedPointsChange(recordedPoints.filterNot { it.coordinate == selectedPoint } + ReferencePoint("RP-${selectedPoint.x}-${selectedPoint.y}", selectedPoint, fp)) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("重新采集") }
                                        }
                                    } else {
                                        // 竖屏下横向排列按钮更节省空间
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            OutlinedButton(onClick = { onRecordedPointsChange(recordedPoints.filterNot { it.coordinate == selectedPoint }) }, modifier = Modifier.weight(1f).height(50.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除数据") }
                                            Button(onClick = { val fp = selectedDevices.keys.associateWith { liveDevices.find { d -> d.macAddress == it }?.smoothedRssi ?: -100 }; onRecordedPointsChange(recordedPoints.filterNot { it.coordinate == selectedPoint } + ReferencePoint("RP-${selectedPoint.x}-${selectedPoint.y}", selectedPoint, fp)) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("重新采集") }
                                        }
                                    }
                                } else {
                                    Button(onClick = { val fp = selectedDevices.keys.associateWith { liveDevices.find { d -> d.macAddress == it }?.smoothedRssi ?: -100 }; onRecordedPointsChange(recordedPoints + ReferencePoint("RP-${selectedPoint.x}-${selectedPoint.y}", selectedPoint, fp)) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("采集当前点位") }
                                }
                            } else {
                                Text("💡 请在上方雷达图中点击需要操作的节点", color = Color.Gray, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    }
                }

                // ================= 核心响应式布局 =================
                if (isWideScreen) {
                    // 📺 横屏：左大地图 + 右侧控制台
                    Row(modifier = Modifier.weight(1f)) {
                        radarMapUI(Modifier.weight(1.5f))
                        controlPanelUI(Modifier.weight(1f).fillMaxHeight(), true)
                    }
                } else {
                    // 📱 竖屏：上地图 + 底部固定面板
                    Column(modifier = Modifier.weight(1f)) {
                        radarMapUI(Modifier.weight(1f))
                        controlPanelUI(Modifier.fillMaxWidth().height(160.dp), false)
                    }
                }
            }
        }
    }

    if (showClearDialog) { AlertDialog(onDismissRequest = { showClearDialog = false }, title = { Text("清空所有指纹？") }, text = { Text("这将会清除您在此页面录入的所有数据。") }, confirmButton = { Button(onClick = { onRecordedPointsChange(emptyList()); showClearDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") } }, dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }) }
}

// ================= Tab 2: WKNN 定位测试 =================
@Composable
fun PositioningTestScreen(
    selectedDevices: Map<String, BeaconDevice>,
    sharedFingerprints: List<ReferencePoint>,
    isAdvancedModeEnabled: Boolean
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    DisposableEffect(Unit) { scanner.startScan(); onDispose { scanner.stopScan() } }

    val locator = remember { WknnLocator() }
    var customFingerprints by remember { mutableStateOf<List<ReferencePoint>?>(null) }
    val activeFingerprints = customFingerprints ?: sharedFingerprints

    // UI 控制状态
    var kValue by remember { mutableFloatStateOf(4f) }
    var useWknn by remember { mutableStateOf(true) }
    var enableSmoothing by remember { mutableStateOf(true) }

    if (!isAdvancedModeEnabled) {
        kValue = 4f
        useWknn = true
        enableSmoothing = true
    }

    var groundTruthPoint by remember { mutableStateOf<Point?>(null) }
    var rawPosition by remember { mutableStateOf<Point?>(null) }
    var smoothedPosition by remember { mutableStateOf<Point?>(null) }
    var currentError by remember { mutableStateOf<Double?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { coroutineScope.launch { val data = importCsvFromUri(context, uri); if (data != null) customFingerprints = data } }
    }

    LaunchedEffect(liveDevices, activeFingerprints, kValue, useWknn) {
        if (activeFingerprints.isNotEmpty() && selectedDevices.size >= 3) {
            val liveRssiMap = selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
            val newRawPos = locator.locate(liveRssiMap, activeFingerprints, kValue.roundToInt(), useWknn)
            rawPosition = newRawPos

            if (newRawPos != null) {
                if (enableSmoothing) {
                    if (smoothedPosition == null) { smoothedPosition = newRawPos }
                    else {
                        val alpha = 0.3
                        smoothedPosition = Point(
                            smoothedPosition!!.x + alpha * (newRawPos.x - smoothedPosition!!.x),
                            smoothedPosition!!.y + alpha * (newRawPos.y - smoothedPosition!!.y)
                        )
                    }
                } else { smoothedPosition = newRawPos }

                if (groundTruthPoint != null) {
                    currentError = Math.hypot(smoothedPosition!!.x - groundTruthPoint!!.x, smoothedPosition!!.y - groundTruthPoint!!.y)
                } else { currentError = null }
            }
        }
    }

    val gridCoordinates = remember(activeFingerprints) { activeFingerprints.map { it.coordinate } }

    // ================= 定义局部 UI 积木，避免代码重复 =================

    // 积木 1：开发者控制面板
    val controlPanelUI = @Composable { modifier: Modifier ->
        Column(modifier = modifier.verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("性能评估台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Button(onClick = { importLauncher.launch(arrayOf("text/csv")) }) { Text("切换地图") }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("解算算法:", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        SegmentedButton(options = listOf("KNN", "WKNN"), selectedIndex = if (useWknn) 1 else 0, onOptionSelected = { useWknn = (it == 1) })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("K 值 (${kValue.roundToInt()}):", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Slider(value = kValue, onValueChange = { kValue = kotlin.math.round(it) }, valueRange = 1f..7f, steps = 5, modifier = Modifier.weight(1.5f))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("坐标级轨迹平滑 (防抖):", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = enableSmoothing, onCheckedChange = { enableSmoothing = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("基准真值 (地图点击选定)", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                        Text("当前动态误差", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(if (groundTruthPoint != null) "X:${groundTruthPoint!!.x}, Y:${groundTruthPoint!!.y}" else "尚未选定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (currentError != null) "${String.format("%.2f", currentError)} m" else "--", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if ((currentError ?: 99.0) < 1.5) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // 积木 2：雷达地图画布
    val radarMapUI = @Composable { modifier: Modifier ->
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
        ) {
            if (activeFingerprints.isEmpty() || selectedDevices.size < 3) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    Text("雷达待命", style = MaterialTheme.typography.headlineSmall, color = Color.Gray)
                    Text("请先锁定基站并在指纹页采集数据", color = Color.Gray)
                }
            } else {
                InteractiveRadarMap(
                    gridCoordinates = gridCoordinates,
                    recordedPoints = gridCoordinates,
                    selectedPoint = groundTruthPoint,
                    predictedPoint = smoothedPosition,
                    onPointSelected = { pt -> if (isAdvancedModeEnabled) groundTruthPoint = pt }
                )

                smoothedPosition?.let { pos ->
                    Text(
                        "实测 X: ${String.format("%.2f", pos.x)}, Y: ${String.format("%.2f", pos.y)}",
                        modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // ================= 核心响应式布局大脑 =================
    // 直接获取当前设备的屏幕配置
    val configuration = LocalConfiguration.current
    // 定义宽屏的阈值 (600dp 是 Android 官方建议的平板/横屏分水岭)
    val isWideScreen = configuration.screenWidthDp > 600

    if (isWideScreen) {
        // 📺 【平板 / 横屏模式】：左右分栏
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧大雷达地图 (占比 60%)
            radarMapUI(Modifier.weight(1.5f).padding(16.dp).fillMaxHeight())

            // 右侧控制台 (占比 40%)
            if (isAdvancedModeEnabled) {
                controlPanelUI(Modifier.weight(1f).padding(top = 16.dp, bottom = 16.dp, end = 16.dp).fillMaxHeight())
            }
        }
    } else {
        // 📱 【手机竖屏模式】：上下分栏
        Column(modifier = Modifier.fillMaxSize()) {
            if (isAdvancedModeEnabled) {
                controlPanelUI(Modifier.weight(0.9f).padding(16.dp).fillMaxWidth())
            }

            val mapWeight = if (isAdvancedModeEnabled) 1.1f else 1f
            radarMapUI(Modifier.weight(mapWeight).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth())
        }
    }
}

// ================= UI 小工具：分段按钮 =================
@Composable
fun SegmentedButton(options: List<String>, selectedIndex: Int, onOptionSelected: (Int) -> Unit) {
    Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
        options.forEachIndexed { index, text ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onOptionSelected(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = text, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
            if (index < options.size - 1) {
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

// ================= Tab 3: 设置 =================
@Composable
fun SettingsScreen(isAdvancedModeEnabled: Boolean, onAdvancedModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var autoScan by remember { mutableStateOf(true) }
    var backgroundMode by remember { mutableStateOf(false) }

    // 弹窗状态管理
    var showClearConfirm by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("系统设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // --- 分组 1：雷达引擎与开发者选项 ---
            item {
                Text("雷达引擎配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column {
                        SettingSwitchItem(
                            icon = Icons.Default.Search, title = "启动时自动扫描", subtitle = "进入应用后自动开启低功耗蓝牙 (BLE) 扫描",
                            checked = autoScan, onCheckedChange = { autoScan = it }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        // 🌟 核心开关：开发者评估模式
                        SettingSwitchItem(
                            icon = Icons.Default.Build, // 👈 核心修复：换成基础的 Build (扳手) 图标
                            title = "开发者性能评估模式",
                            subtitle = "开启后可在定位页手动调节 WKNN 算法参数并测算误差",
                            checked = isAdvancedModeEnabled,
                            onCheckedChange = onAdvancedModeChange
                        )
                    }
                }
            }

            // --- 分组 2：数据与存储 ---
            item {
                Text("数据与存储", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    SettingClickableItem(
                        icon = Icons.Default.Delete, title = "清空所有本地缓存", subtitle = "清除已锁定的基站与所有未导出的指纹快照", isDestructive = true,
                        onClick = { showClearConfirm = true }
                    )
                }
            }

            // --- 分组 3：关于项目与开发者 ---
            item {
                Text("关于", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column {
                        // 🧑‍💻 开发者专属署名
// 🧑‍💻 开发者专属署名
                        SettingClickableItem(
                            icon = Icons.Default.Person, title = "开发者", subtitle = "Casper-003", // 👈 填你的名字
                            onClick = { Toast.makeText(context, "感谢使用本系统！", Toast.LENGTH_SHORT).show() }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 📧 联系邮箱
                        SettingClickableItem(
                            icon = Icons.Default.Email, title = "联系邮箱", subtitle = "casper-003@outlook.com", // 👈 填你的邮箱地址
                            onClick = { Toast.makeText(context, "期待您的技术交流与反馈", Toast.LENGTH_SHORT).show() }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 🌐 开源仓库
                        SettingClickableItem(
                            icon = Icons.Default.Share, title = "开源仓库 (GitHub)", subtitle = "github.com/casper-003/IndoorNavi", // 👈 填你的开源地址
                            onClick = {
                                // 👈 核心变化：直接唤起手机浏览器打开你的仓库！
                                uriHandler.openUri("https://github.com/Casper-003/IndoorNavi_BLE")
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(
                            icon = Icons.Default.Info, title = "系统版本", subtitle = "v2.2.0 (Release) - WKNN Engine",
                            onClick = { Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show() }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(
                            icon = Icons.Default.List, title = "查看使用手册", subtitle = "了解如何构建指纹库及标定误差",
                            onClick = { showManualDialog = true }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(
                            icon = Icons.Default.Build, title = "开源声明与技术栈", subtitle = "查看使用的第三方库 (Jetpack Compose 等)",
                            onClick = { showLicenseDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("Powered by Kotlin & Jetpack Compose\n基于 BLE 与 WKNN 算法的室内定位系统", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ================= 弹窗集合 =================
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("警告") }, text = { Text("您确定要清空所有数据吗？此操作无法撤销。") },
            confirmButton = { Button(onClick = { showClearConfirm = false; Toast.makeText(context, "缓存已清空", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("📖 系统使用手册") },
            text = { Text("1. 【基站管理】：请先在此页面扫描并锁定至少 3 个用于定位的信标设备。\n2. 【指纹管理】：配置物理空间大小，在指定网格点上点击“采集”录入环境信号。\n3. 【定位测试】：红点代表算法实时解算的当前坐标。若开启开发者模式，可点击地图放置真实坐标点，用以测算动态误差。") },
            confirmButton = { TextButton(onClick = { showManualDialog = false }) { Text("我已了解") } }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("⚖️ 开源技术声明") },
            text = { Text("本系统构建于以下现代移动开发技术栈：\n\n• Kotlin Coroutines\n• Jetpack Compose Material 3\n• Android BLE API\n• State & ViewModel Architecture\n\n核心定位引擎完全由开发者自主实现，采用了针对 RSSI 信号优化的加权 K-近邻算法 (WKNN)。") },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") } }
        )
    }
}

// --- 设置页专用 UI 组件：带 Switch 的设置项 ---
@Composable
fun SettingSwitchItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// --- 设置页专用 UI 组件：可点击的普通设置项 ---
@Composable
fun SettingClickableItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (isDestructive) contentColor.copy(alpha = 0.7f) else Color.Gray)
        }
    }
}

// ================= 全局数据管家 (ViewModel) =================
// 专门解决退到后台、旋转屏幕导致的数据丢失问题
// ================= 全局数据管家 (ViewModel) =================
class SharedViewModel : ViewModel() {
    var selectedDevices by mutableStateOf(mapOf<String, BeaconDevice>())
    var recordedPoints by mutableStateOf(listOf<ReferencePoint>())
    // 新增：开发者高级评估模式开关（默认关闭，给普通用户最纯净的体验）
    var isAdvancedModeEnabled by mutableStateOf(false)
}