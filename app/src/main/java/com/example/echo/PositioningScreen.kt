package com.example.echo

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositioningTestScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    val fusionEngine = remember { LocationFusionEngine(context) }
    val fusedPosition by fusionEngine.fusedPosition.collectAsState()
    val currentHeading by fusionEngine.currentHeadingFlow.collectAsState()

    val pathfinder = remember { AStarPathfinder() }

    val benchmarkLogger = remember { BenchmarkLogger() }
    var benchmarkProgress by remember { mutableFloatStateOf(0f) }

    var showClearObstaclesConfirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        scanner.startScan()
        fusionEngine.start()
        onDispose {
            scanner.stopScan()
            fusionEngine.stop()
            benchmarkLogger.cancelLogging()
        }
    }

    val locator = remember { WknnLocator() }
    val activeFingerprints = sharedViewModel.recordedPoints

    var kValue by remember { mutableFloatStateOf(4f) }
    var useWknn by remember { mutableStateOf(true) }
    var smoothingStrategy by remember { mutableIntStateOf(2) }

    if (!sharedViewModel.isAdvancedModeEnabled) {
        kValue = 4f; useWknn = true; smoothingStrategy = 2
    }

    var groundTruthPoint by remember { mutableStateOf<Point?>(null) }

    var rawPosition by remember { mutableStateOf<Point?>(null) }
    var emaSmoothedPosition by remember { mutableStateOf<Point?>(null) }
    var currentError by remember { mutableStateOf<Double?>(null) }

    val w = sharedViewModel.spaceWidth.toFloatOrNull() ?: 10f
    val l = sharedViewModel.spaceLength.toFloatOrNull() ?: 10f
    val s = sharedViewModel.gridSpacing.toFloatOrNull() ?: 2f

    var lastUiBleHash by remember { mutableLongStateOf(0L) }

    LaunchedEffect(liveDevices, activeFingerprints, kValue, useWknn) {
        if (activeFingerprints.isNotEmpty() && sharedViewModel.selectedDevices.size >= 3) {
            var currentHash = 0L
            for (mac in sharedViewModel.selectedDevices.keys) {
                val device = liveDevices.find { it.macAddress == mac }
                if (device != null) currentHash += device.lastSeen
            }

            if (currentHash != lastUiBleHash && currentHash != 0L) {
                lastUiBleHash = currentHash
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
    }

    val displayPosition = when (smoothingStrategy) {
        1 -> emaSmoothedPosition
        2 -> fusedPosition ?: rawPosition
        else -> rawPosition
    }

    LaunchedEffect(displayPosition, groundTruthPoint) {
        currentError = if (displayPosition != null && groundTruthPoint != null) Math.hypot(displayPosition.x - groundTruthPoint!!.x, displayPosition.y - groundTruthPoint!!.y) else null
    }

    var globalPlannedPath by remember { mutableStateOf<List<Point>>(emptyList()) }
    var lastTarget by remember { mutableStateOf<Point?>(null) }
    var lastObstaclesHash by remember { mutableIntStateOf(0) }
    val OFF_ROUTE_THRESHOLD = 1.0

    LaunchedEffect(displayPosition, sharedViewModel.navigationTarget, sharedViewModel.obstacles) {
        val start = displayPosition
        val target = sharedViewModel.navigationTarget
        val currentObsHash = sharedViewModel.obstacles.hashCode()

        if (start == null || target == null) {
            globalPlannedPath = emptyList()
            sharedViewModel.currentPath = emptyList()
            lastTarget = target
            lastObstaclesHash = currentObsHash
            return@LaunchedEffect
        }

        var needsRecalculate = false

        if (globalPlannedPath.isEmpty() || target != lastTarget) needsRecalculate = true
        if (currentObsHash != lastObstaclesHash) needsRecalculate = true

        var closestIndex = 0

        if (!needsRecalculate) {
            var minDistance = Double.MAX_VALUE
            val searchWindow = kotlin.math.min(globalPlannedPath.size - 1, 3).coerceAtLeast(1)

            for (i in 0 until searchWindow) {
                val v = globalPlannedPath[i]
                val w_pt = globalPlannedPath[i + 1]

                val l2 = (w_pt.x - v.x) * (w_pt.x - v.x) + (w_pt.y - v.y) * (w_pt.y - v.y)
                val dist = if (l2 == 0.0) {
                    Math.hypot(start.x - v.x, start.y - v.y)
                } else {
                    val t = (((start.x - v.x) * (w_pt.x - v.x) + (start.y - v.y) * (w_pt.y - v.y)) / l2).coerceIn(0.0, 1.0)
                    val projX = v.x + t * (w_pt.x - v.x)
                    val projY = v.y + t * (w_pt.y - v.y)
                    Math.hypot(start.x - projX, start.y - projY)
                }

                if (dist < minDistance) {
                    minDistance = dist
                    closestIndex = i
                }
            }

            if (minDistance > OFF_ROUTE_THRESHOLD) {
                needsRecalculate = true
            }
        }

        if (needsRecalculate) {
            withContext(Dispatchers.Default) {
                val path = pathfinder.findPath(
                    start = start, target = target,
                    spaceWidth = w.toDouble(), spaceLength = l.toDouble(),
                    gridResolution = s.toDouble(),
                    obstacles = sharedViewModel.obstacles.map { Point(it.x, it.y) }
                )
                globalPlannedPath = path
                sharedViewModel.currentPath = path
            }
            lastTarget = target
            lastObstaclesHash = currentObsHash
        } else {
            if (closestIndex > 0) {
                globalPlannedPath = globalPlannedPath.drop(closestIndex)
                closestIndex = 0
            }

            val visualPath = mutableListOf<Point>()
            visualPath.add(start)
            for (i in closestIndex + 1 until globalPlannedPath.size) {
                visualPath.add(globalPlannedPath[i])
            }
            sharedViewModel.currentPath = visualPath
        }
    }

    // 🌟 核心算法重构：基于多段线折线累加的【真实行走里程】计算
    val distanceToTarget = remember(sharedViewModel.currentPath, displayPosition, sharedViewModel.navigationTarget) {
        val path = sharedViewModel.currentPath
        if (path.size > 1) {
            var dist = 0.0
            // 遍历所有途经节点，累加真实物理折线距离
            for (i in 0 until path.size - 1) {
                dist += Math.hypot(path[i + 1].x - path[i].x, path[i + 1].y - path[i].y)
            }
            dist
        } else if (displayPosition != null && sharedViewModel.navigationTarget != null) {
            // 降级保护：在寻路耗时或无路径时，临时显示直线距离
            Math.hypot(displayPosition.x - sharedViewModel.navigationTarget!!.x, displayPosition.y - sharedViewModel.navigationTarget!!.y)
        } else null
    }

    val gridCoordinates = remember(w, l, s) {
        val pts = mutableListOf<Point>(); var x = 0f
        while (x <= w) { var y = 0f; while (y <= l) { pts.add(Point(x.toDouble(), y.toDouble())); y += s }; x += s }
        pts
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600
    var lastDrawnCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val mapContent = @Composable {
        Box(
            modifier = Modifier
                .padding(all = 16.dp)
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
                    currentHeading = currentHeading,
                    targetPoint = if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) sharedViewModel.navigationTarget else null,
                    obstacles = sharedViewModel.obstacles,
                    currentPath = if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) sharedViewModel.currentPath else emptyList(),
                    gridResolution = s.toDouble(),
                    editMode = sharedViewModel.currentInteractionState,
                    isMapFollowingMode = sharedViewModel.isMapFollowingModeEnabled,

                    onPointSelected = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.EVALUATION_MODE ||
                            sharedViewModel.currentInteractionState == InteractionState.BENCHMARK_MODE) {
                            groundTruthPoint = pt
                        }
                    },
                    onTargetSelected = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) sharedViewModel.navigationTarget = pt
                    },
                    checkIsObstacle = { pt ->
                        val c = (pt.x / s).toInt()
                        val r = (pt.y / s).toInt()
                        sharedViewModel.obstacles.any { it.id == "OBS_${c}_${r}" }
                    },
                    onToggleObstacle = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.OBSTACLE_MODE) {
                            sharedViewModel.toggleObstacle(pt, s.toDouble())
                        }
                    },
                    onDragObstacle = { pt, isErasing ->
                        if (sharedViewModel.currentInteractionState == InteractionState.OBSTACLE_MODE) {
                            val c = (pt.x / s).toInt()
                            val r = (pt.y / s).toInt()
                            if (lastDrawnCell != c to r) {
                                lastDrawnCell = c to r
                                sharedViewModel.toggleObstacle(pt, s.toDouble(), isErasing)
                            }
                        }
                    }
                )
            }
        }
    }

    val controlContent = @Composable {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(modifier = Modifier.padding(20.dp)) {

                Text("系统交互模式:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                SegmentedButton(
                    options = InteractionState.values().map { it.title },
                    selectedIndex = InteractionState.values().indexOf(sharedViewModel.currentInteractionState),
                    onOptionSelected = { sharedViewModel.currentInteractionState = InteractionState.values()[it] }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when (sharedViewModel.currentInteractionState) {
                    InteractionState.EVALUATION_MODE -> {
                        if (sharedViewModel.isAdvancedModeEnabled) {
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
                        }

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("基准真值 (地图选定)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (groundTruthPoint != null) "X: ${String.format("%.2f", groundTruthPoint!!.x)}, Y: ${String.format("%.2f", groundTruthPoint!!.y)}" else "尚未选定",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if(groundTruthPoint != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    InteractionState.NAVIGATION_MODE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            if (sharedViewModel.navigationTarget == null) {
                                Text("请在地图上点击选定导航终点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Text("因局部磁场偏差，PDR罗盘可能会发生漂移", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(onClick = { fusionEngine.calibrateHeading(); Toast.makeText(context, "罗盘已对齐正方向", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📌 对准地图正方向后点击校准", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    InteractionState.OBSTACLE_MODE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("避障与墙体编辑", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("点击或拖动地图以绘制/擦除障碍物", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

                            Spacer(modifier = Modifier.height(16.dp))
                            if (sharedViewModel.obstacles.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { showClearObstaclesConfirm = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("一键清空所有墙体", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    InteractionState.BENCHMARK_MODE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("环境上下文标签", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            SegmentedButton(options = sharedViewModel.envLabels, selectedIndex = sharedViewModel.envLabels.indexOf(sharedViewModel.currentEnvLabel), onOptionSelected = { sharedViewModel.currentEnvLabel = sharedViewModel.envLabels[it] })
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            var benchmarkModeTab by remember { mutableIntStateOf(0) }
                            SegmentedButton(options = listOf("定点空间", "定点时间", "自由轨迹"), selectedIndex = benchmarkModeTab, onOptionSelected = { benchmarkModeTab = it })
                            Spacer(modifier = Modifier.height(16.dp))

                            when (benchmarkModeTab) {
                                0 -> {
                                    if (groundTruthPoint == null) {
                                        Text("请在地图上精准点击你所在的物理坐标点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("物理真值 X: ${String.format("%.2f", groundTruthPoint!!.x)}, Y: ${String.format("%.2f", groundTruthPoint!!.y)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        if (sharedViewModel.isBenchmarking) {
                                            LinearProgressIndicator(progress = { benchmarkProgress }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)))
                                            Text("正在等待物理信号更新... ${(benchmarkProgress * 50).toInt()}/50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top=8.dp))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Button(onClick = { benchmarkLogger.cancelLogging(); sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f }, modifier = Modifier.fillMaxWidth().height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("取消采样", fontWeight = FontWeight.Bold) }
                                        } else {
                                            Button(onClick = { sharedViewModel.isBenchmarking = true; benchmarkLogger.startLogging(coroutineScope = coroutineScope, truth = groundTruthPoint!!, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, envLabel = sharedViewModel.currentEnvLabel, targetSamples = 50, onProgress = { benchmarkProgress = it }, onComplete = { sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f; benchmarkLogger.stopAndExport(context) }) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("开始定额采样 (50次)", fontWeight = FontWeight.Bold) }
                                        }
                                    }
                                }
                                1 -> {
                                    Text("将手机静置于桌面，用于收集滤波平滑折线图", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    if (sharedViewModel.isBenchmarking) {
                                        LinearProgressIndicator(progress = { benchmarkProgress }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)))
                                        Text("正在静默采集中... ${(benchmarkProgress * 60).toInt()} / 60 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top=8.dp))
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(onClick = { benchmarkLogger.cancelLogging(); sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f }, modifier = Modifier.fillMaxWidth().height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("取消采集", fontWeight = FontWeight.Bold) }
                                    } else {
                                        Button(onClick = { sharedViewModel.isBenchmarking = true; benchmarkProgress = 0f; benchmarkLogger.startTimeBoundLogging(coroutineScope = coroutineScope, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, envLabel = sharedViewModel.currentEnvLabel, durationSeconds = 60, onProgress = { benchmarkProgress = it }, onComplete = { sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f; benchmarkLogger.stopAndExport(context) }) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("▶ 开始 60 秒读条采集", fontWeight = FontWeight.Bold) }
                                    }
                                }
                                2 -> {
                                    if (sharedViewModel.isContinuousLogging) {
                                        Text("🔴 正在以 2Hz 频率连续记录轨迹...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { sharedViewModel.isContinuousLogging = false; benchmarkLogger.stopAndExport(context) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("⏹ 停止走动并导出轨迹", fontWeight = FontWeight.Bold) }
                                    } else {
                                        Text("无需点选真值，开启后匀速走动即可画出轨迹", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { sharedViewModel.isContinuousLogging = true; benchmarkLogger.startContinuousLogging(coroutineScope = coroutineScope, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, envLabel = sharedViewModel.currentEnvLabel) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("▶ 开始录制不限时轨迹", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val topBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f).compositeOver(MaterialTheme.colorScheme.surface)
    val scrolledTopBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surface)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("定位引擎", fontWeight = FontWeight.Bold) },
                actions = {
                    displayPosition?.let { pos ->
                        val strategyName = listOf("RAW", "EMA", "PDR")[smoothingStrategy]

                        // 🌟 文案替换为“路程”，代表实际行走路径长度
                        val extraInfo = when (sharedViewModel.currentInteractionState) {
                            InteractionState.NAVIGATION_MODE -> distanceToTarget?.let { " | 路程: ${String.format("%.1f", it)}m" } ?: ""
                            InteractionState.EVALUATION_MODE -> currentError?.let { " | 误差: ${String.format("%.2f", it)}m" } ?: ""
                            else -> ""
                        }

                        val statusIndicatorColor = when (sharedViewModel.currentInteractionState) {
                            InteractionState.EVALUATION_MODE -> if ((currentError ?: 0.0) < 1.5) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            InteractionState.NAVIGATION_MODE -> if (distanceToTarget != null) Color(0xFF4CAF50) else Color.Gray
                            else -> Color(0xFF4CAF50)
                        }

                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusIndicatorColor))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$strategyName | X: ${String.format("%.2f", pos.x)}, Y: ${String.format("%.2f", pos.y)}$extraInfo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarBgColor, scrolledContainerColor = scrolledTopBarBgColor, titleContentColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (isWideScreen) {
                Row(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                    Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) { mapContent() }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) { controlContent() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { mapContent() }
                    controlContent()
                    Spacer(modifier = Modifier.height(bottomPadding + 24.dp))
                }
            }
        }
    }

    if (showClearObstaclesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearObstaclesConfirm = false },
            title = { Text("清空所有墙体", fontWeight = FontWeight.Bold) },
            text = { Text("确定要清空地图上绘制的所有避障墙体吗？该操作不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    sharedViewModel.clearAllObstacles()
                    showClearObstaclesConfirm = false
                    Toast.makeText(context, "避障墙体已清空", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearObstaclesConfirm = false }) { Text("取消") } }
        )
    }
}