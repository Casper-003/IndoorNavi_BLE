package com.example.echo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

// ================= PDR 与 WKNN 互补融合定位引擎 =================
class LocationFusionEngine(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _fusedPosition = MutableStateFlow<Point?>(null)
    val fusedPosition: StateFlow<Point?> = _fusedPosition.asStateFlow()

    private val _currentHeadingFlow = MutableStateFlow(0f)
    val currentHeadingFlow: StateFlow<Float> = _currentHeadingFlow.asStateFlow()

    private var currentHeading: Double = 0.0
    private var headingOffset: Double = 0.0

    // PDR 步长估计 (米)
    private val stepLength = 0.6

    // 互补滤波权重：PDR推算惯性(0.7) vs 蓝牙绝对纠偏(0.3)
    private val wknnWeight = 0.3
    private val pdrWeight = 0.7

    fun start() {
        stepDetector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // 🌟 核心修正 1：剥离互补滤波的触发时机
    // 只有当接收到新的【绝对物理坐标 (WKNN)】时，才对现有的 PDR 轨迹进行加权拉扯纠偏
    fun updateWknnPosition(wknnPoint: Point) {
        val currentFused = _fusedPosition.value
        if (currentFused == null) {
            _fusedPosition.value = wknnPoint
        } else {
            // 将旧的平滑轨迹向新的绝对坐标靠拢
            _fusedPosition.value = Point(
                x = currentFused.x * pdrWeight + wknnPoint.x * wknnWeight,
                y = currentFused.y * pdrWeight + wknnPoint.y * wknnWeight
            )
        }
    }

    fun calibrateHeading() {
        headingOffset = -currentHeading
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                currentHeading = orientationAngles[0].toDouble()
                val mapHeading = currentHeading + headingOffset
                _currentHeadingFlow.value = Math.toDegrees(mapHeading).toFloat()
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                val currentFused = _fusedPosition.value

                if (currentFused != null) {
                    val mapHeading = currentHeading + headingOffset

                    // 🌟 核心修正 2：步进推算绝对信任微机电系统 (MEMS) 的相对位移
                    // 彻底废除步进时的蓝牙强行加权，告别“皮筋回弹”
                    val pdrX = currentFused.x + stepLength * sin(mapHeading)
                    val pdrY = currentFused.y - stepLength * cos(mapHeading)

                    _fusedPosition.value = Point(pdrX, pdrY)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}