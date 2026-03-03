package com.example.indoornavi

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
    // 🌟 核心 1：硬件级计步器 (比分析加速度计波峰波谷既准又省电)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    // 🌟 核心 2：旋转矢量传感器 (融合了陀螺仪和地磁，无积分漂移)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 暴露给 UI 的最终平滑坐标流
    private val _fusedPosition = MutableStateFlow<Point?>(null)
    val fusedPosition: StateFlow<Point?> = _fusedPosition.asStateFlow()

    // 内部运行状态
    private var currentHeading: Double = 0.0 // 当前偏航角 (弧度)
    private var lastWknnPosition: Point? = null // 最新接收到的 WKNN 绝对坐标

    // 论文可调超参数 (Hyper-parameters)
    private val stepLength = 0.6 // 默认步长 0.6 米
    private val wknnWeight = 0.3 // WKNN 绝对坐标权重 (修正漂移)
    private val pdrWeight = 0.7  // PDR 相对推算权重 (保证平滑)

    fun start() {
        stepDetector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // 接收来自蓝牙扫描算出的 WKNN 原始坐标
    fun updateWknnPosition(wknnPoint: Point) {
        lastWknnPosition = wknnPoint

        val currentFused = _fusedPosition.value
        if (currentFused == null) {
            // 冷启动：此时还没有 PDR 数据，完全信任 WKNN
            _fusedPosition.value = wknnPoint
        } else {
            // 站立静止时的微重置：如果没有检测到走动，缓慢将红点拉回 WKNN 的中心，防止长时间静止时的漂移
            _fusedPosition.value = Point(
                x = currentFused.x * 0.9 + wknnPoint.x * 0.1,
                y = currentFused.y * 0.9 + wknnPoint.y * 0.1
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // orientationAngles[0] 是 Azimuth (绕Z轴旋转的偏航角)，范围 -pi 到 pi
                currentHeading = orientationAngles[0].toDouble()
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                // 🌟 触发核心逻辑：硬件检测到用户走了一步！
                val currentFused = _fusedPosition.value
                val wknnPos = lastWknnPosition

                if (currentFused != null && wknnPos != null) {
                    // 1. PDR 纯推算：基于上一次的坐标，朝着手机当前指向迈出一步
                    val pdrX = currentFused.x + stepLength * sin(currentHeading)
                    val pdrY = currentFused.y - stepLength * cos(currentHeading)

                    // 2. 互补滤波融合：将 PDR 平滑位移与 WKNN 绝对坐标融合
                    val fusedX = pdrX * pdrWeight + wknnPos.x * wknnWeight
                    val fusedY = pdrY * pdrWeight + wknnPos.y * wknnWeight

                    // 瞬间推送到 UI 层渲染
                    _fusedPosition.value = Point(fusedX, fusedY)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 无需处理
    }
}