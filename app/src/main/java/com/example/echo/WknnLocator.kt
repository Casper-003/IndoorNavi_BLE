package com.example.echo

import kotlin.math.sqrt

// 物理坐标点
data class Point(val x: Double, val y: Double)

// 对应论文中的“参考点 (Reference Point, RP)”
data class ReferencePoint(
    val id: String,
    val coordinate: Point,
    val fingerprint: Map<String, Int>
)

// 🌟 新增：包含算法分析指标的详细返回体
data class LocateResult(
    val coordinate: Point,
    val d1: Double,       // 最小匹配特征距离 (用于评估信号环境异构度)
    val validN: Int       // 参与本次解算的有效特征维度数
)

/**
 * 核心定位解算器 (WKNN Algorithm Engine)
 */
class WknnLocator {

    private fun calculateEuclideanDistance(liveRssi: Map<String, Int>, rpFingerprint: Map<String, Int>): Double {
        val commonMacs = liveRssi.keys.intersect(rpFingerprint.keys)
        if (commonMacs.size < 2) return Double.MAX_VALUE

        var sumSq = 0.0
        for (mac in commonMacs) {
            val s_j = liveRssi[mac]!!
            val f_ij = rpFingerprint[mac]!!
            val diff = (s_j - f_ij).toDouble()
            sumSq += diff * diff
        }

        return sqrt(sumSq / commonMacs.size)
    }

    // 兼容原有的 UI 渲染调用
    fun locate(liveRssi: Map<String, Int>, database: List<ReferencePoint>, k: Int, useWknn: Boolean = true): Point? {
        return locateDetailed(liveRssi, database, k, useWknn)?.coordinate
    }

    // 🌟 核心：提供更详尽的解算结果，并修补精度与除零漏洞
    fun locateDetailed(
        liveRssi: Map<String, Int>,
        database: List<ReferencePoint>,
        k: Int,
        useWknn: Boolean = true
    ): LocateResult? {
        if (liveRssi.isEmpty() || database.isEmpty()) return null

        val distances = database.map { rp ->
            Triple(rp, calculateEuclideanDistance(liveRssi, rp.fingerprint), liveRssi.keys.intersect(rp.fingerprint.keys).size)
        }.filter { it.second != Double.MAX_VALUE }

        if (distances.isEmpty()) return null

        val sortedCandidates = distances.sortedBy { it.second }
        val nearestNeighbors = sortedCandidates.take(k)

        if (nearestNeighbors.isEmpty()) return null

        // 提取学术指标
        val d1 = sortedCandidates.first().second
        val validN = sortedCandidates.first().third

        val finalPoint = if (useWknn) {
            var weightSum = 0.0
            var xSum = 0.0
            var ySum = 0.0
            for (neighbor in nearestNeighbors) {
                val distance = neighbor.second
                // 🌟 修复：引入极小常量 epsilon (1e-6)，绝对杜绝物理重合导致的除零崩溃
                val safeDistance = distance + 1e-6
                val weight = 1.0 / safeDistance
                weightSum += weight
                xSum += neighbor.first.coordinate.x * weight
                ySum += neighbor.first.coordinate.y * weight
            }
            if (weightSum == 0.0) return null
            Point(xSum / weightSum, ySum / weightSum)
        } else {
            val avgX = nearestNeighbors.map { it.first.coordinate.x }.average()
            val avgY = nearestNeighbors.map { it.first.coordinate.y }.average()
            Point(avgX, avgY)
        }

        return LocateResult(finalPoint, d1, validN)
    }
}