package com.example.echo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapVerificationScreen(
    rawPolygon: List<Point>,
    rawObstacles: List<Point> = emptyList(),
    sharedViewModel: SharedViewModel,
    onSaveSuccess: () -> Unit,
    onDiscard: () -> Unit,
    onRescan: () -> Unit = onDiscard  // 重新扫描：丢弃当前结果并返回 AR 界面
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var editedPolygon by remember { mutableStateOf(rawPolygon) }
    var showObstacles by remember { mutableStateOf(rawObstacles.isNotEmpty()) }

    // 旋转滑块状态（±180°，松开时烘焙进多边形坐标）
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    val snapAngles = remember { listOf(-180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f) }
    fun snapRotation(raw: Float): Float {
        val nearest = snapAngles.minByOrNull { abs(it - raw) } ?: raw
        return if (abs(nearest - raw) <= 4f) nearest else raw
    }

    // 90° 旋转动画：Animatable 精确控制，动画结束后 snap 归零再烘焙，避免视觉反跳
    val snap90Anim = remember { Animatable(0f) }
    fun triggerSnap90() {
        coroutineScope.launch {
            snap90Anim.animateTo(90f, tween(300))
            editedPolygon = rotatePolygon90(editedPolygon)
            snap90Anim.snapTo(0f)   // 瞬间归零，坐标已烘焙，视觉无跳变
        }
    }

    var bgImageUri by remember { mutableStateOf<Uri?>(null) }
    var bgScale by remember { mutableFloatStateOf(1f) }
    var bgOffset by remember { mutableStateOf(Offset.Zero) }
    var bgRotation by remember { mutableFloatStateOf(0f) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bgImageUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地图轮廓精修", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDiscard) { Icon(Icons.Default.Close, contentDescription = "放弃") }
                },
                actions = {
                    Button(
                        onClick = {
                            if (editedPolygon.size < 3) return@Button

                            val minX = editedPolygon.minOf { it.x }
                            val maxX = editedPolygon.maxOf { it.x }
                            val minY = editedPolygon.minOf { it.y }
                            val maxY = editedPolygon.maxOf { it.y }

                            val normalizedPolygon = editedPolygon.map { Point(it.x - minX, it.y - minY) }
                            val finalWidth = maxX - minX
                            val finalLength = maxY - minY

                            val prefix = "AR 扫描图_"
                            val nextIndex = sharedViewModel.mapList
                                .mapNotNull { it.mapName.removePrefix(prefix).toIntOrNull() }
                                .maxOrNull()?.plus(1) ?: 1
                            sharedViewModel.createNewMap(
                                name = "$prefix$nextIndex",
                                w = finalWidth,
                                l = finalLength,
                                isAr = true,
                                polygon = normalizedPolygon,
                                bgImageUri = bgImageUri?.toString() ?: ""
                            )
                            // 障碍物坐标与多边形同步归一化，写入 DB
                            if (rawObstacles.isNotEmpty()) {
                                val normalizedObstacles = rawObstacles.map {
                                    Point(it.x - minX, it.y - minY)
                                }
                                // createNewMap 内部 switchActiveMap 是异步的，
                                // 通过 currentActiveMapId 状态流感知新 mapId
                                coroutineScope.launch {
                                    // 等待 currentActiveMapId 变化（最多等 2s）
                                    var waited = 0
                                    while (sharedViewModel.currentActiveMapId.value == null && waited < 2000) {
                                        kotlinx.coroutines.delay(50); waited += 50
                                    }
                                    sharedViewModel.currentActiveMapId.value?.let { mapId ->
                                        sharedViewModel.saveObstaclesForMap(mapId, normalizedObstacles)
                                    }
                                }
                            }
                            onSaveSuccess()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text("保存并建图", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 预览框：固定占满剩余空间，地图在内部按比例 fit 缩放
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
            ) {
                if (bgImageUri != null) {
                    AsyncImage(
                        model = bgImageUri,
                        contentDescription = "户型底图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = bgScale,
                                scaleY = bgScale,
                                translationX = bgOffset.x,
                                translationY = bgOffset.y,
                                rotationZ = bgRotation
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    bgScale = (bgScale * zoom).coerceIn(0.1f, 10f)
                                    bgOffset += pan
                                    bgRotation += rotation
                                }
                            }
                    )
                } else {
                    Text(
                        "双指缩放平移可调整底图\n拖拽蓝点可微调墙角",
                        color = Color.Gray.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // 网格层：固定不随旋转转动
                GridCanvas(
                    polygon = editedPolygon,
                    modifier = Modifier.fillMaxSize()
                )

                // 多边形编辑层：随滑块和90°动画旋转
                PolygonEditorCanvas(
                    polygon = editedPolygon,
                    onPolygonChanged = { editedPolygon = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotationDeg + snap90Anim.value }
                )

                // 障碍物叠层：橙色方格，与多边形同步旋转
                if (showObstacles && rawObstacles.isNotEmpty() && editedPolygon.size >= 2) {
                    val obstacleColor = Color(0xFFFF7043).copy(alpha = 0.7f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = rotationDeg + snap90Anim.value }
                    ) {
                        val minX = editedPolygon.minOf { it.x }
                        val maxX = editedPolygon.maxOf { it.x }
                        val minY = editedPolygon.minOf { it.y }
                        val maxY = editedPolygon.maxOf { it.y }
                        val rangeX = (maxX - minX).coerceAtLeast(0.01)
                        val rangeY = (maxY - minY).coerceAtLeast(0.01)
                        val scale = minOf(size.width / rangeX.toFloat(), size.height / rangeY.toFloat()) * 0.8f
                        val offX = size.width / 2f - ((minX + maxX) / 2f * scale).toFloat()
                        val offY = size.height / 2f - ((minY + maxY) / 2f * scale).toFloat()
                        val cellPx = 0.15f * scale
                        rawObstacles.forEach { obs ->
                            val cx = offX + obs.x.toFloat() * scale
                            val cy = offY + obs.y.toFloat() * scale
                            drawRect(
                                color = obstacleColor,
                                topLeft = Offset(cx - cellPx / 2, cy - cellPx / 2),
                                size = androidx.compose.ui.geometry.Size(cellPx, cellPx)
                            )
                        }
                    }
                }
            }

            // 旋转微调滑块
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "旋转微调",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${rotationDeg.toInt()}°",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (rotationDeg == 0f) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = rotationDeg,
                    onValueChange = { raw ->
                        rotationDeg = snapRotation(raw)
                    },
                    onValueChangeFinished = {
                        // 松开时把旋转烘焙进坐标，滑块归零
                        if (rotationDeg != 0f) {
                            editedPolygon = rotatePolygonByDeg(editedPolygon, rotationDeg)
                            rotationDeg = 0f
                        }
                    },
                    valueRange = -180f..180f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { triggerSnap90() },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = "顺时针旋转 90°", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("旋转90°", style = MaterialTheme.typography.labelSmall)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { imagePicker.launch("image/*") },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "导入户型底图", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("导入底图", style = MaterialTheme.typography.labelSmall)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                bgScale = 1f
                                bgOffset = Offset.Zero
                                bgRotation = 0f
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Default.FilterCenterFocus, contentDescription = "复位底图")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("复位底图", style = MaterialTheme.typography.labelSmall)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                editedPolygon = orthogonalizePolygon(editedPolygon)
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "AI 规整", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("一键直角化", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onRescan,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "重新扫描", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("重新扫描", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }

                    if (rawObstacles.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledIconButton(
                                onClick = { showObstacles = !showObstacles },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (showObstacles) Color(0xFFFF7043)
                                                     else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "显示/隐藏障碍物",
                                    tint = if (showObstacles) Color.White
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "障碍(${rawObstacles.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (showObstacles) Color(0xFFFF7043)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// =================================================================================
// 网格层：固定在旋转层外面，不随多边形旋转
// 自动计算大格间距（约占画布 1/5~1/8），大格内画 2 条细虚线分 3 段
// =================================================================================
@Composable
fun GridCanvas(polygon: List<Point>, modifier: Modifier) {
    val mainColor = Color(0xFF90A4AE).copy(alpha = 0.45f)
    val subColor  = Color(0xFF90A4AE).copy(alpha = 0.18f)
    val subDash   = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        // 根据画布尺寸计算合适的大格像素间距（目标：屏幕上出现 5~8 条格线）
        val targetLines = 6
        val rawSpacingPx = minOf(size.width, size.height) / targetLines
        // 取整到 "好看" 的值：10, 20, 25, 40, 50, 80, 100 ... px
        val niceSteps = listOf(20f, 25f, 40f, 50f, 60f, 80f, 100f, 120f, 160f, 200f)
        val majorPx = niceSteps.minByOrNull { kotlin.math.abs(it - rawSpacingPx) } ?: rawSpacingPx
        val minorPx = majorPx / 3f   // 每格内 2 条分割线

        // 主格线（实线，稍粗）
        var x = 0f
        while (x <= size.width) {
            drawLine(mainColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += majorPx
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(mainColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += majorPx
        }

        // 次格线（虚线，细）
        var sx = minorPx
        while (sx <= size.width) {
            if (sx % majorPx > 1f) // 跳过与主格重合的线
                drawLine(subColor, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 0.5f, pathEffect = subDash)
            sx += minorPx
        }
        var sy = minorPx
        while (sy <= size.height) {
            if (sy % majorPx > 1f)
                drawLine(subColor, Offset(0f, sy), Offset(size.width, sy), strokeWidth = 0.5f, pathEffect = subDash)
            sy += minorPx
        }
    }
}

// =================================================================================
// 组件：交互式多边形编辑器（无网格，网格由外层 GridCanvas 负责）
// =================================================================================
@Composable
fun PolygonEditorCanvas(
    polygon: List<Point>,
    onPolygonChanged: (List<Point>) -> Unit,
    modifier: Modifier
) {
    if (polygon.isEmpty()) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val currentPolygon by rememberUpdatedState(polygon)

    // 网格吸附：将物理坐标吸附到最近的 0.5m 网格点（距离 ≤ 0.15m 时触发）
    val gridSpacing = 0.5
    val snapThreshold = 0.15
    fun snapToGrid(x: Double, y: Double): Point {
        val snappedX = Math.round(x / gridSpacing) * gridSpacing
        val snappedY = Math.round(y / gridSpacing) * gridSpacing
        val dx = snappedX - x; val dy = snappedY - y
        return if (sqrt(dx * dx + dy * dy) <= snapThreshold) Point(snappedX, snappedY)
        else Point(x, y)
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            var activeDragIndex: Int? = null
            detectDragGestures(
                onDragStart = { startOffset ->
                    val hitRadiusSq = 60f * 60f
                    var minDstSq = Float.MAX_VALUE; var closestIdx = -1
                    for (i in currentPolygon.indices) {
                        val px = offsetX + (currentPolygon[i].x.toFloat() * scale)
                        val py = offsetY + (currentPolygon[i].y.toFloat() * scale)
                        val dstSq = (startOffset.x - px).let { it * it } + (startOffset.y - py).let { it * it }
                        if (dstSq < hitRadiusSq && dstSq < minDstSq) { minDstSq = dstSq; closestIdx = i }
                    }
                    if (closestIdx != -1) activeDragIndex = closestIdx
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    activeDragIndex?.let { idx ->
                        val newPolygon = currentPolygon.toMutableList()
                        val rawX = newPolygon[idx].x + dragAmount.x / scale
                        val rawY = newPolygon[idx].y + dragAmount.y / scale
                        newPolygon[idx] = snapToGrid(rawX, rawY)
                        onPolygonChanged(newPolygon)
                    }
                },
                onDragEnd = { activeDragIndex = null },
                onDragCancel = { activeDragIndex = null }
            )
        }
    ) {
        val minX = polygon.minOf { it.x }; val maxX = polygon.maxOf { it.x }
        val minY = polygon.minOf { it.y }; val maxY = polygon.maxOf { it.y }
        val rangeX = (maxX - minX).coerceAtLeast(1.0)
        val rangeY = (maxY - minY).coerceAtLeast(1.0)

        scale = minOf(size.width / rangeX.toFloat(), size.height / rangeY.toFloat()) * 0.8f
        offsetX = size.width / 2f - ((minX + maxX).toFloat() / 2f * scale)
        offsetY = size.height / 2f - ((minY + maxY).toFloat() / 2f * scale)

        fun toPx(p: Point) = Offset(offsetX + p.x.toFloat() * scale, offsetY + p.y.toFloat() * scale)

        val path = Path().apply {
            moveTo(toPx(polygon.first()).x, toPx(polygon.first()).y)
            for (i in 1 until polygon.size) lineTo(toPx(polygon[i]).x, toPx(polygon[i]).y)
            close()
        }
        drawPath(path, Color(0xFF1976D2).copy(alpha = 0.15f))
        drawPath(path, Color(0xFF1976D2).copy(alpha = 0.8f), style = Stroke(width = 4.dp.toPx()))

        polygon.forEach { pt ->
            drawCircle(Color.White, 8.dp.toPx(), toPx(pt))
            drawCircle(Color(0xFF1976D2), 6.dp.toPx(), toPx(pt))
        }
    }
}

// =================================================================================
// 旋转多边形任意角度（绕中心，度数，顺时针为正）
// =================================================================================
fun rotatePolygonByDeg(polygon: List<Point>, degrees: Float): List<Point> {
    if (polygon.isEmpty()) return polygon
    val rad = Math.toRadians(degrees.toDouble())
    val cosA = cos(rad); val sinA = sin(rad)
    val cx = (polygon.maxOf { it.x } + polygon.minOf { it.x }) / 2.0
    val cy = (polygon.maxOf { it.y } + polygon.minOf { it.y }) / 2.0
    return polygon.map { p ->
        val dx = p.x - cx; val dy = p.y - cy
        Point(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
    }
}

// =================================================================================
// 旋转多边形 90°（绕中心顺时针）
// =================================================================================
fun rotatePolygon90(polygon: List<Point>): List<Point> {
    if (polygon.isEmpty()) return polygon
    val cx = (polygon.maxOf { it.x } + polygon.minOf { it.x }) / 2.0
    val cy = (polygon.maxOf { it.y } + polygon.minOf { it.y }) / 2.0
    // 顺时针 90°：(dx, dy) → (dy, -dx)
    return polygon.map { p ->
        val dx = p.x - cx
        val dy = p.y - cy
        Point(cx + dy, cy - dx)
    }
}
fun orthogonalizePolygon(rawPoints: List<Point>): List<Point> {
    if (rawPoints.size < 3) return rawPoints

    var maxEdgeLengthSq = -1.0
    var mainAngle = 0.0

    for (i in rawPoints.indices) {
        val p1 = rawPoints[i]
        val p2 = rawPoints[(i + 1) % rawPoints.size]
        val distSq = (p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y)
        if (distSq > maxEdgeLengthSq) {
            maxEdgeLengthSq = distSq
            mainAngle = atan2(p2.y - p1.y, p2.x - p1.x)
        }
    }

    val pivot = rawPoints.first()
    val rotatedPoints = rawPoints.map { pt ->
        val dx = pt.x - pivot.x
        val dy = pt.y - pivot.y
        val rx = dx * cos(-mainAngle) - dy * sin(-mainAngle)
        val ry = dx * sin(-mainAngle) + dy * cos(-mainAngle)
        Point(rx + pivot.x, ry + pivot.y)
    }

    val snappedPoints = mutableListOf<Point>()
    snappedPoints.add(rotatedPoints.first())

    for (i in 1 until rotatedPoints.size) {
        val prev = snappedPoints.last()
        val curr = rotatedPoints[i]
        val dx = curr.x - prev.x
        val dy = curr.y - prev.y

        if (abs(dx) > abs(dy)) {
            snappedPoints.add(Point(curr.x, prev.y))
        } else {
            snappedPoints.add(Point(prev.x, curr.y))
        }
    }

    val finalPoints = snappedPoints.map { pt ->
        val dx = pt.x - pivot.x
        val dy = pt.y - pivot.y
        val rx = dx * cos(mainAngle) - dy * sin(mainAngle)
        val ry = dx * sin(mainAngle) + dy * cos(mainAngle)
        Point(rx + pivot.x, ry + pivot.y)
    }

    return finalPoints
}