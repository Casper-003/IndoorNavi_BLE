package com.example.indoornavi

import kotlin.math.sqrt

// 物理坐标点
data class Point(val x: Double, val y: Double)

// 对应论文中的“参考点 (Reference Point, RP)”
data class ReferencePoint(
    val id: String,                  // 比如 "点位A"
    val coordinate: Point,           // 物理坐标 (X, Y)
    val fingerprint: Map<String, Int> // 该点的指纹特征：MAC地址 -> 录入时的平均 RSSI
)

/**
 * 核心定位解算器
 * @param k 取距离最近的 K 个参考点进行加权计算 (默认取 3)
 */
class WknnLocator {

    private fun calculateEuclideanDistance(liveRssi: Map<String, Int>, rpFingerprint: Map<String, Int>): Double {
        var sumSq = 0.0
        val allMacs = liveRssi.keys + rpFingerprint.keys
        for (mac in allMacs) {
            val s_j = liveRssi[mac] ?: -100
            val f_ij = rpFingerprint[mac] ?: -100
            val diff = (s_j - f_ij).toDouble()
            sumSq += diff * diff
        }
        return sqrt(sumSq)
    }

    /**
     * @param k 取最近的 K 个参考点
     * @param useWknn true: 使用加权(WKNN), false: 使用算术平均(传统KNN)
     */
    fun locate(
        liveRssi: Map<String, Int>,
        database: List<ReferencePoint>,
        k: Int,
        useWknn: Boolean = true
    ): Point? {
        if (liveRssi.isEmpty() || database.isEmpty()) return null

        // 1. 计算距离
        val distances = database.map { rp ->
            rp to calculateEuclideanDistance(liveRssi, rp.fingerprint)
        }

        // 2. 取最近的 K 个点
        val nearestNeighbors = distances.sortedBy { it.second }.take(k)
        if (nearestNeighbors.isEmpty()) return null

        // 3. 算法分流解算
        if (useWknn) {
            // WKNN: 根据距离倒数加权 (距离越近，权重越大)
            var weightSum = 0.0
            var xSum = 0.0
            var ySum = 0.0
            for ((rp, distance) in nearestNeighbors) {
                val safeDistance = if (distance == 0.0) 0.001 else distance
                val weight = 1.0 / safeDistance
                weightSum += weight
                xSum += rp.coordinate.x * weight
                ySum += rp.coordinate.y * weight
            }
            if (weightSum == 0.0) return null
            return Point(xSum / weightSum, ySum / weightSum)
        } else {
            // 传统 KNN: 直接取 K 个点的物理坐标算术平均值 (论文里的 Baseline)
            val avgX = nearestNeighbors.map { it.first.coordinate.x }.average()
            val avgY = nearestNeighbors.map { it.first.coordinate.y }.average()
            return Point(avgX, avgY)
        }
    }
}