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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
// ArScannerScreen — 阶段一：基于 Plane Merging 的全自动边界扫描
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
    var isTracking by remember { mutableStateOf(false) }
    // 最新一帧的最大水平面多边形（世界坐标），500ms 刷新到 UI
    var planeSnapshot by remember { mutableStateOf(listOf<Point>()) }
    // 实时面积（m²）
    var currentAreaM2 by remember { mutableDoubleStateOf(0.0) }
    // 收敛状态
    var isConverged by remember { mutableStateOf(false) }
    // 渐入动画
    var isVisible by remember { mutableStateOf(false) }

    val sessionHolder = remember { ArSessionHolder() }
    var glView by remember { mutableStateOf<ArGLSurfaceView?>(null) }

    // ── 面积收敛判定（每 500ms tick 一次）────────────────────────────────────────────
    // 保存近 3 秒（6 次 500ms）的面积历史，判断增长是否 < 0.05m²
    val areaHistory = remember { ArrayDeque<Double>(7) }

    // ── AR Session 生命周期 ────────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        if (activity != null) sessionHolder.create(activity)
        onDispose { sessionHolder.close() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                Lifecycle.Event.ON_PAUSE  -> glView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 500ms 周期：快照 + 面积收敛判定 ───────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val snap = sessionHolder.latestFloorPolygon
            if (snap.isNotEmpty()) {
                planeSnapshot = snap
                val area = shoelaceArea(snap)
                currentAreaM2 = area

                // 维护历史队列（最多 7 项，覆盖 3.5 秒）
                if (areaHistory.size >= 7) areaHistory.removeFirst()
                areaHistory.addLast(area)

                // 收敛条件：面积 > 2m² 且 最近 6 次采样（3秒）增长 < 0.05m²
                if (!isConverged && areaHistory.size >= 6 && area > 2.0) {
                    val growth = areaHistory.last() - areaHistory[areaHistory.size - 6]
                    if (growth < 0.05) {
                        isConverged = true
                        // 震动反馈
                        triggerVibration(context)
                    }
                }
            }
        }
    }

    // 渐入动画
    LaunchedEffect(Unit) {
        delay(50)
        isVisible = true
    }

    // 采集间距（从 ViewModel 读，默认 1.0m）
    val gridStepM = remember(sharedViewModel.gridSpacing) {
        sharedViewModel.gridSpacing.toDoubleOrNull()?.coerceIn(0.5, 3.0) ?: 1.0
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(400)),
        exit  = fadeOut(animationSpec = tween(200))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            // ── 1. GLSurfaceView 相机 + 平面渲染层 ────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    ArGLSurfaceView(ctx, sessionHolder).also { view ->
                        glView = view
                        view.onTrackingChanged = { tracking -> isTracking = tracking }
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            view.onResume()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 2. 准星 ────────────────────────────────────────────────────────────────
            Canvas(modifier = Modifier.align(Alignment.Center).size(56.dp)) {
                val s = size.minDimension
                val arm = s * 0.30f
                val sw = 2.5f
                val color = Color.White.copy(alpha = 0.8f)
                listOf(
                    Offset(0f, 0f) to listOf(Offset(arm, 0f), Offset(0f, arm)),
                    Offset(s, 0f)  to listOf(Offset(s - arm, 0f), Offset(s, arm)),
                    Offset(0f, s)  to listOf(Offset(arm, s), Offset(0f, s - arm)),
                    Offset(s, s)   to listOf(Offset(s - arm, s), Offset(s, s - arm))
                ).forEach { (pivot, ends) -> ends.forEach { end -> drawLine(color, pivot, end, sw) } }
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(s / 2f, s / 2f))
            }

            // ── 3. 右上角 Mini-Map（显示当前检测到的最大平面多边形）────────────────────
            AnimatedVisibility(
                visible = planeSnapshot.isNotEmpty(),
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
                        vertices = planeSnapshot,
                        cursor = null,
                        pointCloud = emptyList(),
                        modifier = Modifier.fillMaxSize().padding(10.dp)
                    )
                }
            }

            // ── 4. 左上角：取消按钮 ────────────────────────────────────────────────────
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        sessionHolder.pause()
                        delay(200)
                        onCancel()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.White)
            }

            // ── 5. 顶部状态提示条 ──────────────────────────────────────────────────────
            val statusText = when {
                !isTracking                              -> "请缓慢移动设备，扫描地面"
                planeSnapshot.isEmpty()                  -> "未检测到地面平面，请对准地板"
                currentAreaM2 < 2.0                      -> "继续探索房间… 已测 %.1fm²".format(currentAreaM2)
                isConverged                              -> "✓ 扫描收敛！面积 %.1fm²，可完成".format(currentAreaM2)
                else                                     -> "继续走动以完整覆盖… %.1fm²".format(currentAreaM2)
            }
            val statusColor = if (isConverged) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.9f)

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }

            // ── 6. 底部操作栏 ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            sessionHolder.pause()
                            delay(200)
                            onCancel()
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "取消",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.size(72.dp))

                // 完成（面积 > 2m² 时解锁，不强制等收敛信号）
                val canFinish = currentAreaM2 > 2.0 && planeSnapshot.size >= 3
                IconButton(
                    onClick = {
                        val boundary = alignToScreen(planeSnapshot)
                        val gridPts  = generateVirtualGrid(boundary, gridStepM)
                        coroutineScope.launch {
                            sessionHolder.pause()
                            delay(200)
                            onComplete(ScanResult(boundary, gridPts))
                        }
                    },
                    enabled = canFinish,
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            if (canFinish) Color(0xFF00897B) else Color.White.copy(alpha = 0.15f),
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "完成扫描",
                        tint = if (canFinish) Color.White else Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// =====================================================================================
// 阶段二：虚拟采集网格生成（射线法过滤）
// =====================================================================================

/**
 * 在多边形内部按 [stepM] 步长生成均匀网格点。
 *
 * 1. 计算多边形 BoundingBox
 * 2. 在 BoundingBox 内生成矩阵点
 * 3. Ray-Casting 过滤掉多边形外部点
 */
fun generateVirtualGrid(polygon: List<Point>, stepM: Double): List<Point> {
    if (polygon.size < 3) return emptyList()

    val minX = polygon.minOf { it.x }
    val maxX = polygon.maxOf { it.x }
    val minY = polygon.minOf { it.y }
    val maxY = polygon.maxOf { it.y }

    val result = mutableListOf<Point>()
    var x = minX + stepM / 2.0   // 从网格中心开始，避免点落在边界线上
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

/**
 * Ray-Casting 点在多边形内判断。
 * 从点向 +X 方向发射射线，统计与多边形边的交叉次数。
 * 奇数 = 内部，偶数 = 外部。
 */
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

/** Shoelace 公式计算多边形面积（m²） */
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

/** 震动反馈：200ms 短震 */
private fun triggerVibration(context: Context) {
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
            vib?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib?.vibrate(200)
        }
    } catch (_: Exception) {}
}

// =====================================================================================
// ArSessionHolder — 持有 ARCore Session，并在 GL 线程写入最新平面多边形
// =====================================================================================
class ArSessionHolder {
    @Volatile var session: Session? = null
        private set
    @Volatile var isPaused = false
        private set

    // GL 线程写入，UI 线程（500ms）读取——使用 @Volatile 保证可见性
    @Volatile var latestFloorPolygon: List<Point> = emptyList()

    fun create(activity: Activity) {
        try {
            if (ArCoreApk.getInstance().requestInstall(activity, true)
                != ArCoreApk.InstallStatus.INSTALLED) return

            val s = Session(activity)

            // ── S25+ 防崩：弹性相机配置，优先 30FPS，无法匹配则用默认配置 ──────────────
            try {
                val filter = CameraConfigFilter(s).apply {
                    targetFps = java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                }
                val configs = s.getSupportedCameraConfigs(filter)
                if (configs.isNotEmpty()) s.cameraConfig = configs[0]
                // 若 configs 为空（不支持 30FPS 过滤），直接使用默认配置，不崩溃
            } catch (_: Exception) {}

            val config = Config(s).apply {
                planeFindingMode  = Config.PlaneFindingMode.HORIZONTAL
                updateMode        = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode         = Config.DepthMode.AUTOMATIC   // 软件深度（S25+ 无 ToF）
                focusMode         = Config.FocusMode.AUTO         // 自动对焦，防近景追踪断裂
                lightEstimationMode = Config.LightEstimationMode.DISABLED // 节省算力
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
        try { session?.close(); session = null } catch (_: Exception) {}
    }
}

// =====================================================================================
// ArGLSurfaceView
// =====================================================================================
class ArGLSurfaceView(context: Context, private val holder: ArSessionHolder) :
    GLSurfaceView(context) {

    var onTrackingChanged: ((Boolean) -> Unit)? = null

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

    override fun onResume() { super.onResume(); holder.resume() }
    override fun onPause()  { super.onPause();  holder.pause()  }
}

// =====================================================================================
// ArRenderer — GL 渲染 + Plane 多边形提取
// =====================================================================================
class ArRenderer(
    private val sessionHolder: ArSessionHolder,
    context: Context,
    private val onFrameUpdate: (tracking: Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private val rotationHelper = DisplayRotationHelper(context)
    private var backgroundRenderer: CameraBackgroundRenderer? = null
    private var planeRenderer: PlaneRenderer? = null
    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            backgroundRenderer = CameraBackgroundRenderer()
            planeRenderer      = PlaneRenderer()
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
            backgroundRenderer?.draw(frame)

            val camera     = frame.camera
            val isTracking = camera.trackingState == TrackingState.TRACKING

            if (isTracking) {
                val projMatrix = FloatArray(16)
                val viewMatrix = FloatArray(16)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
                camera.getViewMatrix(viewMatrix, 0)

                val planes = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING }

                // 赛博朋克平面网格渲染（阶段一视觉核心，必须开启）
                planeRenderer?.drawPlanes(planes, viewMatrix, projMatrix)

                // ── 提取面积最大的水平向上平面，将 polygon 转为世界坐标 ────────────────
                val floorPlane = planes
                    .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    .maxByOrNull { it.extentX * it.extentZ }

                if (floorPlane != null) {
                    sessionHolder.latestFloorPolygon = extractWorldPolygon(floorPlane)
                }
            }

            onFrameUpdate(isTracking)
        } catch (_: SessionPausedException) {
        } catch (_: Exception) {}
    }

    /**
     * 将 ARCore Plane 的局部 polygon 顶点转换为世界坐标系中的 (X, Z) 坐标列表。
     *
     * plane.polygon 是以平面中心 Pose 为原点的局部 FloatBuffer（x, z 交替存储，无 Y）。
     * 通过 centerPose.transformPoint([localX, 0, localZ]) 得到世界坐标。
     */
    private fun extractWorldPolygon(plane: Plane): List<Point> {
        val buf = plane.polygon   // 局部坐标，格式：x0,z0, x1,z1, ...
        buf.rewind()
        val result = mutableListOf<Point>()
        val localPt = FloatArray(3)
        val worldPt = FloatArray(3)
        while (buf.remaining() >= 2) {
            localPt[0] = buf.get()  // localX
            localPt[1] = 0f
            localPt[2] = buf.get()  // localZ
            plane.centerPose.transformPoint(localPt, 0, worldPt, 0)
            result.add(Point(worldPt[0].toDouble(), worldPt[2].toDouble()))
        }
        return result
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
// PlaneRenderer — 赛博朋克斑点网格平面渲染（阶段一视觉核心，禁止关闭）
// =====================================================================================
class PlaneRenderer {

    private var program           = 0
    private var positionAttrib    = 0
    private var mvpMatrixUniform  = 0
    private var colorUniform      = 0
    private var dotScaleUniform   = 0

    private val PLANE_COLOR = floatArrayOf(0.1f, 0.85f, 0.7f, 0.22f)   // 半透明青绿
    private val DOT_COLOR   = floatArrayOf(0.1f, 0.95f, 0.75f, 0.55f)  // 亮点

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER,   VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        positionAttrib   = GLES20.glGetAttribLocation(program,  "a_Position")
        mvpMatrixUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform     = GLES20.glGetUniformLocation(program, "u_Color")
        dotScaleUniform  = GLES20.glGetUniformLocation(program, "u_DotScale")
    }

    fun drawPlanes(planes: Collection<Plane>, viewMatrix: FloatArray, projMatrix: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        for (plane in planes) {
            val modelMatrix = FloatArray(16)
            plane.centerPose.toMatrix(modelMatrix, 0)
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            val extentX  = plane.extentX
            val extentZ  = plane.extentZ
            val vertices = buildPlaneVertices(extentX, extentZ)
            val vBuf = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(vertices); position(0) }

            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glUniformMatrix4fv(mvpMatrixUniform, 1, false, mvpMatrix, 0)

            GLES20.glUniform4fv(colorUniform, 1, PLANE_COLOR, 0)
            GLES20.glUniform1f(dotScaleUniform, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertices.size / 3)

            val dots = buildDotVertices(extentX, extentZ, spacing = 0.12f)
            if (dots.isNotEmpty()) {
                val dBuf = java.nio.ByteBuffer.allocateDirect(dots.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                    .apply { put(dots); position(0) }
                GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, dBuf)
                GLES20.glUniform4fv(colorUniform, 1, DOT_COLOR, 0)
                GLES20.glUniform1f(dotScaleUniform, 1f)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, dots.size / 3)
            }
            GLES20.glDisableVertexAttribArray(positionAttrib)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildPlaneVertices(extentX: Float, extentZ: Float): FloatArray {
        val segments = 32
        val rx = extentX / 2f; val rz = extentZ / 2f
        val verts = mutableListOf<Float>()
        verts += listOf(0f, 0f, 0f)
        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            verts += listOf(rx * cos(angle), 0f, rz * sin(angle))
        }
        return verts.toFloatArray()
    }

    private fun buildDotVertices(extentX: Float, extentZ: Float, spacing: Float): FloatArray {
        val rx = extentX / 2f; val rz = extentZ / 2f
        val dots = mutableListOf<Float>()
        var x = -rx
        while (x <= rx) {
            var z = -rz
            while (z <= rz) {
                if ((x / rx) * (x / rx) + (z / rz) * (z / rz) <= 1f) dots += listOf(x, 0f, z)
                z += spacing
            }
            x += spacing
        }
        return dots.toFloatArray()
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            uniform float u_DotScale;
            void main() {
                gl_Position = u_MVP * a_Position;
                gl_PointSize = 5.0 * u_DotScale + 1.0;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """
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
