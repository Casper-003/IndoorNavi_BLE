package com.example.indoornavi

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

// ================= 交互式雷达数据引擎 =================
@Composable
fun InteractiveRadarMap(
    modifier: Modifier = Modifier,
    gridCoordinates: List<Point>,
    recordedPoints: List<Point>,
    selectedPoint: Point? = null,
    predictedPoint: Point? = null,
    onPointSelected: (Point) -> Unit
) {
    val maxX = gridCoordinates.maxOfOrNull { it.x }?.coerceAtLeast(1.0) ?: 10.0
    val maxY = gridCoordinates.maxOfOrNull { it.y }?.coerceAtLeast(1.0) ?: 10.0

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(gridCoordinates) {
                detectTapGestures { offset ->
                    val padding = 40.dp.toPx()
                    val usableWidth = size.width - padding * 2
                    val usableHeight = size.height - padding * 2
                    val scaleX = usableWidth / maxX
                    val scaleY = usableHeight / maxY

                    val tapX = (offset.x - padding) / scaleX
                    val tapY = (offset.y - padding) / scaleY

                    val nearest = gridCoordinates.minByOrNull { Math.hypot(it.x - tapX, it.y - tapY) }

                    if (nearest != null) {
                        val distance = Math.hypot(nearest.x - tapX, nearest.y - tapY)
                        if (distance < (maxX / 5).coerceAtLeast(1.5)) {
                            onPointSelected(nearest)
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

        val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        val gridLineColor = Color.Gray.copy(alpha = 0.15f)
        val strokeWidth = 1.dp.toPx()

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

        gridCoordinates.forEach { pt ->
            val cx = padding + (pt.x * scaleX).toFloat()
            val cy = padding + (pt.y * scaleY).toFloat()

            val isRecorded = recordedPoints.contains(pt)
            val isSelected = selectedPoint == pt

            drawCircle(
                color = if (isRecorded) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.3f),
                radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(),
                center = Offset(cx, cy)
            )

            if (isSelected) {
                drawCircle(
                    color = Color(0xFF1976D2).copy(alpha = 0.6f),
                    radius = 14.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        predictedPoint?.let { pos ->
            val safeX = pos.x.coerceIn(-1.0, maxX + 1.0)
            val safeY = pos.y.coerceIn(-1.0, maxY + 1.0)

            val cx = padding + (safeX * scaleX).toFloat()
            val cy = padding + (safeY * scaleY).toFloat()

            drawCircle(color = Color(0xFFE53935).copy(alpha = 0.2f), radius = 24.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = Color(0xFFE53935), radius = 6.dp.toPx(), center = Offset(cx, cy))

            selectedPoint?.let { truth ->
                val tx = padding + (truth.x * scaleX).toFloat()
                val ty = padding + (truth.y * scaleY).toFloat()

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