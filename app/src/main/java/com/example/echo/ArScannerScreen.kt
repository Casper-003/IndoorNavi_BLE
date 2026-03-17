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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
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

    var polygonVertices by remember { mutableStateOf(listOf<Point>()) }
    var isTracking by remember { mutableStateOf(false) }
    var currentHitPoint by remember { mutableStateOf<Point?>(null) }
    // 控制整个界面的淡入
    var isVisible by remember { mutableStateOf(false) }

    val sessionHolder = remember { ArSessionHolder() }
    // GLSurfaceView 实例引用，用于手动触发 onResume/onPause
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
                        view.onTrackingChanged = { tracking, hitPoint ->
                            isTracking = tracking
                            currentHitPoint = hitPoint
                        }
                        // AndroidView 创建时 Lifecycle 已是 RESUMED，手动触发一次
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            view.onResume()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 2. 专业准星：四角框线样式 ────────────────────────────────────────
            val crosshairColor = if (currentHitPoint != null) Color(0xFF00E676) else Color.White.copy(alpha = 0.8f)
            Canvas(
                modifier = Modifier.align(Alignment.Center).size(56.dp)
            ) {
                val s = size.minDimension
                val arm = s * 0.30f   // 角线长度
                val gap = s * 0.08f   // 中心留空
                val sw = 2.5f

                val corners = listOf(
                    Offset(0f, 0f) to listOf(Offset(arm, 0f), Offset(0f, arm)),
                    Offset(s, 0f) to listOf(Offset(s - arm, 0f), Offset(s, arm)),
                    Offset(0f, s) to listOf(Offset(arm, s), Offset(0f, s - arm)),
                    Offset(s, s) to listOf(Offset(s - arm, s), Offset(s, s - arm))
                )
                corners.forEach { (pivot, ends) ->
                    ends.forEach { end ->
                        drawLine(crosshairColor, pivot, end, strokeWidth = sw)
                    }
                }
                // 中心点
                drawCircle(crosshairColor, radius = 2.dp.toPx(), center = Offset(s / 2f, s / 2f))
            }

            // ── 3. 右上角 Mini-Map ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = polygonVertices.isNotEmpty(),
                enter = fadeIn(tween(300)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 16.dp)
            ) {
                Card(
                    modifier = Modifier.size(120.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    MiniMapCanvas(
                        vertices = polygonVertices,
                        cursor = currentHitPoint,
                        modifier = Modifier.fillMaxSize().padding(10.dp)
                    )
                }
            }

            // ── 4. 左上角：退出按钮 ──────────────────────────────────────────────
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
                Icon(Icons.Default.Close, contentDescription = "退出扫描", tint = Color.White)
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
                    text = if (!isTracking) "移动设备以检测地面平面"
                           else if (polygonVertices.isEmpty()) "检测到平面 — 标记第一个角点"
                           else "已标记 ${polygonVertices.size} 个角点",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            // ── 6. 底部操作栏：固定三列，主按钮始终居中 ─────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左槽：撤销按钮
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = polygonVertices.isNotEmpty(),
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        IconButton(
                            onClick = { polygonVertices = polygonVertices.dropLast(1) },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "撤销",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // 中槽：主操作按钮（标记角点）
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            if (currentHitPoint != null) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            currentHitPoint?.let { polygonVertices = polygonVertices + it }
                        },
                        enabled = currentHitPoint != null,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.AddLocationAlt,
                            contentDescription = "标记角点",
                            tint = if (currentHitPoint != null) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // 右槽：完成按钮
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = polygonVertices.size >= 3,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        IconButton(
                            onClick = {
                                val captured = polygonVertices
                                coroutineScope.launch {
                                    sessionHolder.pause()
                                    delay(200)
                                    onComplete(captured)
                                }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color(0xFF00897B), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "完成扫描",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
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

    private val renderer = ArRenderer(holder, context) { tracking, hitPoint ->
        post { onTrackingChanged?.invoke(tracking, hitPoint) }
    }

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
    private val onFrameUpdate: (tracking: Boolean, hitPoint: Point?) -> Unit
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
fun MiniMapCanvas(vertices: List<Point>, cursor: Point?, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (vertices.isEmpty() && cursor == null) return@Canvas

        val allPoints = vertices.toMutableList()
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
