package com.example.echo

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

// ================= 1. 数据结构定义 =================
data class BenchmarkRecord(
    val timestamp: Long,
    val truthX: Double, val truthY: Double,
    val knnX: Double, val knnY: Double,
    val wknnX: Double, val wknnY: Double,
    val fusedX: Double, val fusedY: Double,
    val validN: Int,
    val d1: Double,
    val rawRssi: Int,
    val smoothedRssi: Int,
    val envLabel: String
)

// ================= 2. 核心采集引擎 =================
class BenchmarkLogger {
    private val records = mutableListOf<BenchmarkRecord>()
    private var logJob: Job? = null

    // 🌟 新增：安全取消机制，清理废弃的协程和脏数据
    fun cancelLogging() {
        logJob?.cancel()
        records.clear()
    }

    // 🎯 模式 A：定点定额采样
    fun startLogging(
        coroutineScope: CoroutineScope,
        truth: Point,
        liveDevicesFlow: StateFlow<List<BeaconDevice>>,
        getSelectedMacs: () -> Set<String>,
        locator: WknnLocator,
        activeFingerprints: List<ReferencePoint>,
        kValue: Int,
        getFused: () -> Point?,
        envLabel: String,
        targetSamples: Int = 50,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ) {
        cancelLogging() // 启动前先确保环境干净
        logJob = coroutineScope.launch(Dispatchers.Default) {
            var lastHash = 0L
            var lockedMac: String? = null

            liveDevicesFlow.collect { devices ->
                val selectedMacs = getSelectedMacs()
                if (selectedMacs.isEmpty()) return@collect

                var currentHash = 0L
                for (device in devices) {
                    if (device.macAddress in selectedMacs) { currentHash += device.lastSeen }
                }

                if (currentHash != lastHash && currentHash != 0L) {
                    lastHash = currentHash
                    val liveRssiMap = selectedMacs.associateWith { mac -> devices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }

                    if (lockedMac == null) {
                        lockedMac = liveRssiMap.maxByOrNull { it.value }?.key
                    }
                    val targetDevice = devices.find { it.macAddress == lockedMac }
                    val raw = targetDevice?.rawRssi ?: -100
                    val smoothed = targetDevice?.smoothedRssi ?: -100

                    val knnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, false)
                    val wknnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, true)
                    val fusedPos = getFused() ?: Point(0.0, 0.0)

                    records.add(
                        BenchmarkRecord(
                            timestamp = System.currentTimeMillis(),
                            truthX = truth.x, truthY = truth.y,
                            knnX = knnResult?.coordinate?.x ?: 0.0, knnY = knnResult?.coordinate?.y ?: 0.0,
                            wknnX = wknnResult?.coordinate?.x ?: 0.0, wknnY = wknnResult?.coordinate?.y ?: 0.0,
                            fusedX = fusedPos.x, fusedY = fusedPos.y,
                            validN = wknnResult?.validN ?: 0, d1 = wknnResult?.d1 ?: Double.MAX_VALUE,
                            rawRssi = raw, smoothedRssi = smoothed, envLabel = envLabel
                        )
                    )

                    val currentSize = records.size
                    withContext(Dispatchers.Main) { onProgress(currentSize.toFloat() / targetSamples) }

                    if (currentSize >= targetSamples) {
                        withContext(Dispatchers.Main) { onComplete() }
                        cancel()
                    }
                }
            }
        }
    }

    // 🎯 模式 C：定时静默采样 (60秒读条)
    fun startTimeBoundLogging(
        coroutineScope: CoroutineScope,
        liveDevicesFlow: StateFlow<List<BeaconDevice>>,
        getSelectedMacs: () -> Set<String>,
        locator: WknnLocator,
        activeFingerprints: List<ReferencePoint>,
        kValue: Int,
        getFused: () -> Point?,
        envLabel: String,
        durationSeconds: Int = 60,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ) {
        cancelLogging()
        val targetSamples = durationSeconds * 2 // 2Hz

        logJob = coroutineScope.launch(Dispatchers.Default) {
            var lockedMac: String? = null
            var currentSamples = 0

            while (isActive && currentSamples < targetSamples) {
                delay(500)
                val devices = liveDevicesFlow.value
                val selectedMacs = getSelectedMacs()
                if (selectedMacs.isEmpty()) continue

                val liveRssiMap = selectedMacs.associateWith { mac -> devices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }

                if (lockedMac == null) {
                    lockedMac = liveRssiMap.maxByOrNull { it.value }?.key
                }
                val targetDevice = devices.find { it.macAddress == lockedMac }
                val raw = targetDevice?.rawRssi ?: -100
                val smoothed = targetDevice?.smoothedRssi ?: -100

                val knnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, false)
                val wknnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, true)
                val fusedPos = getFused() ?: Point(0.0, 0.0)

                records.add(
                    BenchmarkRecord(
                        timestamp = System.currentTimeMillis(),
                        truthX = Double.NaN, truthY = Double.NaN,
                        knnX = knnResult?.coordinate?.x ?: 0.0, knnY = knnResult?.coordinate?.y ?: 0.0,
                        wknnX = wknnResult?.coordinate?.x ?: 0.0, wknnY = wknnResult?.coordinate?.y ?: 0.0,
                        fusedX = fusedPos.x, fusedY = fusedPos.y,
                        validN = wknnResult?.validN ?: 0, d1 = wknnResult?.d1 ?: Double.MAX_VALUE,
                        rawRssi = raw, smoothedRssi = smoothed, envLabel = envLabel
                    )
                )

                currentSamples++
                withContext(Dispatchers.Main) { onProgress(currentSamples.toFloat() / targetSamples) }
            }

            if (currentSamples >= targetSamples) {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    // 🎯 模式 B：后台连续轨迹录制模式 (不限时)
    fun startContinuousLogging(
        coroutineScope: CoroutineScope,
        liveDevicesFlow: StateFlow<List<BeaconDevice>>,
        getSelectedMacs: () -> Set<String>,
        locator: WknnLocator,
        activeFingerprints: List<ReferencePoint>,
        kValue: Int,
        getFused: () -> Point?,
        envLabel: String
    ) {
        cancelLogging()
        logJob = coroutineScope.launch(Dispatchers.Default) {
            var lockedMac: String? = null

            while (isActive) {
                delay(500)
                val devices = liveDevicesFlow.value
                val selectedMacs = getSelectedMacs()
                if (selectedMacs.isEmpty()) continue

                val liveRssiMap = selectedMacs.associateWith { mac -> devices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }

                if (lockedMac == null) {
                    lockedMac = liveRssiMap.maxByOrNull { it.value }?.key
                }
                val targetDevice = devices.find { it.macAddress == lockedMac }
                val raw = targetDevice?.rawRssi ?: -100
                val smoothed = targetDevice?.smoothedRssi ?: -100

                val knnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, false)
                val wknnResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue, true)
                val fusedPos = getFused() ?: Point(0.0, 0.0)

                records.add(
                    BenchmarkRecord(
                        timestamp = System.currentTimeMillis(),
                        truthX = Double.NaN, truthY = Double.NaN,
                        knnX = knnResult?.coordinate?.x ?: 0.0, knnY = knnResult?.coordinate?.y ?: 0.0,
                        wknnX = wknnResult?.coordinate?.x ?: 0.0, wknnY = wknnResult?.coordinate?.y ?: 0.0,
                        fusedX = fusedPos.x, fusedY = fusedPos.y,
                        validN = wknnResult?.validN ?: 0, d1 = wknnResult?.d1 ?: Double.MAX_VALUE,
                        rawRssi = raw, smoothedRssi = smoothed, envLabel = envLabel
                    )
                )
            }
        }
    }

    fun stopAndExport(context: Context) {
        val currentRecords = records.toList()
        cancelLogging() // 导出前停掉所有任务

        if (currentRecords.isEmpty()) {
            Toast.makeText(context, "无有效物理采样数据", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val metaData = """
                # [IndoorNavi_BLE System Benchmark Data / 室内定位系统基准测试原始数据]
                # ====================================================================
                # Time    : 采样时间戳 (毫秒 / ms)
                # TruthX/Y: 物理真值 (动态连续轨迹模式下为 NaN)
                # KNN_X/Y : 传统 K-Nearest Neighbors 算法预测坐标 (米 / m)
                # WKNN_X/Y: 距离倒数加权 WKNN 算法预测坐标 (米 / m)
                # Fused_X/Y: PDR 航位推算与 WKNN 互补滤波后的融合坐标 (米 / m)
                # Valid_N : 参与本次解算的有效特征维度数量 (表征环境盲区遮挡)
                # Min_D1  : 第一近邻归一化欧氏距离
                # Raw_RSSI: 锁定首选基站的原始信号强度 (dBm，用于滤波对比)
                # Sm_RSSI : 锁定首选基站的平滑滤波信号 (dBm)
                # EnvLabel: 环境上下文标签 (开阔/盲区/静止/走动)
                # ====================================================================
                
            """.trimIndent()

            val csvHeader = "Time,TruthX,TruthY,KNN_X,KNN_Y,WKNN_X,WKNN_Y,Fused_X,Fused_Y,Valid_N,Min_D1,Raw_RSSI,Smoothed_RSSI,Env_Label\n"

            val data = currentRecords.joinToString("\n") { record ->
                String.format(
                    Locale.US,
                    "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%d,%d,%s",
                    record.timestamp,
                    record.truthX, record.truthY,
                    record.knnX, record.knnY,
                    record.wknnX, record.wknnY,
                    record.fusedX, record.fusedY,
                    record.validN, record.d1,
                    record.rawRssi, record.smoothedRssi, record.envLabel
                )
            }

            val csvContent = "\uFEFF" + metaData + csvHeader + data

            val file = File(context.cacheDir, "IndoorNavi_Benchmark_${System.currentTimeMillis()}.csv")
            file.writeText(csvContent)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出带标注的基准测试数据"))

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "导出出错", Toast.LENGTH_LONG).show()
        }
    }
}