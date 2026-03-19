package com.example.echo

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

// =====================================================================================
// AR 扫描界面
// =====================================================================================

@Composable
fun ArScannerScreen(
    sharedViewModel: SharedViewModel,
    onComplete: (List<Point>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isTracking by remember { mutableStateOf(false) }
    val collectedPoints = remember { mutableStateListOf<Point>() }
    // 用于去重的网格 key 集合（分辨率 0.1m）
    val occupiedCells = remember { HashSet<Long>() }
    // Mini-Map 显示用快照，每 500ms 更新，避免高频重组
    var pointCloudSnapshot by remember { mutableStateOf(listOf<Point>()) }
    var isVisible by remember { mutableStateOf(false) }

    // 每 500ms 刷新一次 Mini-Map 快照
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (collectedPoints.isNotEmpty()) {
                pointCloudSnapshot = collectedPoints.toList()
            }
        }
    }

    val sessionHolder = remember { ArSessionHolder() }
    var glView by remember { mutableStateOf<ArGLSurfaceView?>(null) }

    // 创建 AR Session
    DisposableEffect(Unit) {
        if (activity != null) sessionHolder.create(activity)
        onDispose { sessionHolder.close() }
    }

    // 跟随 Lifecycle 驱动 GL 线程的 resume/pause，修复黑屏问题
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

    // 界面加载完成后触发淡入动画
    LaunchedEffect(Unit) {
        delay(50)
        isVisible = true
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(400)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            // ── 1. GLSurfaceView 相机预览层 ──────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    ArGLSurfaceView(ctx, sessionHolder).also { view ->
                        glView = view
                        view.onTrackingChanged = { tracking, _ ->
                            isTracking = tracking
                        }
                        view.onPointCloudUpdated = { points ->
                            // 网格去重：只保留未见过的格子中心点（分辨率 0.1m）
                            val newPts = points.filter { p ->
                                val cx = (p.x / 0.1).toLong()
                                val cz = (p.y / 0.1).toLong()
                                val key = cx.shl(32) or (cz and 0xFFFFFFFFL)
                                occupiedCells.add(key)
                            }
                            if (newPts.isNotEmpty()) collectedPoints.addAll(newPts)
                        }
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            view.onResume()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 2. 准星 ──────────────────────────────────────────────────────────
            Canvas(modifier = Modifier.align(Alignment.Center).size(56.dp)) {
                val s = size.minDimension
                val arm = s * 0.30f
                val sw = 2.5f
                val color = Color.White.copy(alpha = 0.8f)
                listOf(
                    Offset(0f, 0f) to listOf(Offset(arm, 0f), Offset(0f, arm)),
                    Offset(s, 0f) to listOf(Offset(s - arm, 0f), Offset(s, arm)),
                    Offset(0f, s) to listOf(Offset(arm, s), Offset(0f, s - arm)),
                    Offset(s, s) to listOf(Offset(s - arm, s), Offset(s, s - arm))
                ).forEach { (pivot, ends) -> ends.forEach { end -> drawLine(color, pivot, end, sw) } }
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(s / 2f, s / 2f))
            }

            // ── 3. 右上角 Mini-Map ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = pointCloudSnapshot.isNotEmpty(),
                enter = fadeIn(tween(300)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 16.dp)
            ) {
                Card(
                    modifier = Modifier.size(120.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    MiniMapCanvas(
                        vertices = emptyList(),
                        cursor = null,
                        pointCloud = pointCloudSnapshot,
                        modifier = Modifier.fillMaxSize().padding(10.dp)
                    )
                }
            }

            // ── 4. 左上角：取消按钮 ──────────────────────────────────────────────
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

            // ── 5. 顶部居中：状态提示条 ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when {
                        !isTracking -> "请缓慢移动设备，扫描地面"
                        collectedPoints.size < 50 -> "沿房间边缘缓慢行走，收集地图数据…"
                        else -> "数据充足，可完成扫描（已采集 ${collectedPoints.size} 点）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            // ── 6. 底部操作栏 ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左槽：取消
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
                    Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.White, modifier = Modifier.size(22.dp))
                }

                // 中槽：占位，保持布局对称
                Spacer(modifier = Modifier.size(72.dp))

                // 右槽：完成（≥50 个点才激活）
                val canFinish = collectedPoints.size >= 50
                IconButton(
                    onClick = {
                        val pts = collectedPoints.toList()
                        val flatPoints = FloatArray(pts.size * 2) { i ->
                            if (i % 2 == 0) pts[i / 2].x.toFloat() else pts[i / 2].y.toFloat()
                        }
                        val raw = MapProcessor.extractBoundaryPolygon(flatPoints, 0.15f)
                        val boundary = if (raw.size >= 6) {
                            (0 until raw.size / 2).map { i ->
                                Point(raw[i * 2].toDouble(), raw[i * 2 + 1].toDouble())
                            }
                        } else {
                            extractBoundaryPolygon(pts) // 回退到 Kotlin 实现
                        }
                        coroutineScope.launch {
                            sessionHolder.pause()
                            delay(200)
                            onComplete(boundary)
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
// ArSessionHolder
// =====================================================================================
class ArSessionHolder {
    @Volatile var session: Session? = null
        private set
    @Volatile var isPaused = false
        private set

    fun create(activity: Activity) {
        try {
            if (ArCoreApk.getInstance().requestInstall(activity, true)
                != ArCoreApk.InstallStatus.INSTALLED) return
            val s = Session(activity)
            val config = Config(s).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
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

    var onTrackingChanged: ((Boolean, Point?) -> Unit)? = null
    var onPointCloudUpdated: ((List<Point>) -> Unit)? = null

    private val renderer = ArRenderer(holder, context,
        onFrameUpdate = { tracking, hitPoint ->
            post { onTrackingChanged?.invoke(tracking, hitPoint) }
        },
        onPointCloud = { points ->
            post { onPointCloudUpdated?.invoke(points) }
        }
    )

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        holder.resume()
    }

    override fun onPause() {
        super.onPause()
        holder.pause()
    }
}

// =====================================================================================
// ArRenderer
// =====================================================================================
class ArRenderer(
    private val sessionHolder: ArSessionHolder,
    context: Context,
    private val onFrameUpdate: (tracking: Boolean, hitPoint: Point?) -> Unit,
    private val onPointCloud: (List<Point>) -> Unit
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
            planeRenderer = PlaneRenderer()
        } catch (_: Exception) {}
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = sessionHolder.session ?: return
        if (sessionHolder.isPaused) return
        try {
            rotationHelper.updateSessionIfNeeded(session)
            session.setCameraTextureName(backgroundRenderer?.textureId ?: return)
            val frame = session.update()
            backgroundRenderer?.draw(frame)

            val camera = frame.camera
            val isTracking = camera.trackingState == TrackingState.TRACKING
            var hitPoint: Point? = null

            if (isTracking) {
                    // 绘制检测到的平面
                    val projMatrix = FloatArray(16)
                    val viewMatrix = FloatArray(16)
                    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
                    camera.getViewMatrix(viewMatrix, 0)
                    val planes = session.getAllTrackables(Plane::class.java)
                        .filter { it.trackingState == TrackingState.TRACKING }
                    planeRenderer?.drawPlanes(planes, viewMatrix, projMatrix)

                    val hit = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
                        .firstOrNull { it.trackable is Plane }
                    if (hit != null) {
                        val pose = hit.hitPose
                        hitPoint = Point(pose.tx().toDouble(), pose.tz().toDouble())
                    }

                    // 采集 Point Cloud，用检测到的地面平面 Y 值作为基准过滤
                    val cloud = frame.acquirePointCloud()
                    try {
                        val buf = cloud.points // FloatBuffer: x, y, z, confidence 每4个一组

                        // 取所有 TRACKING 状态水平面的平均 Y 值作为地面基准
                        val floorY = planes
                            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                            .map { it.centerPose.ty() }
                            .average()
                            .takeIf { !it.isNaN() }
                            ?.toFloat()
                            ?: (camera.pose.ty() - 1.5f) // 无平面时回退到相机估算

                        val yTolerance = 0.2f // 严格到 ±20cm，排除桌椅
                        val groundPoints = mutableListOf<Point>()
                        buf.rewind()
                        while (buf.remaining() >= 4) {
                            val x = buf.get()
                            val y = buf.get()
                            val z = buf.get()
                            buf.get() // confidence，跳过
                            if (y in (floorY - yTolerance)..(floorY + yTolerance)) {
                                groundPoints.add(Point(x.toDouble(), z.toDouble()))
                            }
                        }
                        if (groundPoints.isNotEmpty()) onPointCloud(groundPoints)
                    } finally {
                        cloud.release()
                    }
                }
            onFrameUpdate(isTracking, hitPoint)
        } catch (_: SessionPausedException) {
        } catch (_: Exception) {
        }
    }
}

// =====================================================================================
// DisplayRotationHelper
// =====================================================================================
class DisplayRotationHelper(private val context: Context) {
    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

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
// PlaneRenderer — 把检测到的 ARCore 平面渲染成斑点网格效果
// =====================================================================================
class PlaneRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpMatrixUniform = 0
    private var colorUniform = 0
    private var dotScaleUniform = 0

    // 平面颜色：半透明青绿色
    private val PLANE_COLOR = floatArrayOf(0.1f, 0.85f, 0.7f, 0.22f)
    // 网格点颜色：亮一些
    private val DOT_COLOR   = floatArrayOf(0.1f, 0.95f, 0.75f, 0.55f)

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        positionAttrib    = GLES20.glGetAttribLocation(program, "a_Position")
        mvpMatrixUniform  = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform      = GLES20.glGetUniformLocation(program, "u_Color")
        dotScaleUniform   = GLES20.glGetUniformLocation(program, "u_DotScale")
    }

    fun drawPlanes(planes: Collection<Plane>, viewMatrix: FloatArray, projMatrix: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        for (plane in planes) {
            val pose = plane.centerPose
            val modelMatrix = FloatArray(16)
            pose.toMatrix(modelMatrix, 0)

            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            val extentX = plane.extentX
            val extentZ = plane.extentZ
            val vertices = buildPlaneVertices(extentX, extentZ)
            val vBuf = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(vertices); position(0) }

            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glUniformMatrix4fv(mvpMatrixUniform, 1, false, mvpMatrix, 0)

            // 先绘制半透明填充面
            GLES20.glUniform4fv(colorUniform, 1, PLANE_COLOR, 0)
            GLES20.glUniform1f(dotScaleUniform, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertices.size / 3)

            // 再绘制斑点网格（用点精灵模拟）
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

    /** 生成平面的凸多边形扇形顶点（中心 + 边缘一圈） */
    private fun buildPlaneVertices(extentX: Float, extentZ: Float): FloatArray {
        val segments = 32
        val rx = extentX / 2f
        val rz = extentZ / 2f
        val verts = mutableListOf<Float>()
        verts += listOf(0f, 0f, 0f) // 中心点
        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            verts += listOf(rx * cos(angle), 0f, rz * sin(angle))
        }
        return verts.toFloatArray()
    }

    /** 生成平面内均匀分布的斑点坐标 */
    private fun buildDotVertices(extentX: Float, extentZ: Float, spacing: Float): FloatArray {
        val rx = extentX / 2f
        val rz = extentZ / 2f
        val dots = mutableListOf<Float>()
        var x = -rx
        while (x <= rx) {
            var z = -rz
            while (z <= rz) {
                // 椭圆内裁剪
                if ((x / rx) * (x / rx) + (z / rz) * (z / rz) <= 1f) {
                    dots += listOf(x, 0f, z)
                }
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

    private var quadProgram = 0
    private var quadPositionAttrib = 0
    private var quadTexCoordAttrib = 0
    private var textureUniform = 0

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

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        quadProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        quadPositionAttrib = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        textureUniform    = GLES20.glGetUniformLocation(quadProgram, "sTexture")
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
fun MiniMapCanvas(vertices: List<Point>, cursor: Point?, pointCloud: List<Point> = emptyList(), modifier: Modifier) {
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
        val pcx = (minX + maxX) / 2f; val pcy = (minY + maxY) / 2f

        fun toPx(p: Point) = Offset(
            cx + ((p.x - pcx).toFloat() * scale),
            cy + ((p.y - pcy).toFloat() * scale)
        )

        // 先画点云散点（底层）
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
        if (vertices.isNotEmpty() && cursor != null) {
            drawLine(Color(0xFF00E676), toPx(vertices.last()), toPx(cursor), 1.5f.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        vertices.forEach { drawCircle(Color.White, 2.5f.dp.toPx(), toPx(it)) }
        cursor?.let { drawCircle(Color(0xFF00E676), 3.5f.dp.toPx(), toPx(it)) }
    }
}

// =====================================================================================
// 边界多边形提取：栅格化 → 边缘格 → 凸包排序
// =====================================================================================
fun extractBoundaryPolygon(points: List<Point>, resolution: Float = 0.15f): List<Point> {
    if (points.size < 3) return points

    // 1. 映射到网格
    val minX = points.minOf { it.x.toFloat() }
    val minZ = points.minOf { it.y.toFloat() }
    val occupied = HashSet<Long>()
    fun cellKey(cx: Int, cz: Int): Long = cx.toLong().shl(32) or cz.toLong().and(0xFFFFFFFFL)
    for (p in points) {
        val cx = ((p.x.toFloat() - minX) / resolution).toInt()
        val cz = ((p.y.toFloat() - minZ) / resolution).toInt()
        occupied.add(cellKey(cx, cz))
    }

    // 2. 找边缘格（8邻域中有空格的）
    val neighbors = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
    val edgeCells = mutableListOf<Point>()
    for (key in occupied) {
        val cx = (key shr 32).toInt()
        val cz = (key and 0xFFFFFFFFL).toInt()
        val isEdge = neighbors.any { (dx, dz) -> !occupied.contains(cellKey(cx + dx, cz + dz)) }
        if (isEdge) {
            val wx = minX + (cx + 0.5f) * resolution
            val wz = minZ + (cz + 0.5f) * resolution
            edgeCells.add(Point(wx.toDouble(), wz.toDouble()))
        }
    }
    if (edgeCells.size < 3) return edgeCells

    // 3. 凸包排序
    val hull = convexHull(edgeCells)

    // 4. PCA 主轴对齐：让房间"长边"朝向屏幕 Y 轴（向上）
    return alignToScreen(hull)
}

/**
 * PCA 主轴对齐：计算点集的主方向，旋转使主轴平行于屏幕 Y 轴。
 * 这样不管用户扫描时的朝向，精修页显示的地图都是"竖直"的。
 */
private fun alignToScreen(pts: List<Point>): List<Point> {
    if (pts.size < 2) return pts

    // 质心
    val cx = pts.sumOf { it.x } / pts.size
    val cy = pts.sumOf { it.y } / pts.size

    // 协方差矩阵元素
    var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
    for (p in pts) {
        val dx = p.x - cx; val dy = p.y - cy
        cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
    }

    // 最大特征值对应的特征向量（主轴方向）
    val diff = (cxx - cyy) / 2.0
    val eigenAngle = 0.5 * atan2(2.0 * cxy, diff * 2.0 + 1e-10)
    // 主轴向量
    val axisX = cos(eigenAngle)
    val axisY = sin(eigenAngle)

    // 判断主轴更接近水平还是竖直：若更接近水平，旋转 90°
    // 目标：主轴对齐屏幕竖直方向（Y轴），即旋转角 = -(主轴与Y轴的夹角)
    val angleToY = atan2(axisX, axisY) // 主轴与 Y 轴的夹角
    val cosA = cos(-angleToY); val sinA = sin(-angleToY)

    return pts.map { p ->
        val dx = p.x - cx; val dy = p.y - cy
        Point(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
    }
}

/** Andrew's Monotone Chain 凸包算法，返回逆时针排序顶点 */
private fun convexHull(pts: List<Point>): List<Point> {
    val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size <= 2) return sorted

    fun cross(o: Point, a: Point, b: Point) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    val lower = mutableListOf<Point>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeLast()
        lower.add(p)
    }
    val upper = mutableListOf<Point>()
    for (p in sorted.reversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeLast()
        upper.add(p)
    }
    lower.removeLast(); upper.removeLast()
    return lower + upper
}

// =====================================================================================
// MapProcessor：JNI 桥接，调用 native-lib.so 中的 C++ 建图算法
// =====================================================================================
object MapProcessor {
    init { System.loadLibrary("native-lib") }
    external fun extractBoundaryPolygon(points: FloatArray, resolution: Float): FloatArray
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
