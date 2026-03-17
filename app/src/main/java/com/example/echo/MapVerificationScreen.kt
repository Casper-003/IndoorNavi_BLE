package com.example.echo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapVerificationScreen(
    rawPolygon: List<Point>,
    sharedViewModel: SharedViewModel,
    onSaveSuccess: () -> Unit,
    onDiscard: () -> Unit,
    onRescan: () -> Unit = onDiscard  // 重新扫描：丢弃当前结果并返回 AR 界面
) {
    val context = LocalContext.current

    var editedPolygon by remember { mutableStateOf(rawPolygon) }

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

                            sharedViewModel.createNewMap(
                                name = "AR 扫描图_${System.currentTimeMillis() % 10000}",
                                w = finalWidth,
                                l = finalLength,
                                isAr = true,
                                polygon = normalizedPolygon
                            )
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

                PolygonEditorCanvas(
                    polygon = editedPolygon,
                    onPolygonChanged = { editedPolygon = it },
                    modifier = Modifier.fillMaxSize()
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
                }
            }
        }
    }
}

// =================================================================================
// 组件：交互式多边形编辑器 (修复手势中断 Bug 版)
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

    // 🌟 核心修复 2：使用最新的状态引用，供给内部的手势监听器使用
    val currentPolygon by rememberUpdatedState(polygon)

    Canvas(
        // 🌟 核心修复 1：将 key 设为 Unit！防止每次拖拽更新多边形时，强制打断用户的拖拽手势！
        modifier = modifier.pointerInput(Unit) {
            var activeDragIndex: Int? = null

            detectDragGestures(
                onDragStart = { startOffset ->
                    val hitRadiusSq = 60f * 60f
                    var minDstSq = Float.MAX_VALUE
                    var closestIdx = -1

                    for (i in currentPolygon.indices) {
                        val px = offsetX + (currentPolygon[i].x.toFloat() * scale)
                        val py = offsetY + (currentPolygon[i].y.toFloat() * scale)
                        val dstSq = (startOffset.x - px) * (startOffset.x - px) + (startOffset.y - py) * (startOffset.y - py)
                        if (dstSq < hitRadiusSq && dstSq < minDstSq) {
                            minDstSq = dstSq
                            closestIdx = i
                        }
                    }
                    if (closestIdx != -1) activeDragIndex = closestIdx
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    activeDragIndex?.let { idx ->
                        val dxPhys = dragAmount.x / scale
                        val dyPhys = dragAmount.y / scale
                        val newPolygon = currentPolygon.toMutableList()
                        // 将像素偏移转化回物理米数
                        newPolygon[idx] = Point(newPolygon[idx].x + dxPhys.toDouble(), newPolygon[idx].y + dyPhys.toDouble())
                        onPolygonChanged(newPolygon)
                    }
                },
                onDragEnd = { activeDragIndex = null },
                onDragCancel = { activeDragIndex = null }
            )
        }
    ) {
        val minX = polygon.minOf { it.x }
        val maxX = polygon.maxOf { it.x }
        val minY = polygon.minOf { it.y }
        val maxY = polygon.maxOf { it.y }
        val rangeX = (maxX - minX).coerceAtLeast(1.0)
        val rangeY = (maxY - minY).coerceAtLeast(1.0)

        val rangeXFloat = rangeX.toFloat()
        val rangeYFloat = rangeY.toFloat()
        val minXFloat = minX.toFloat()
        val maxXFloat = maxX.toFloat()
        val minYFloat = minY.toFloat()
        val maxYFloat = maxY.toFloat()

        scale = minOf(size.width / rangeXFloat, size.height / rangeYFloat) * 0.8f
        offsetX = size.width / 2f - ((minXFloat + maxXFloat) / 2f * scale)
        offsetY = size.height / 2f - ((minYFloat + maxYFloat) / 2f * scale)

        fun toPx(p: Point): Offset = Offset(
            x = offsetX + (p.x.toFloat() * scale),
            y = offsetY + (p.y.toFloat() * scale)
        )

        val path = Path().apply {
            moveTo(toPx(polygon.first()).x, toPx(polygon.first()).y)
            for (i in 1 until polygon.size) { lineTo(toPx(polygon[i]).x, toPx(polygon[i]).y) }
            close()
        }

        drawPath(path = path, color = Color(0xFF1976D2).copy(alpha = 0.8f), style = Stroke(width = 4.dp.toPx()))
        drawPath(path = path, color = Color(0xFF1976D2).copy(alpha = 0.15f))

        polygon.forEach { pt ->
            drawCircle(color = Color.White, radius = 8.dp.toPx(), center = toPx(pt))
            drawCircle(color = Color(0xFF1976D2), radius = 6.dp.toPx(), center = toPx(pt))
        }
    }
}

// =================================================================================
// 核心算法：基于曼哈顿距离的启发式正交化算法 (AI 直角化)
// =================================================================================
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