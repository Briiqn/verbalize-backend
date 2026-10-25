package dev.briiqn.verbalize.domain.world

import dev.briiqn.verbalize.domain.model.Npc
import java.util.PriorityQueue
import kotlin.math.sqrt

/** Grid A* over blocked cells: 8-directional with corner-cut prevention. */
class Pathfinder(
    private val isBlocked: (Int, Int) -> Boolean
) {
    private val neighbors = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )

    private data class Node(val x: Int, val z: Int, val g: Double, val f: Double)

    fun findPath(sx: Int, sz: Int, tx: Int, tz: Int, maxExpansions: Int = 700, otherNpcs : List<Npc>): List<Pair<Int, Int>>? {
        if (isBlocked(tx, tz) ) return null
        fun h(x: Int, z: Int): Double {
            val dx = (tx - x).toDouble()
            val dz = (tz - z).toDouble()
            return sqrt(dx * dx + dz * dz)
        }
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(sx, sz, 0.0, h(sx, sz)))
        val cameFrom = HashMap<Long, Long>()
        val gScore = HashMap<Long, Double>()
        val closed = HashSet<Long>()
        gScore[WorldGen.packCell2(sx, sz)] = 0.0
        var expansions = 0

        while (open.isNotEmpty() && expansions++ < maxExpansions) {
            val current = open.poll()
            val currentKey = WorldGen.packCell2(current.x, current.z)
            if (!closed.add(currentKey)) continue

            if (current.x == tx && current.z == tz) {
                val path = ArrayList<Pair<Int, Int>>()
                var k: Long? = currentKey
                while (k != null) {
                    path.add(unpackX(k) to unpackZ(k))
                    k = cameFrom[k]
                }
                path.reverse()
                if (path.isNotEmpty()) path.removeAt(0)
                return path.ifEmpty { null }
            }

            for (n in neighbors) {
                val nx = current.x + n[0]
                val nz = current.z + n[1]
                if (isBlocked(nx, nz)) continue
                if (n[0] != 0 && n[1] != 0 && (isBlocked(current.x + n[0], current.z) || isBlocked(current.x, current.z + n[1])) || (otherNpcs.any{ (it.transform.xMm == current.x) && (it.transform.zMm  == current.z)}) || (otherNpcs.any{ (it.transform.xMm+it.transform.vxMm == current.x) && (it.transform.zMm+it.transform.vzMm  == current.z)})) {
                    continue
                }
                val neighborKey = WorldGen.packCell2(nx, nz)
                if (closed.contains(neighborKey)) continue
                val tentativeG = current.g + if (n[0] != 0 && n[1] != 0) 1.4142135623730951 else 1.0
                if (tentativeG >= (gScore[neighborKey] ?: Double.POSITIVE_INFINITY)) continue
                gScore[neighborKey] = tentativeG
                cameFrom[neighborKey] = currentKey
                open.add(Node(nx, nz, tentativeG, tentativeG + h(nx, nz)))
            }
        }
        return null
    }

    private fun unpackX(key: Long): Int = ((key shr 24) and 0xffffffL).toInt().let { if (it >= 0x800000) it - 0x1000000 else it }
    private fun unpackZ(key: Long): Int = (key and 0xffffffL).toInt().let { if (it >= 0x800000) it - 0x1000000 else it }
}
