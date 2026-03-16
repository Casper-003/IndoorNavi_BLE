package com.example.echo

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun InteractiveRadarMap(
    modifier: Modifier = Modifier,
    gridCoordinates: List<Point>,
    recordedPoints: List<Point>,
    selectedPoint: Point? = null,
    predictedPoint: Point? = null,
    currentHeading: Float? = null,
    targetPoint: Point? = null,
    obstacles: List<ObstacleEntity> = emptyList(),
    currentPath: List<Point> = emptyList(),
    gridResolution: Double = 1.0,
    editMode: InteractionState = InteractionState.NAVIGATION_MODE,
    isMapFollowingMode: Boolean = false,
    onPointSelected: (Point) -> Unit = {},
    onTargetSelected: (Point) -> Unit = {},
    onToggleObstacle: (Point) -> Unit = {},
    onDragObstacle: (Point, Boolean) -> Unit = { _, _ -> },
    checkIsObstacle: (Point) -> Boolean = { false }
) {
    val maxX = gridCoordinates.maxOfOrNull { it.x }?.coerceAtLeast(1.0) ?: 10.0
    val maxY = gridCoordinates.maxOfOrNull { it.y }?.coerceAtLeast(1.0) ?: 10.0
    val density = LocalDensity.current
    val paddingPx = remember(density) { with(density) { 40.dp.toPx() } }
    val themePrimaryColor = MaterialTheme.colorScheme.primary

    var pulseState by remember { mutableStateOf(false) }
    LaunchedEffect(targetPoint) { while (true) { pulseState = !pulseState; delay(800) } }
    val pulseRadius by animateFloatAsState(targetValue = if (pulseState) 28f else 12f, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "pulse_radius")
    val pulseAlpha by animateFloatAsState(targetValue = if (pulseState) 0.1f else 0.5f, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "pulse_alpha")

    var isErasingDrag by remember { mutableStateOf(false) }
    val currentPredictedPoint by rememberUpdatedState(predictedPoint)
    val currentHeadingState by rememberUpdatedState(currentHeading)
    val currentIsFollowing by rememberUpdatedState(isMapFollowingMode)

    val panAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomAnimatable = remember { Animatable(1f) } // 🌟 新增缩放动画状态
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()

    // 🌟 3秒无操作恢复默认视角
    LaunchedEffect(lastInteractionTime) {
        if (lastInteractionTime > 0 && (panAnimatable.targetValue != Offset.Zero || zoomAnimatable.targetValue != 1f)) {
            delay(3000)
            coroutineScope.launch { panAnimatable.animateTo(Offset.Zero, tween(600, easing = FastOutSlowInEasing)) }
            coroutineScope.launch { zoomAnimatable.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        }
    }

    fun mapScreenToWorld(offset: Offset, size: IntSize): Point {
        val scaleX = (size.width - paddingPx * 2) / maxX
        val scaleY = (size.height - paddingPx * 2) / maxY
        val canvasCenterX = size.width / 2f
        val canvasCenterY = size.height / 2f

        // 🌟 逆向抵消缩放
        val unZoomedX = canvasCenterX + (offset.x - canvasCenterX) / zoomAnimatable.value
        val unZoomedY = canvasCenterY + (offset.y - canvasCenterY) / zoomAnimatable.value

        var mapPx = unZoomedX - panAnimatable.value.x
        var mapPy = unZoomedY - panAnimatable.value.y

        if (currentIsFollowing && currentPredictedPoint != null && editMode == InteractionState.NAVIGATION_MODE) {
            val userCx = paddingPx + (currentPredictedPoint!!.x * scaleX).toFloat()
            val userCy = paddingPx + (currentPredictedPoint!!.y * scaleY).toFloat()
            mapPx -= (canvasCenterX - userCx); mapPy -= (canvasCenterY - userCy)
            val rad = Math.toRadians((currentHeadingState ?: 0f).toDouble())
            val cosVal = cos(rad).toFloat(); val sinVal = sin(rad).toFloat()
            val dx = mapPx - userCx; val dy = mapPy - userCy
            mapPx = dx * cosVal - dy * sinVal + userCx; mapPy = dx * sinVal + dy * cosVal + userCy
        }

        val tapX = (mapPx - paddingPx) / scaleX; val tapY = (mapPy - paddingPx) / scaleY
        return Point(tapX.toDouble(), tapY.toDouble())
    }

    fun isValidPoint(pt: Point): Boolean { return pt.x >= -0.1 && pt.x <= maxX + 0.1 && pt.y >= -0.1 && pt.y <= maxY + 0.1 }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(gridCoordinates, editMode) {
                detectTapGestures { offset ->
                    val pt = mapScreenToWorld(offset, size)
                    if (isValidPoint(pt)) {
                        when (editMode) {
                            InteractionState.NAVIGATION_MODE -> { onTargetSelected(pt); lastInteractionTime = System.currentTimeMillis() }
                            InteractionState.OBSTACLE_MODE -> onToggleObstacle(pt)
                            InteractionState.BENCHMARK_MODE -> onPointSelected(pt)
                            InteractionState.EVALUATION_MODE -> {}
                        }
                        val nearest = gridCoordinates.minByOrNull { hypot(it.x - pt.x, it.y - pt.y) }
                        if (nearest != null && hypot(nearest.x - pt.x, nearest.y - pt.y) < (maxX / 5).coerceAtLeast(1.5)) onPointSelected(nearest)
                    }
                }
            }
            // 🌟 避障模式保持画墙的 Drag，定位模式支持 Pinch-to-zoom + Pan
            .pointerInput(gridCoordinates, editMode) {
                if (editMode == InteractionState.OBSTACLE_MODE) {
                    detectDragGestures(onDragStart = { offset -> val pt = mapScreenToWorld(offset, size); if (isValidPoint(pt)) isErasingDrag = checkIsObstacle(pt) }) { change, _ ->
                        val pt = mapScreenToWorld(change.position, size)
                        if (isValidPoint(pt)) onDragObstacle(pt, isErasingDrag)
                    }
                } else if (editMode == InteractionState.NAVIGATION_MODE) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        coroutineScope.launch {
                            panAnimatable.snapTo(panAnimatable.value + pan)
                            zoomAnimatable.snapTo((zoomAnimatable.value * zoom).coerceIn(0.5f, 3f)) // 限制缩放 0.5 到 3 倍
                        }
                        lastInteractionTime = System.currentTimeMillis()
                    }
                }
            }
    ) {
        val scaleX = (size.width - paddingPx * 2) / maxX; val scaleY = (size.height - paddingPx * 2) / maxY
        val canvasCenterX = size.width / 2f; val canvasCenterY = size.height / 2f
        val userCx = predictedPoint?.let { paddingPx + (it.x * scaleX).toFloat() } ?: canvasCenterX
        val userCy = predictedPoint?.let { paddingPx + (it.y * scaleY).toFloat() } ?: canvasCenterY
        val heading = currentHeading ?: 0f
        val applyTransform = isMapFollowingMode && predictedPoint != null && editMode == InteractionState.NAVIGATION_MODE

        withTransform({
            translate(panAnimatable.value.x, panAnimatable.value.y)
            scale(zoomAnimatable.value, zoomAnimatable.value, pivot = Offset(canvasCenterX, canvasCenterY)) // 🌟 缩放变换
            if (applyTransform) { translate(canvasCenterX - userCx, canvasCenterY - userCy); rotate(degrees = -heading, pivot = Offset(userCx, userCy)) }
        }) {
            val diagPx = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
            val viewCenterPhysX = if (applyTransform && predictedPoint != null) predictedPoint.x else maxX / 2.0
            val viewCenterPhysY = if (applyTransform && predictedPoint != null) predictedPoint.y else maxY / 2.0
            val safeScale = min(scaleX, scaleY).coerceAtLeast(0.1)
            val physRadius = (diagPx / safeScale) * 1.5

            val startCol = floor((viewCenterPhysX - physRadius) / gridResolution).toInt()
            val endCol = ceil((viewCenterPhysX + physRadius) / gridResolution).toInt()
            val startRow = floor((viewCenterPhysY - physRadius) / gridResolution).toInt()
            val endRow = ceil((viewCenterPhysY + physRadius) / gridResolution).toInt()
            val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            val gridLineColor = Color.Gray.copy(alpha = 0.1f)
            val strokeWidth = 1.dp.toPx()

            for (c in startCol..endCol) { val cx = paddingPx + (c * gridResolution * scaleX).toFloat(); drawLine(color = gridLineColor, start = Offset(cx, paddingPx + (startRow * gridResolution * scaleY).toFloat()), end = Offset(cx, paddingPx + (endRow * gridResolution * scaleY).toFloat()), strokeWidth = strokeWidth, pathEffect = dashPathEffect) }
            for (r in startRow..endRow) { val cy = paddingPx + (r * gridResolution * scaleY).toFloat(); drawLine(color = gridLineColor, start = Offset(paddingPx + (startCol * gridResolution * scaleX).toFloat(), cy), end = Offset(paddingPx + (endCol * gridResolution * scaleX).toFloat(), cy), strokeWidth = strokeWidth, pathEffect = dashPathEffect) }
            for (c in startCol..endCol) { for (r in startRow..endRow) { val cx = paddingPx + (c * gridResolution * scaleX).toFloat(); val cy = paddingPx + (r * gridResolution * scaleY).toFloat(); drawCircle(color = Color.Gray.copy(alpha = 0.15f), radius = 3.dp.toPx(), center = Offset(cx, cy)) } }

            val roomTopLeft = Offset(paddingPx, paddingPx)
            val roomSize = Size((maxX * scaleX).toFloat(), (maxY * scaleY).toFloat())
            drawRect(color = themePrimaryColor.copy(alpha = 0.03f), topLeft = roomTopLeft, size = roomSize)
            drawRect(color = themePrimaryColor.copy(alpha = 0.35f), topLeft = roomTopLeft, size = roomSize, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)))

            val obsColor = Color.Gray.copy(alpha = 0.7f)
            obstacles.forEach { obs -> drawRect(color = obsColor, topLeft = Offset(paddingPx + ((obs.x / gridResolution).toInt() * gridResolution * scaleX).toFloat(), paddingPx + ((obs.y / gridResolution).toInt() * gridResolution * scaleY).toFloat()), size = Size((gridResolution * scaleX).toFloat(), (gridResolution * scaleY).toFloat())) }

            if (currentPath.isNotEmpty()) {
                val path = Path()
                currentPath.forEachIndexed { index, pt -> val px = paddingPx + (pt.x * scaleX).toFloat(); val py = paddingPx + (pt.y * scaleY).toFloat(); if (index == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                drawPath(path = path, color = Color(0xFF1976D2).copy(alpha = 0.9f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)))
            }

            gridCoordinates.forEach { pt ->
                val cx = paddingPx + (pt.x * scaleX).toFloat(); val cy = paddingPx + (pt.y * scaleY).toFloat(); val isRecorded = recordedPoints.contains(pt); val isSelected = selectedPoint == pt
                drawCircle(color = if (isRecorded) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.4f), radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(), center = Offset(cx, cy))
                if (isSelected && editMode != InteractionState.NAVIGATION_MODE) drawCircle(color = Color(0xFF1976D2).copy(alpha = 0.6f), radius = 14.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
            }

            predictedPoint?.let { pos ->
                val cx = paddingPx + (pos.x * scaleX).toFloat(); val cy = paddingPx + (pos.y * scaleY).toFloat()
                drawCircle(color = Color(0xFFE53935).copy(alpha = 0.2f), radius = 24.dp.toPx(), center = Offset(cx, cy)); drawCircle(color = Color(0xFFE53935), radius = 6.dp.toPx(), center = Offset(cx, cy))
                if (editMode == InteractionState.NAVIGATION_MODE) { currentHeading?.let { hdg -> rotate(degrees = hdg, pivot = Offset(cx, cy)) { val arrowPath = Path().apply { moveTo(cx, cy - 12.dp.toPx()); lineTo(cx - 7.dp.toPx(), cy + 7.dp.toPx()); lineTo(cx + 7.dp.toPx(), cy + 7.dp.toPx()); close() }; drawPath(arrowPath, color = Color(0xFFE53935).copy(alpha = 0.85f)) } } }
                selectedPoint?.let { truth -> drawLine(color = Color(0xFF1976D2).copy(alpha = 0.6f), start = Offset(paddingPx + (truth.x * scaleX).toFloat(), paddingPx + (truth.y * scaleY).toFloat()), end = Offset(cx, cy), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)) }
            }

            targetPoint?.let { pos ->
                val cx = paddingPx + (pos.x * scaleX).toFloat(); val cy = paddingPx + (pos.y * scaleY).toFloat()
                drawCircle(color = themePrimaryColor.copy(alpha = pulseAlpha), radius = pulseRadius.dp.toPx(), center = Offset(cx, cy)); drawCircle(color = themePrimaryColor, radius = 8.dp.toPx(), center = Offset(cx, cy)); drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(cx, cy))
            }
        }
    }
}

suspend fun exportCsvToUri(context: Context, uri: Uri, points: List<ReferencePoint>): Boolean { return withContext(Dispatchers.IO) { try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.write("\uFEFF"); writer.write("ID,X,Y,FINGERPRINTS\n"); points.forEach { rp -> writer.write("${rp.id},${rp.coordinate.x},${rp.coordinate.y},${rp.fingerprint.entries.joinToString(";") { "${it.key}=${it.value}" }}\n") } }; true } catch (e: Exception) { false } } }
suspend fun importCsvFromUri(context: Context, uri: Uri): List<ReferencePoint>? { return withContext(Dispatchers.IO) { try { val importedPoints = mutableListOf<ReferencePoint>(); context.contentResolver.openInputStream(uri)?.use { inputStream -> BufferedReader(InputStreamReader(inputStream)).useLines { lines -> lines.drop(1).forEach { line -> val parts = line.split(","); if (parts.size >= 4) { importedPoints.add(ReferencePoint(parts[0], Point(parts[1].toDoubleOrNull() ?: 0.0, parts[2].toDoubleOrNull() ?: 0.0), parts[3].split(";").mapNotNull { fp -> val pair = fp.split("="); if (pair.size == 2) pair[0] to (pair[1].toIntOrNull() ?: -100) else null }.toMap())) } } } }; importedPoints } catch (e: Exception) { null } } }

@Composable
fun SegmentedButton(options: List<String>, selectedIndex: Int, onOptionSelected: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
        options.forEachIndexed { index, text ->
            val isSelected = index == selectedIndex
            Box(modifier = Modifier.weight(1f).background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onOptionSelected(index) }.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text(text = text, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (index < options.size - 1) Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
        }
    }
}