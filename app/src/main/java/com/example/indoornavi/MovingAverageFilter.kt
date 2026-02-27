package com.example.indoornavi

/**
 * 移动平均滤波 (Moving Average Filtering)
 * 对应论文 2.2.2 节算法：平滑 RSSI 信号毛刺
 */
class MovingAverageFilter(private val windowSize: Int = 5) {

    // 滑动窗口队列
    private val window = ArrayDeque<Int>(windowSize)

    fun addAndGetAverage(newRssi: Int): Int {
        // 如果窗口满了，踢出最老的值
        if (window.size >= windowSize) {
            window.removeFirst()
        }
        // 加入最新值
        window.addLast(newRssi)
        // 计算平均值
        return window.sum() / window.size
    }

    fun clear() {
        window.clear()
    }
}