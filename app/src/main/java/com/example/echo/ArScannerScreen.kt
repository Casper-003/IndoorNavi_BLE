package com.example.echo

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

// =====================================================================================
// 数据模型
// =====================================================================================

/**
 * 扫描结果：
 *  - boundary   — 房间边界多边形（世界坐标系 X/Z，单位米，已 PCA 对齐）
 *  - gridPoints — 阶段二虚拟采集网格（射线法过滤，单位米，与 boundary 同坐标系）
 */
data class ScanResult(
    val boundary: List<Point>,
    val gridPoints: List<Point> = emptyList()
)

// =====================================================================================
// ArScannerScreen — 手动打点建图模式
// 复刻 ARCoreMeasuredDistance 的交互逻辑：
//   点击地面放锚点 → 相邻连线 → 显示边长 → 支持撤销 → 完成后闭合多边形输出
// =====================================================================================

@Composable
fun ArScannerScreen(
    sharedViewModel: SharedViewModel,
    onComplete: (ScanResult) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // ── 状态 ──────────────────────────────────────────────────────────────────────────
    var isTracking    by remember { mutableStateOf(false) }
    var anchorPoints  by remember { mutableStateOf(listOf<Point>()) }
    var segmentLengths by remember { mutableStateOf(listOf<Double>()) }

    // 动画阶段：uiReady = UI 元素飞入；cameraReady = 摄像头开启
    var uiReady     by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }

    val sessionHolder = remember { ArSessionHolder() }
    var glView by remember { mutableStateOf<ArGLSurfaceView?>(null) }

    // ── 进入序列：50ms 后 UI 飞入，再等 400ms 动画播完后开摄像头 ──────────────────────
    LaunchedEffect(Unit) {
        delay(50)
        uiReady = true
        delay(400)
        cameraReady = true
    }

    // ── cameraReady 变为 true 时，真正 resume GLSurfaceView ────────────────────────────
    LaunchedEffect(cameraReady) {
        if (cameraReady) {
            glView?.onResume()
        }
    }

    // ── AR Session 创建（不依赖 cameraReady，提前初始化省时间）──────────────────────────
    DisposableEffect(Unit) {
        if (activity != null) sessionHolder.create(activity)
        onDispose { sessionHolder.close() }
    }

    // ── 生命周期：仅在 cameraReady 后才真正 resume/pause ──────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { if (cameraReady) glView?.onResume() }
                Lifecycle.Event.ON_PAUSE  -> glView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gridStepM = remember(sharedViewModel.gridSpacing) {
        sharedViewModel.gridSpacing.toDoubleOrNull()?.coerceIn(0.5, 3.0) ?: 1.0
    }
    val canFinish = anchorPoints.size >= 3
    val canUndo   = anchorPoints.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── 1. GLSurfaceView（始终存在，cameraReady 前不 resume）──────────────────────
        AndroidView(
            factory = { ctx ->
                ArGLSurfaceView(ctx, sessionHolder).also { view ->
                    glView = view
                    view.onTrackingChanged = { tracking -> isTracking = tracking }
                    view.onTapResult = { anchor, worldX, worldZ ->
                        val newPoint = Point(worldX.toDouble(), worldZ.toDouble())
                        val prev = anchorPoints.lastOrNull()
                        val tooClose = prev != null && run {
                            val dx = newPoint.x - prev.x
                            val dy = newPoint.y - prev.y
                            sqrt(dx * dx + dy * dy) < 0.3
                        }
                        if (tooClose) {
                            anchor.detach()
                            triggerVibration(ctx, doubleClick = true)
                        } else {
                            if (prev != null) {
                                val dx = newPoint.x - prev.x
                                val dy = newPoint.y - prev.y
                                segmentLengths = segmentLengths + sqrt(dx * dx + dy * dy)
                            }
                            anchorPoints = anchorPoints + newPoint
                            triggerVibration(ctx)
                        }
                    }
                    // 不在 factory 里调用 onResume，由 cameraReady 控制
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── 2. 准星（始终显示，随 uiReady 淡入）──────────────────────────────────────
        AnimatedVisibility(
            visible = uiReady,
            enter = fadeIn(tween(400)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val s = size.minDimension
                val arm = s * 0.30f
                val sw = 2.5f
                val color = Color.White.copy(alpha = 0.8f)
                listOf(
                    Offset(0f, 0f) to listOf(Offset(arm, 0f), Offset(0f, arm)),
                    Offset(s, 0f)  to listOf(Offset(s - arm, 0f), Offset(s, arm)),
                    Offset(0f, s)  to listOf(Offset(arm, s), Offset(0f, s - arm)),
                    Offset(s, s)   to listOf(Offset(s - arm, s), Offset(s, s - arm))
                ).forEach { (pivot, ends) ->
                    ends.forEach { end -> drawLine(color, pivot, end, sw) }
                }
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(s / 2f, s / 2f))
            }
        }

        // ── 3. 右上角 MiniMap ──────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = anchorPoints.isNotEmpty(),
            enter = fadeIn(tween(300)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 52.dp, end = 16.dp)
        ) {
            Card(
                modifier = Modifier.size(120.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                MiniMapCanvas(
                    vertices = anchorPoints,
                    cursor = null,
                    pointCloud = emptyList(),
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }
        }

        // ── 4. 顶部栏（取消 + 状态提示）从上方滑入 ────────────────────────────────────
        AnimatedVisibility(
            visible = uiReady,
            enter = slideInVertically(tween(400)) { -it } + fadeIn(tween(400)),
            exit  = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            // 先触发 UI 退出动画，再 pause session，避免 UI 挂在黑屏上
                            uiReady = false
                            delay(200)
                            sessionHolder.pause()
                            onCancel()
                        }
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.White)
                }

                Spacer(Modifier.width(8.dp))

                // 状态提示
                val statusText = when {
                    !isTracking            -> "请缓慢移动设备，扫描地面"
                    anchorPoints.isEmpty() -> "将准星对准墙角，按下放置"
                    anchorPoints.size == 1 -> "继续放置锚点（至少 3 个）"
                    anchorPoints.size == 2 -> "再放置 1 个即可完成"
                    else                   -> "已放置 ${anchorPoints.size} 个点，可继续或完成"
                }
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // ── 5. 边长标签（左侧浮动）────────────────────────────────────────────────────
        if (segmentLengths.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val display  = segmentLengths.takeLast(3)
                val startIdx = segmentLengths.size - display.size
                display.forEachIndexed { i, len ->
                    Text(
                        text = "段${startIdx + i + 1}：${"%.0f".format(len * 100)} cm",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00E5FF)
                    )
                }
                if (canFinish) {
                    val a = anchorPoints.first(); val b = anchorPoints.last()
                    val closingLen = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
                    Text(
                        text = "闭合：${"%.0f".format(closingLen * 100)} cm",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF69F0AE)
                    )
                }
            }
        }

        // ── 6. 底部三按钮：从下方滑入 ─────────────────────────────────────────────────
        // 布局：[撤销 48dp] ──── [放置 72dp] ──── [完成 48dp]
        AnimatedVisibility(
            visible = uiReady,
            enter = slideInVertically(tween(400)) { it } + fadeIn(tween(400)),
            exit  = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 撤销（小）
                FilledIconButton(
                    onClick = {
                        if (canUndo) {
                            sessionHolder.undoLastAnchor()
                            anchorPoints = anchorPoints.dropLast(1)
                            if (segmentLengths.isNotEmpty())
                                segmentLengths = segmentLengths.dropLast(1)
                        }
                    },
                    enabled = canUndo,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.06f),
                        disabledContentColor = Color.White.copy(alpha = 0.25f)
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销", modifier = Modifier.size(20.dp))
                }

                // 放置（大）
                FilledIconButton(
                    onClick = { glView?.hitTestCenter() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "放置锚点", modifier = Modifier.size(32.dp))
                }

                // 完成（小）
                FilledIconButton(
                    onClick = {
                        val boundary = alignToScreen(rdpSimplify(anchorPoints, 0.1))
                        val gridPts  = generateVirtualGrid(boundary, gridStepM)
                        coroutineScope.launch {
                            uiReady = false
                            delay(200)
                            sessionHolder.pause()
                            onComplete(ScanResult(boundary, gridPts))
                        }
                    },
                    enabled = canFinish,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF00897B),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.06f),
                        disabledContentColor = Color.White.copy(alpha = 0.25f)
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = "完成", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// =====================================================================================
// 阶段二：虚拟采集网格生成（射线法过滤）
// =====================================================================================

fun generateVirtualGrid(polygon: List<Point>, stepM: Double): List<Point> {
    if (polygon.size < 3) return emptyList()

    val minX = polygon.minOf { it.x }
    val maxX = polygon.maxOf { it.x }
    val minY = polygon.minOf { it.y }
    val maxY = polygon.maxOf { it.y }

    val result = mutableListOf<Point>()
    var x = minX + stepM / 2.0
    while (x <= maxX) {
        var y = minY + stepM / 2.0
        while (y <= maxY) {
            val p = Point(x, y)
            if (isPointInPolygon(p, polygon)) result.add(p)
            y += stepM
        }
        x += stepM
    }
    return result
}

private fun isPointInPolygon(point: Point, polygon: List<Point>): Boolean {
    var inside = false
    val n = polygon.size
    var j = n - 1
    for (i in 0 until n) {
        val xi = polygon[i].x; val yi = polygon[i].y
        val xj = polygon[j].x; val yj = polygon[j].y
        val intersect = ((yi > point.y) != (yj > point.y)) &&
                (point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

// =====================================================================================
// 工具函数
// =====================================================================================

fun shoelaceArea(pts: List<Point>): Double {
    if (pts.size < 3) return 0.0
    var area = 0.0
    val n = pts.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += pts[i].x * pts[j].y
        area -= pts[j].x * pts[i].y
    }
    return abs(area) / 2.0
}

/**
 * Ramer-Douglas-Peucker 多边形简化。
 * 去掉共线或近共线的冗余点，保留形状关键转角。
 * [epsilon] 单位米，推荐 0.1m（10cm）。
 */
fun rdpSimplify(points: List<Point>, epsilon: Double): List<Point> {
    if (points.size <= 2) return points
    // 找离首尾连线最远的点
    val start = points.first(); val end = points.last()
    var maxDist = 0.0; var maxIdx = 0
    for (i in 1 until points.size - 1) {
        val d = perpendicularDistance(points[i], start, end)
        if (d > maxDist) { maxDist = d; maxIdx = i }
    }
    return if (maxDist > epsilon) {
        // 递归处理两段
        val left  = rdpSimplify(points.subList(0, maxIdx + 1), epsilon)
        val right = rdpSimplify(points.subList(maxIdx, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(start, end)
    }
}

/** 点 p 到线段 (a, b) 的垂直距离 */
private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
    val dx = b.x - a.x; val dy = b.y - a.y
    if (dx == 0.0 && dy == 0.0) {
        return sqrt((p.x - a.x).pow(2) + (p.y - a.y).pow(2))
    }
    return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / sqrt(dx * dx + dy * dy)
}

private fun triggerVibration(context: Context, doubleClick: Boolean = false) {
    try {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (doubleClick) {
                // 两短震：提示"太近了，已拒绝"
                VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 50), -1)
            } else {
                VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vib?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            if (doubleClick) vib?.vibrate(longArrayOf(0, 50, 60, 50), -1)
            else vib?.vibrate(60)
        }
    } catch (_: Exception) {}
}

// =====================================================================================
// ArSessionHolder — 持有 ARCore Session，维护锚点列表
// =====================================================================================
class ArSessionHolder {
    @Volatile var session: Session? = null
        private set
    @Volatile var isPaused = false
        private set

    // GL 线程持有的锚点列表（世界坐标）
    // 主线程通过 onTapResult 追加，undoLastAnchor 删除
    val anchors = mutableListOf<Anchor>()

    fun create(activity: Activity) {
        try {
            if (ArCoreApk.getInstance().requestInstall(activity, true)
                != ArCoreApk.InstallStatus.INSTALLED) return

            val s = Session(activity)

            try {
                val filter = CameraConfigFilter(s).apply {
                    targetFps = java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                }
                val configs = s.getSupportedCameraConfigs(filter)
                if (configs.isNotEmpty()) s.cameraConfig = configs[0]
            } catch (_: Exception) {}

            val config = Config(s).apply {
                planeFindingMode    = Config.PlaneFindingMode.HORIZONTAL
                updateMode          = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode           = Config.DepthMode.AUTOMATIC
                focusMode           = Config.FocusMode.AUTO
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            s.configure(config)
            session = s
        } catch (_: Exception) {}
    }

    fun resume() {
        try { session?.resume(); isPaused = false } catch (_: Exception) {}
    }

    fun pause() {
        try { session?.pause(); isPaused = true } catch (_: Exception) {}
    }

    fun close() {
        try {
            anchors.forEach { it.detach() }
            anchors.clear()
            session?.close(); session = null
        } catch (_: Exception) {}
    }

    fun undoLastAnchor() {
        synchronized(anchors) {
            if (anchors.isNotEmpty()) {
                anchors.removeAt(anchors.lastIndex).detach()
            }
        }
    }
}

// =====================================================================================
// ArGLSurfaceView — 准星中心 hit test（由按钮触发，不再依赖触摸）
// =====================================================================================
class ArGLSurfaceView(context: Context, private val holder: ArSessionHolder) :
    GLSurfaceView(context) {

    var onTrackingChanged: ((Boolean) -> Unit)? = null
    // 回调：成功放置锚点时，返回 Anchor 和世界 (X, Z)
    var onTapResult: ((Anchor, Float, Float) -> Unit)? = null

    private val renderer = ArRenderer(
        sessionHolder = holder,
        context = context,
        onFrameUpdate = { tracking -> post { onTrackingChanged?.invoke(tracking) } }
    )

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** 对屏幕正中心执行 hit test，由放置按钮调用 */
    fun hitTestCenter() {
        if (holder.isPaused) return
        queueEvent {
            try {
                val frame = renderer.lastFrame ?: return@queueEvent
                val cx = width / 2f
                val cy = height / 2f
                val hits = frame.hitTest(cx, cy)
                val hit = hits.firstOrNull { hr ->
                    val trackable = hr.trackable
                    trackable is Plane &&
                    trackable.isPoseInPolygon(hr.hitPose) &&
                    trackable.trackingState == TrackingState.TRACKING &&
                    hr.distance <= 10f
                } ?: return@queueEvent

                val anchor = hit.createAnchor()
                synchronized(holder.anchors) { holder.anchors.add(anchor) }
                val pose = anchor.pose
                post { onTapResult?.invoke(anchor, pose.tx(), pose.tz()) }
            } catch (_: Exception) {}
        }
    }

    override fun onResume() { super.onResume(); holder.resume() }
    override fun onPause()  { super.onPause();  holder.pause()  }
}

// =====================================================================================
// ArRenderer — GL 渲染：相机背景 + 锚点球体 + 连线
// =====================================================================================
class ArRenderer(
    private val sessionHolder: ArSessionHolder,
    context: Context,
    private val onFrameUpdate: (tracking: Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private val rotationHelper = DisplayRotationHelper(context)
    private var backgroundRenderer: CameraBackgroundRenderer? = null
    private var anchorRenderer: AnchorRenderer? = null
    private var viewportWidth = 0
    private var viewportHeight = 0

    // ArGLSurfaceView 在 GL 线程读取此 frame 执行 hit test
    @Volatile var lastFrame: Frame? = null

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            backgroundRenderer = CameraBackgroundRenderer()
            anchorRenderer     = AnchorRenderer()
        } catch (_: Exception) {}
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width; viewportHeight = height
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = sessionHolder.session ?: return
        if (sessionHolder.isPaused) return

        try {
            rotationHelper.updateSessionIfNeeded(session)
            session.setCameraTextureName(backgroundRenderer?.textureId ?: return)
            val frame  = session.update()
            lastFrame  = frame
            backgroundRenderer?.draw(frame)

            val camera     = frame.camera
            val isTracking = camera.trackingState == TrackingState.TRACKING

            if (isTracking) {
                val projMatrix = FloatArray(16)
                val viewMatrix = FloatArray(16)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
                camera.getViewMatrix(viewMatrix, 0)

                val snapAnchors = synchronized(sessionHolder.anchors) {
                    sessionHolder.anchors.toList()
                }
                anchorRenderer?.draw(snapAnchors, viewMatrix, projMatrix)
            }

            onFrameUpdate(isTracking)
        } catch (_: SessionPausedException) {
        } catch (_: Exception) {}
    }
}

// =====================================================================================
// AnchorRenderer — 渲染锚点球体（绿色圆点）+ 相邻连线（白色）
// =====================================================================================
class AnchorRenderer {

    private var program = 0
    private var aPos    = 0
    private var uMVP    = 0
    private var uColor  = 0

    private val vpMatrix  = FloatArray(16)
    private val mvp       = FloatArray(16)
    private val model     = FloatArray(16)

    // 球体顶点（近似：经纬分段生成 GL_LINES）
    private val sphereLines: FloatArray
    private val sphereNio: java.nio.FloatBuffer

    // 线段缓冲（每帧根据锚点数量动态构建）
    private var lineArray = FloatArray(0)
    private var lineNio: java.nio.FloatBuffer? = null
    private var lineCap = 0

    init {
        program = buildProgram(VS, FS)
        aPos   = GLES20.glGetAttribLocation(program,  "a_Position")
        uMVP   = GLES20.glGetUniformLocation(program, "u_MVP")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")

        // 生成球体线框（半径 0.04m，经纬各 12 段）
        sphereLines = buildSphereLines(0.04f, 12)
        sphereNio = java.nio.ByteBuffer
            .allocateDirect(sphereLines.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(sphereLines); position(0) }
    }

    fun draw(anchors: List<Anchor>, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (anchors.isEmpty()) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // ── 渲染每个锚点球体（绿色）─────────────────────────────────────────────────
        GLES20.glUniform4f(uColor, 0.33f, 0.87f, 0f, 1f)  // 对应原项目绿色
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glLineWidth(1.5f)

        for (anchor in anchors) {
            if (anchor.trackingState != TrackingState.TRACKING) continue
            anchor.pose.toMatrix(model, 0)
            Matrix.multiplyMM(mvp, 0, vpMatrix, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            sphereNio.position(0)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, sphereNio)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, sphereLines.size / 3)
        }

        // ── 渲染连线（白色），使用单位 model 矩阵（世界坐标直接画）─────────────────
        if (anchors.size >= 2) {
            val validAnchors = anchors.filter { it.trackingState == TrackingState.TRACKING }
            if (validAnchors.size >= 2) {
                // 构建线段顶点：相邻对 + 闭合线（如果 ≥ 3 个点）
                val segCount = validAnchors.size - 1 +
                        if (validAnchors.size >= 3) 1 else 0
                val needed = segCount * 6  // 每段 2 点 × 3 float
                if (needed > lineCap) {
                    lineArray = FloatArray(needed)
                    lineNio = java.nio.ByteBuffer.allocateDirect(needed * 4)
                        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                    lineCap = needed
                }

                var li = 0
                for (i in 0 until validAnchors.size - 1) {
                    val pA = validAnchors[i].pose
                    val pB = validAnchors[i + 1].pose
                    lineArray[li++] = pA.tx(); lineArray[li++] = pA.ty(); lineArray[li++] = pA.tz()
                    lineArray[li++] = pB.tx(); lineArray[li++] = pB.ty(); lineArray[li++] = pB.tz()
                }
                // 闭合线：最后一点 → 第一点（预览用，半透明）
                if (validAnchors.size >= 3) {
                    val pFirst = validAnchors.first().pose
                    val pLast  = validAnchors.last().pose
                    lineArray[li++] = pLast.tx();  lineArray[li++] = pLast.ty();  lineArray[li++] = pLast.tz()
                    lineArray[li++] = pFirst.tx(); lineArray[li++] = pFirst.ty(); lineArray[li++] = pFirst.tz()
                }

                lineNio!!.position(0); lineNio!!.put(lineArray, 0, li); lineNio!!.position(0)

                // 用单位矩阵作为 model（顶点已是世界坐标）
                Matrix.setIdentityM(model, 0)
                Matrix.multiplyMM(mvp, 0, vpMatrix, 0, model, 0)
                GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)

                // 实线（连线段，白色）
                GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.9f)
                lineNio!!.position(0)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, lineNio)
                GLES20.glLineWidth(2f)
                val solidCount = (validAnchors.size - 1) * 2
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, solidCount)

                // 闭合线（虚线效果用半透明实线代替，浅绿色）
                if (validAnchors.size >= 3) {
                    GLES20.glUniform4f(uColor, 0.41f, 0.94f, 0.42f, 0.50f)
                    lineNio!!.position(solidCount * 3)  // 跳到闭合段
                    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, lineNio)
                    GLES20.glLineWidth(1.5f)
                    GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
                }
            }
        }

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 生成球体线框顶点（经线 + 纬线，GL_LINES 格式） */
    private fun buildSphereLines(r: Float, segments: Int): FloatArray {
        val lines = mutableListOf<Float>()
        val step = (2 * PI / segments).toFloat()

        // XZ 平面圆（赤道）
        for (i in 0 until segments) {
            val a0 = i * step; val a1 = (i + 1) * step
            lines += floatArrayOf(
                r * cos(a0), 0f, r * sin(a0),
                r * cos(a1), 0f, r * sin(a1)
            ).toList()
        }
        // XY 平面圆
        for (i in 0 until segments) {
            val a0 = i * step; val a1 = (i + 1) * step
            lines += floatArrayOf(
                r * cos(a0), r * sin(a0), 0f,
                r * cos(a1), r * sin(a1), 0f
            ).toList()
        }
        // YZ 平面圆
        for (i in 0 until segments) {
            val a0 = i * step; val a1 = (i + 1) * step
            lines += floatArrayOf(
                0f, r * sin(a0), r * cos(a0),
                0f, r * sin(a1), r * cos(a1)
            ).toList()
        }
        return lines.toFloatArray()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vs); GLES20.glCompileShader(it) }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fs); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        private const val VS = """
            uniform mat4  u_MVP;
            attribute vec3 a_Position;
            void main() { gl_Position = u_MVP * vec4(a_Position, 1.0); }
        """
        private const val FS = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """
    }
}

// =====================================================================================
// DisplayRotationHelper
// =====================================================================================
class DisplayRotationHelper(private val context: Context) {
    private var viewportChanged = false
    private var viewportWidth   = 0
    private var viewportHeight  = 0

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width; viewportHeight = height; viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager
            val rotation = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }
}

// =====================================================================================
// CameraBackgroundRenderer
// =====================================================================================
class CameraBackgroundRenderer {
    var textureId: Int = 0
        private set

    private var quadProgram        = 0
    private var quadPositionAttrib = 0
    private var quadTexCoordAttrib = 0
    private var textureUniform     = 0

    private val ndcCoords = floatArrayOf(
        -1f, -1f, 0f,
         1f, -1f, 0f,
        -1f,  1f, 0f,
         1f,  1f, 0f
    )

    init {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val target = android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER,   VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        quadProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        quadPositionAttrib = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        textureUniform     = GLES20.glGetUniformLocation(quadProgram, "sTexture")
    }

    fun draw(frame: Frame) {
        val texCoords = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            Coordinates2d.TEXTURE_NORMALIZED,
            texCoords
        )
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(quadProgram)

        val posBuffer = java.nio.ByteBuffer.allocateDirect(ndcCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(ndcCoords); position(0) }
        val texBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(texCoords); position(0) }

        GLES20.glVertexAttribPointer(quadPositionAttrib, 3, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glVertexAttribPointer(quadTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glEnableVertexAttribArray(quadPositionAttrib)
        GLES20.glEnableVertexAttribArray(quadTexCoordAttrib)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionAttrib)
        GLES20.glDisableVertexAttribArray(quadTexCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """
    }
}

// =====================================================================================
// MiniMapCanvas
// =====================================================================================
@Composable
fun MiniMapCanvas(
    vertices: List<Point>,
    cursor: Point?,
    pointCloud: List<Point> = emptyList(),
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        if (vertices.isEmpty() && cursor == null && pointCloud.isEmpty()) return@Canvas

        val allPoints = (vertices + pointCloud).toMutableList()
        cursor?.let { allPoints.add(it) }

        val minX = allPoints.minOf { it.x }; val maxX = allPoints.maxOf { it.x }
        val minY = allPoints.minOf { it.y }; val maxY = allPoints.maxOf { it.y }
        val rangeX = (maxX - minX).coerceAtLeast(1.0)
        val rangeY = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf(size.width / rangeX.toFloat(), size.height / rangeY.toFloat()) * 0.8f
        val cx = size.width / 2f; val cy = size.height / 2f
        val pcx = ((minX + maxX) / 2.0).toFloat()
        val pcy = ((minY + maxY) / 2.0).toFloat()

        fun toPx(p: Point) = Offset(
            cx + ((p.x - pcx) * scale).toFloat(),
            cy + ((p.y - pcy) * scale).toFloat()
        )

        pointCloud.forEach { p ->
            drawCircle(Color(0xFF00E5FF).copy(alpha = 0.5f), radius = 1.5f.dp.toPx(), center = toPx(p))
        }

        if (vertices.isNotEmpty()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(toPx(vertices.first()).x, toPx(vertices.first()).y)
                for (i in 1 until vertices.size) lineTo(toPx(vertices[i]).x, toPx(vertices[i]).y)
                if (vertices.size >= 3) close()
            }
            drawPath(path, Color(0xFF1976D2).copy(alpha = 0.8f), style = Stroke(width = 3.dp.toPx()))
            if (vertices.size >= 3) drawPath(path, Color(0xFF1976D2).copy(alpha = 0.2f))
        }
        vertices.forEach { drawCircle(Color.White, 2.5f.dp.toPx(), toPx(it)) }
        cursor?.let { drawCircle(Color(0xFF00E676), 3.5f.dp.toPx(), toPx(it)) }
    }
}

// =====================================================================================
// PCA 主轴对齐
// =====================================================================================
fun alignToScreen(pts: List<Point>): List<Point> {
    if (pts.size < 2) return pts
    val cx = pts.sumOf { it.x } / pts.size
    val cy = pts.sumOf { it.y } / pts.size
    var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
    for (p in pts) {
        val dx = p.x - cx; val dy = p.y - cy
        cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
    }
    val diff = (cxx - cyy) / 2.0
    val eigenAngle = 0.5 * atan2(2.0 * cxy, diff * 2.0 + 1e-10)
    val axisX = cos(eigenAngle); val axisY = sin(eigenAngle)
    val angleToY = atan2(axisX, axisY)
    val cosA = cos(-angleToY); val sinA = sin(-angleToY)
    return pts.map { p ->
        val dx = p.x - cx; val dy = p.y - cy
        Point(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
    }
}

// =====================================================================================
// 扩展：Context → Activity
// =====================================================================================
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
