package com.example.echo

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

// ================= A* 离散空间网格寻路引擎 =================
class AStarPathfinder {

    data class Node(
        val col: Int,
        val row: Int,
        var g: Double = Double.MAX_VALUE,
        var h: Double = 0.0,
        var parent: Node? = null
    ) : Comparable<Node> {
        val f: Double get() = g + h
        override fun compareTo(other: Node): Int = this.f.compareTo(other.f)
    }

    // BFS 自动越狱机制：解决 PDR 漂移导致起点掉进墙里的问题
    private fun findNearestWalkable(startC: Int, startR: Int, cols: Int, rows: Int, isObstacle: Array<BooleanArray>): Pair<Int, Int>? {
        if (startC in 0 until cols && startR in 0 until rows && !isObstacle[startC][startR]) {
            return Pair(startC, startR)
        }

        val queue = ArrayDeque<Pair<Int, Int>>()
        val visited = mutableSetOf<String>()
        queue.add(Pair(startC, startR))
        visited.add("${startC}_${startR}")

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val c = curr.first
            val r = curr.second

            if (c in 0 until cols && r in 0 until rows && !isObstacle[c][r]) {
                return curr
            }

            val dirs = listOf(Pair(0,1), Pair(0,-1), Pair(1,0), Pair(-1,0), Pair(1,1), Pair(-1,-1), Pair(1,-1), Pair(-1,1))
            for (d in dirs) {
                val nc = c + d.first
                val nr = r + d.second
                if (kotlin.math.abs(nc - startC) <= 3 && kotlin.math.abs(nr - startR) <= 3) {
                    val key = "${nc}_${nr}"
                    if (visited.add(key)) {
                        queue.add(Pair(nc, nr))
                    }
                }
            }
        }
        return null
    }

    fun findPath(
        start: Point,
        target: Point,
        spaceWidth: Double,
        spaceLength: Double,
        gridResolution: Double,
        obstacles: List<Point>
    ): List<Point> {
        // 🌟 核心修复 1：去掉 `+ 1`！
        // 彻底关闭“外太空网格”漏洞，把 A* 算法的搜索范围死死锁在物理地图内部！
        val cols = ceil(spaceWidth / gridResolution).toInt()
        val rows = ceil(spaceLength / gridResolution).toInt()

        if (cols <= 0 || rows <= 0) return emptyList()

        val isObstacle = Array(cols) { BooleanArray(rows) }
        for (obs in obstacles) {
            val c = floor(obs.x / gridResolution).toInt()
            val r = floor(obs.y / gridResolution).toInt()
            if (c in 0 until cols && r in 0 until rows) {
                isObstacle[c][r] = true
            }
        }

        val rawStartC = floor(start.x / gridResolution).toInt()
        val rawStartR = floor(start.y / gridResolution).toInt()
        val rawTargetC = floor(target.x / gridResolution).toInt()
        val rawTargetR = floor(target.y / gridResolution).toInt()

        // 使用 BFS 确保起终点绝对安全，不怕 PDR 漂移
        val safeStart = findNearestWalkable(rawStartC, rawStartR, cols, rows, isObstacle) ?: return emptyList()
        val safeTarget = findNearestWalkable(rawTargetC, rawTargetR, cols, rows, isObstacle) ?: return emptyList()

        val startCol = safeStart.first
        val startRow = safeStart.second
        val targetCol = safeTarget.first
        val targetRow = safeTarget.second

        val openList = PriorityQueue<Node>()
        val closedSet = mutableSetOf<String>()
        val allNodes = mutableMapOf<String, Node>()

        fun getNodeId(c: Int, r: Int) = "${c}_${r}"

        val startNode = Node(startCol, startRow, g = 0.0, h = calculateHeuristic(startCol, startRow, targetCol, targetRow))
        openList.add(startNode)
        allNodes[getNodeId(startCol, startRow)] = startNode

        val directions = listOf(
            Pair(0, -1) to 1.0, Pair(0, 1) to 1.0, Pair(-1, 0) to 1.0, Pair(1, 0) to 1.0,
            Pair(-1, -1) to 1.414, Pair(1, -1) to 1.414, Pair(-1, 1) to 1.414, Pair(1, 1) to 1.414
        )

        var targetNode: Node? = null

        while (openList.isNotEmpty()) {
            val current = openList.poll() ?: break

            if (current.col == targetCol && current.row == targetRow) {
                targetNode = current
                break
            }

            closedSet.add(getNodeId(current.col, current.row))

            for ((dir, cost) in directions) {
                val neighborCol = current.col + dir.first
                val neighborRow = current.row + dir.second

                if (neighborCol !in 0 until cols || neighborRow !in 0 until rows) continue
                if (isObstacle[neighborCol][neighborRow]) continue

                // 🌟 核心修复 2：恢复强硬的 `||` 穿模拦截
                // 只要相邻两格有【任何一格】是墙壁，就禁止走对角线！
                // 这强迫 A* 遇到转角必须走 90° 拐角，为后续的拉线平滑提供完美的居中基础
                if (cost > 1.0) {
                    if (isObstacle[current.col][neighborRow] || isObstacle[neighborCol][current.row]) {
                        continue
                    }
                }

                val neighborId = getNodeId(neighborCol, neighborRow)
                if (closedSet.contains(neighborId)) continue

                val tentativeG = current.g + cost
                var neighborNode = allNodes[neighborId]

                if (neighborNode == null) {
                    neighborNode = Node(
                        col = neighborCol,
                        row = neighborRow,
                        g = tentativeG,
                        h = calculateHeuristic(neighborCol, neighborRow, targetCol, targetRow),
                        parent = current
                    )
                    allNodes[neighborId] = neighborNode
                    openList.add(neighborNode)
                } else if (tentativeG < neighborNode.g) {
                    neighborNode.g = tentativeG
                    neighborNode.parent = current
                    openList.remove(neighborNode)
                    openList.add(neighborNode)
                }
            }
        }

        if (targetNode == null) return emptyList()

        val pathNodes = mutableListOf<Node>()
        var curr: Node? = targetNode
        while (curr != null) {
            pathNodes.add(curr)
            curr = curr.parent
        }
        pathNodes.reverse()

        val physicalPath = pathNodes.map { node ->
            Point(
                x = (node.col * gridResolution) + (gridResolution / 2.0),
                y = (node.row * gridResolution) + (gridResolution / 2.0)
            )
        }.toMutableList()

        if (physicalPath.isNotEmpty()) {
            val safeMinX = 0.0
            val safeMaxX = spaceWidth
            val safeMinY = 0.0
            val safeMaxY = spaceLength

            physicalPath[0] = Point(start.x.coerceIn(safeMinX, safeMaxX), start.y.coerceIn(safeMinY, safeMaxY))
            physicalPath[physicalPath.size - 1] = Point(target.x.coerceIn(safeMinX, safeMaxX), target.y.coerceIn(safeMinY, safeMaxY))
        }

        return smoothPath(physicalPath, gridResolution, isObstacle, cols, rows)
    }

    private fun calculateHeuristic(c1: Int, r1: Int, c2: Int, r2: Int): Double {
        val dx = kotlin.math.abs(c1 - c2).toDouble()
        val dy = kotlin.math.abs(r1 - r2).toDouble()
        val h = (dx + dy) + (1.414 - 2.0) * minOf(dx, dy)
        return h * 1.001
    }

    private fun smoothPath(path: List<Point>, s: Double, isObstacle: Array<BooleanArray>, cols: Int, rows: Int): List<Point> {
        if (path.size <= 2) return path

        val smoothed = mutableListOf<Point>()
        smoothed.add(path.first())

        var currentIndex = 0
        while (currentIndex < path.size - 1) {
            var furthest = currentIndex + 1
            for (i in currentIndex + 2 until path.size) {
                if (hasLineOfSight(path[currentIndex], path[i], s, isObstacle, cols, rows)) {
                    furthest = i
                } else {
                    break
                }
            }
            smoothed.add(path[furthest])
            currentIndex = furthest
        }
        return smoothed
    }

    private fun hasLineOfSight(p1: Point, p2: Point, s: Double, isObstacle: Array<BooleanArray>, cols: Int, rows: Int): Boolean {
        val dist = hypot(p2.x - p1.x, p2.y - p1.y)
        if (dist == 0.0) return true

        val stepSize = s / 10.0
        val steps = ceil(dist / stepSize).toInt()

        // 🌟 核心修复 3：随地图格子自动调节的巨型体积判定！
        // 强制占据格子 80% 的宽度 (0.4 * s * 2 = 0.8s)。
        // 这赋予了导航线真实的物理体积，它再也不能擦着棱角抄近路了，必须乖乖走在正中间。
        val margin = s * 0.4

        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val cx = p1.x + t * (p2.x - p1.x)
            val cy = p1.y + t * (p2.y - p1.y)

            val checkPoints = listOf(
                Point(cx, cy),
                Point(cx - margin, cy),
                Point(cx + margin, cy),
                Point(cx, cy - margin),
                Point(cx, cy + margin),
                Point(cx - margin, cy - margin),
                Point(cx + margin, cy - margin),
                Point(cx - margin, cy + margin),
                Point(cx + margin, cy + margin)
            )

            for (pt in checkPoints) {
                val c = floor(pt.x / s).toInt()
                val r = floor(pt.y / s).toInt()

                // 如果胖射线的边缘探出了地图，直接判定失败，绝不借道
                if (c !in 0 until cols || r !in 0 until rows) return false
                // 如果蹭到实心墙，立刻判定失败
                if (isObstacle[c][r]) return false
            }
        }
        return true
    }
}