package com.example.indoornavi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ================= Tab 3: 定位引擎与性能评估 =================
@Composable
fun PositioningTestScreen(sharedViewModel: SharedViewModel) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    val fusionEngine = remember { LocationFusionEngine(context) }
    val fusedPosition by fusionEngine.fusedPosition.collectAsState()

    DisposableEffect(Unit) {
        scanner.startScan()
        fusionEngine.start()
        onDispose { scanner.stopScan(); fusionEngine.stop() }
    }

    val locator = remember { WknnLocator() }
    val activeFingerprints = sharedViewModel.recordedPoints

    var kValue by remember { mutableFloatStateOf(4f) }
    var useWknn by remember { mutableStateOf(true) }
    var smoothingStrategy by remember { mutableIntStateOf(2) }

    if (!sharedViewModel.isAdvancedModeEnabled) {
        kValue = 4f
        useWknn = true
        smoothingStrategy = 2
    }

    var groundTruthPoint by remember { mutableStateOf<Point?>(null) }
    var rawPosition by remember { mutableStateOf<Point?>(null) }
    var emaSmoothedPosition by remember { mutableStateOf<Point?>(null) }
    var currentError by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(liveDevices, activeFingerprints, kValue, useWknn) {
        if (activeFingerprints.isNotEmpty() && sharedViewModel.selectedDevices.size >= 3) {
            val liveRssiMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
            val newRawPos = locator.locate(liveRssiMap, activeFingerprints, kValue.roundToInt(), useWknn)
            rawPosition = newRawPos

            if (newRawPos != null) {
                fusionEngine.updateWknnPosition(newRawPos)
                if (emaSmoothedPosition == null) emaSmoothedPosition = newRawPos
                else { val alpha = 0.3; emaSmoothedPosition = Point(emaSmoothedPosition!!.x + alpha * (newRawPos.x - emaSmoothedPosition!!.x), emaSmoothedPosition!!.y + alpha * (newRawPos.y - emaSmoothedPosition!!.y)) }
            }
        }
    }

    val displayPosition = when (smoothingStrategy) {
        1 -> emaSmoothedPosition
        2 -> fusedPosition ?: rawPosition
        else -> rawPosition
    }

    LaunchedEffect(displayPosition, groundTruthPoint) {
        currentError = if (displayPosition != null && groundTruthPoint != null) Math.hypot(displayPosition.x - groundTruthPoint!!.x, displayPosition.y - groundTruthPoint!!.y) else null
    }

    val w = sharedViewModel.spaceWidth.toFloatOrNull() ?: 10f
    val l = sharedViewModel.spaceLength.toFloatOrNull() ?: 10f
    val s = sharedViewModel.gridSpacing.toFloatOrNull() ?: 2f
    val gridCoordinates = remember(w, l, s) {
        val pts = mutableListOf<Point>(); var x = 0f
        while (x <= w) { var y = 0f; while (y <= l) { pts.add(Point(x.toDouble(), y.toDouble())); y += s }; x += s }
        pts
    }

    // 🌟 1. 屏幕宽度智能检测
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    // 🌟 2. 提取公共的雷达图 UI
    val mapContent = @Composable {
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                // 横屏时以高度为准画正方形(防止被上下裁切)，竖屏时以宽度为准
                .aspectRatio(1f, matchHeightConstraintsFirst = isWideScreen)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
        ) {
            if (activeFingerprints.isEmpty() || sharedViewModel.selectedDevices.size < 3) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    Text("雷达待命", style = MaterialTheme.typography.headlineSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("请先锁定 3 个以上基站并在指纹页采集数据", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            } else {
                InteractiveRadarMap(
                    gridCoordinates = gridCoordinates,
                    recordedPoints = activeFingerprints.map { it.coordinate },
                    selectedPoint = groundTruthPoint,
                    predictedPoint = displayPosition,
                    onPointSelected = { pt -> if (sharedViewModel.isAdvancedModeEnabled) groundTruthPoint = pt }
                )
            }
        }
    }

    // 🌟 3. 提取公共的控制台 UI
    val controlContent = @Composable {
        if (sharedViewModel.isAdvancedModeEnabled) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "性能评估台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("解算算法:", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                        Box(modifier = Modifier.weight(1.2f)) { SegmentedButton(options = listOf("KNN", "WKNN"), selectedIndex = if (useWknn) 1 else 0, onOptionSelected = { useWknn = (it == 1) }) }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("K 值 (${kValue.roundToInt()}):", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                        Slider(value = kValue, onValueChange = { kValue = kotlin.math.round(it) }, valueRange = 1f..7f, steps = 5, modifier = Modifier.weight(1.2f))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text("轨迹优化策略 (消融实验):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    SegmentedButton(options = listOf("纯生数据", "传统平滑", "PDR 融合"), selectedIndex = smoothingStrategy, onOptionSelected = { smoothingStrategy = it })

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("基准真值 (地图选定)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = if (groundTruthPoint != null) "X: ${groundTruthPoint!!.x}, Y: ${groundTruthPoint!!.y}" else "尚未选定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if(groundTruthPoint != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("当前动态误差", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = if (currentError != null) "${String.format("%.2f", currentError)} m" else "--", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if ((currentError ?: 99.0) < 1.5) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // 🌟 4. 全局页面响应式排版容器
    Column(modifier = Modifier.fillMaxSize()) {
        // 固定顶部的 HUD 标题栏 (不参与滑动)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("定位引擎", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            displayPosition?.let { pos ->
                val strategyName = listOf("RAW", "EMA", "PDR")[smoothingStrategy]
                Card(shape = CircleShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "$strategyName | X: ${String.format("%.2f", pos.x)}, Y: ${String.format("%.2f", pos.y)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 🌟 核心：横竖屏分发逻辑
        if (isWideScreen) {
            // 平板横屏：左右分列结构！
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 左侧大地图
                Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    mapContent()
                }
                // 右侧独立滚动的参数面板
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    controlContent()
                    Spacer(modifier = Modifier.height(180.dp)) // 预留避让空间
                }
            }
        } else {
            // 手机竖屏：上下滑动结构
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    mapContent()
                }
                controlContent()
                Spacer(modifier = Modifier.height(180.dp)) // 预留避让空间，保证可以滑出 Dock
            }
        }
    }
}