package com.example.indoornavi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun FingerprintManagerScreen(sharedViewModel: SharedViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { coroutineScope.launch { val data = importCsvFromUri(context, uri); if (data != null) sharedViewModel.updateRecordedPoints(data) } }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) { coroutineScope.launch { exportCsvToUri(context, uri, sharedViewModel.recordedPoints) } }
    }

    AnimatedContent(
        targetState = sharedViewModel.isCollectingMode,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(animationSpec = tween(400)) { width -> width } + fadeIn(animationSpec = tween(400))) togetherWith (slideOutHorizontally(animationSpec = tween(400)) { width -> -width / 3 } + fadeOut(animationSpec = tween(400)))
            } else {
                (slideInHorizontally(animationSpec = tween(400)) { width -> -width / 3 } + fadeIn(animationSpec = tween(400))) togetherWith (slideOutHorizontally(animationSpec = tween(400)) { width -> width } + fadeOut(animationSpec = tween(400)))
            }
        },
        label = "CollectionModeTransition"
    ) { mode ->
        if (mode) {
            CollectionMapScreen(
                sharedViewModel = sharedViewModel,
                onBackClick = { sharedViewModel.isCollectingMode = false }
            )
        } else {
            ConfigScreen(
                sharedViewModel = sharedViewModel,
                onEnterCollection = { sharedViewModel.isCollectingMode = true },
                onImportClick = { importLauncher.launch(arrayOf("text/csv")) },
                onExportClick = { exportLauncher.launch("IndoorNavi_Fingerprints.csv") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    sharedViewModel: SharedViewModel,
    onEnterCollection: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit
) {
    val w = sharedViewModel.spaceWidth.toFloatOrNull() ?: 0f
    val l = sharedViewModel.spaceLength.toFloatOrNull() ?: 0f
    val s = sharedViewModel.gridSpacing.toFloatOrNull() ?: 1f
    val dotsX = if (s > 0) (w / s).toInt() + 1 else 0
    val dotsY = if (s > 0) (l / s).toInt() + 1 else 0
    val totalDots = dotsX * dotsY

    val evalText = when {
        totalDots < 10 -> "警告：点位过少，定位精度可能极差！\n建议缩小间距。"
        totalDots > 200 -> "警告：点位过多，工作量极大！\n建议增大间距。"
        else -> "系统评估：当前分辨率需采集 $totalDots 个点。\n精度与效率完美平衡！"
    }
    val evalColor = if (totalDots in 10..200) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("指纹管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("地图参数配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = sharedViewModel.spaceWidth, onValueChange = { sharedViewModel.spaceWidth = it }, label = { Text("宽/m") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = sharedViewModel.spaceLength, onValueChange = { sharedViewModel.spaceLength = it }, label = { Text("长/m") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = sharedViewModel.gridSpacing, onValueChange = { sharedViewModel.gridSpacing = it }, label = { Text("间距/m") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = RoundedCornerShape(12.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                    Text(text = evalText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = evalColor)
                }
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onEnterCollection,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("进入雷达采集系统", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("已入库指纹点位", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("${sharedViewModel.recordedPoints.size} 个", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onImportClick, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入 CSV")
                    }
                    OutlinedButton(onClick = onExportClick, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出备份")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(120.dp))
    }
}

@Composable
fun CollectionMapScreen(
    sharedViewModel: SharedViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    val w = sharedViewModel.spaceWidth.toFloatOrNull() ?: 10f
    val l = sharedViewModel.spaceLength.toFloatOrNull() ?: 10f
    val s = sharedViewModel.gridSpacing.toFloatOrNull() ?: 2f
    val gridCoordinates = remember(w, l, s) {
        val pts = mutableListOf<Point>(); var x = 0f
        while (x <= w) { var y = 0f; while (y <= l) { pts.add(Point(x.toDouble(), y.toDouble())); y += s }; x += s }
        pts
    }

    var selectedPoint by remember { mutableStateOf<Point?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    var collectionProgress by remember { mutableFloatStateOf(0f) }
    var accumulatedYaw by remember { mutableFloatStateOf(0f) }
    var lastYaw by remember { mutableStateOf<Float?>(null) }

    var showClearAllDialog by remember { mutableStateOf(false) }
    var lastTapPoint by remember { mutableStateOf<Point?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) { scanner.startScan(); onDispose { scanner.stopScan() } }

    DisposableEffect(isRecording, sharedViewModel.is360CollectionModeEnabled) {
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && rotationSensor != null) {
            accumulatedYaw = 0f; lastYaw = null; collectionProgress = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(rotationMatrix, orientation)

                        val currentYaw = orientation[0]
                        if (lastYaw != null) {
                            var delta = currentYaw - lastYaw!!
                            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                            if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                            accumulatedYaw += abs(delta)
                            collectionProgress = (accumulatedYaw / (2 * Math.PI).toFloat()).coerceIn(0f, 1f)
                        }
                        lastYaw = currentYaw
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager.unregisterListener(listener) }
        } else { onDispose {} }
    }

    LaunchedEffect(collectionProgress) {
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && collectionProgress >= 1f) {
            val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
            val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
            sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
            isRecording = false
            collectionProgress = 0f
        }
    }

    // 🌟 智能屏幕判定
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    // 🌟 雷达图组件提取
    val mapContent = @Composable {
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = isWideScreen) // 完美适应平板高度
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
        ) {
            InteractiveRadarMap(
                gridCoordinates = gridCoordinates,
                recordedPoints = sharedViewModel.recordedPoints.map { it.coordinate },
                selectedPoint = selectedPoint,
                predictedPoint = null,
                onPointSelected = { pt ->
                    val currentTime = System.currentTimeMillis()
                    if (pt == lastTapPoint && (currentTime - lastTapTime) < 300) {
                        if (sharedViewModel.recordedPoints.any { it.coordinate == pt }) {
                            sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != pt })
                            selectedPoint = null
                        }
                    } else { selectedPoint = pt }
                    lastTapPoint = pt
                    lastTapTime = currentTime
                }
            )
        }
    }

    // 🌟 控制台组件提取
    val controlContent = @Composable {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前选定坐标", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (selectedPoint != null) "X: ${selectedPoint!!.x}, Y: ${selectedPoint!!.y}" else "请在地图上选点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (selectedPoint != null) MaterialTheme.colorScheme.primary else Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (sharedViewModel.selectedDevices.isEmpty()) "❌ 未锁定基站" else if (sharedViewModel.is360CollectionModeEnabled) "↻ 360° 旋转采集模式" else "⚡ 极速单点模式", style = MaterialTheme.typography.labelMedium, color = if (sharedViewModel.selectedDevices.isEmpty()) MaterialTheme.colorScheme.error else Color.Gray)

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("双击地图已采集点可直接删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                        if (isRecording && sharedViewModel.is360CollectionModeEnabled) {
                            CircularProgressIndicator(progress = { 1f }, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            CircularProgressIndicator(progress = { collectionProgress }, color = MaterialTheme.colorScheme.primary, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            Text(text = "${(collectionProgress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        } else {
                            val canRecord = selectedPoint != null && sharedViewModel.selectedDevices.isNotEmpty()
                            FilledIconButton(
                                onClick = {
                                    if (canRecord) {
                                        isRecording = true
                                        if (!sharedViewModel.is360CollectionModeEnabled) {
                                            val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
                                            val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
                                            sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
                                            isRecording = false
                                        }
                                    }
                                },
                                enabled = canRecord,
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    imageVector = if (sharedViewModel.is360CollectionModeEnabled) Icons.Default.Refresh else Icons.Default.Add,
                                    contentDescription = "采集",
                                    modifier = Modifier.size(44.dp),
                                    tint = if (canRecord) MaterialTheme.colorScheme.onPrimary else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 🌟 全局页面容器
    Column(modifier = Modifier.fillMaxSize()) {

        // 固定顶部的返回和清空栏
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }
            Text("采集雷达", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            if (sharedViewModel.recordedPoints.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { showClearAllDialog = true },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("全部清空", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 🌟 响应式分发逻辑
        if (isWideScreen) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    mapContent()
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    controlContent()
                    Spacer(modifier = Modifier.height(180.dp))
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    mapContent()
                }
                controlContent()
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清空所有点位") },
            text = { Text("确定要清空地图上所有的指纹点位吗？该操作不可恢复。") },
            confirmButton = { Button(onClick = { sharedViewModel.updateRecordedPoints(emptyList()); showClearAllDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("全部清空") } },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("取消") } }
        )
    }
}