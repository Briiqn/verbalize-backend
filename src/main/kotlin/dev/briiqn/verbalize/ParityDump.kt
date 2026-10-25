package dev.briiqn.verbalize

import dev.briiqn.verbalize.domain.simulation.Simulation
import dev.briiqn.verbalize.domain.world.WorldGen
import kotlin.math.abs
import kotlin.math.floor

/** Prints a canonical digest of worldgen + a scripted sim trajectory for a seed.
 *  Must byte-match the node counterpart's output for determinism parity. */
object ParityDump {
    /** JS-style number formatting: integral doubles print without a decimal point. */
    private fun fmt(d: Double): String =
        if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    fun run(seed32: Int) {
        val f = WorldGen.generate(seed32)

        val cells = f.staticCells.entries
            .map { (k, v) ->
                val y = ((k shr 48) and 0xffL).toInt()
                val x = ((k shr 24) and 0xffffffL).toInt().let { if (it >= 0x800000) it - 0x1000000 else it }
                val z = (k and 0xffffffL).toInt().let { if (it >= 0x800000) it - 0x1000000 else it }
                "$x,$y,$z=$v"
            }
            .sorted()
        println("staticCells:${cells.size}:${cells.joinToString(";")}")
        println("trees:${f.trees.size}:" + f.trees.joinToString(";") {
            "${fmt(it.x)},${fmt(it.z)},${fmt(it.trunkHeight)},${fmt(it.foliageRadius)},${fmt(it.tint)}"
        })
        println("buildings:${f.buildings.size}:" + f.buildings.joinToString(";") {
            "${it.type},${it.height},${it.x0},${it.z0},${it.w},${it.d},${it.doorX},${it.doorZ},${it.frontX},${it.frontZ}"
        })
        println("lamps:" + f.lamps.joinToString(";") { "${fmt(it.x)},${fmt(it.z)}" })
        println("obstacles:${f.obstacles.size}:" + f.obstacles.joinToString(";") {
            "${fmt(it.x)},${fmt(it.z)},${fmt(it.r)}"
        })

        var pos = Simulation.Vec3(0.5, 0.0, 0.5)
        var vel = Simulation.Vec3(0.0, 0.0, 0.0)
        val query = Simulation.StructureQuery { gx, gy, gz -> f.structureTypeAt(gx, gy, gz) }
        val obstacles = listOf(Simulation.Obstacle(3.0, 0.0, 0.5, Simulation.ENTITY_BODY_RADIUS))
        val samples = StringBuilder()
        for (i in 0 until 200) {
            val key = when {
                i < 40 -> 1
                i < 80 -> 8 or 16
                i < 120 -> 2 or 32
                else -> 0
            }
            val yaw = when {
                i < 40 -> i * 37
                i < 80 -> 500
                i < 120 -> -1234
                else -> 0
            }
            val r = Simulation.step(pos, vel, key, yaw, query, obstacles)
            pos = r.nextPos
            vel = r.velocity
            if (i % 20 == 19) {
                samples.append("${fmt(pos.x)},${fmt(pos.y)},${fmt(pos.z)},${fmt(vel.x)},${fmt(vel.y)},${fmt(vel.z)};")
            }
        }
        println("trajectory:$samples")
    }
}
