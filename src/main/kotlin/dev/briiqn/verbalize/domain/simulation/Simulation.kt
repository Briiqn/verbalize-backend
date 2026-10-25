package dev.briiqn.verbalize.domain.simulation

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic shared kinematics. This is a 1:1 port of the client's
 * src/core/simulation.js — operation order, constants and Math.round
 * (half-up) semantics must stay bit-identical so client prediction and
 * server authority agree exactly.
 */
object Simulation {
    const val SERVER_TPS = 20
    const val TICK_DT = 1.0 / SERVER_TPS
    const val WALK_SPEED = 6.0
    const val SPRINT_SPEED = 10.0
    const val JUMP_VELOCITY = 6.7
    const val GRAVITY = 18.0
    const val AIR_FRICTION = 0.8
    const val AIR_CONTROL = 3.0
    const val PLAYER_HALF_EXTENT = 0.35
    const val PLAYER_HEIGHT = 1.8
    const val ENTITY_BODY_RADIUS = 0.28
    const val WORLD_RADIUS = 48

    private const val SKIN = 0.001
    private const val EPS = 0.0005

    data class Vec3(val x: Double, val y: Double, val z: Double)

    data class Obstacle(val x: Double, val y: Double, val z: Double, val r: Double)

    data class StepResult(val nextPos: Vec3, val velocity: Vec3, val grounded: Boolean)

    /** structureTypeAt(gx, gy, gz) -> 0 when empty */
    fun interface StructureQuery {
        fun typeAt(gx: Int, gy: Int, gz: Int): Int
    }

    private fun roundJs(v: Double): Double = Math.round(v).toDouble()

    private fun collectAabbs(
        query: StructureQuery,
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        out: MutableList<DoubleArray>
    ) {
        val gx0 = floor(minX).toInt()
        val gx1 = floor(maxX).toInt()
        val gy0 = maxOf(0, floor(minY).toInt())
        val gy1 = floor(maxY).toInt()
        val gz0 = floor(minZ).toInt()
        val gz1 = floor(maxZ).toInt()
        for (gx in gx0..gx1) {
            for (gy in gy0..gy1) {
                for (gz in gz0..gz1) {
                    val type = query.typeAt(gx, gy, gz)
                    if (type == 0) continue
                    val bx0 = gx.toDouble()
                    val by0 = gy.toDouble()
                    val bz0 = gz.toDouble()
                    val bx1 = bx0 + 1.0
                    val by1 = by0 + 1.0
                    val bz1 = bz0 + 1.0
                    if (bx1 > minX && bx0 < maxX && by1 > minY && by0 < maxY && bz1 > minZ && bz0 < maxZ) {
                        out.add(doubleArrayOf(bx0, by0, bz0, bx1, by1, bz1))
                    }
                }
            }
        }
    }

    private fun hasSupport(pos: Vec3, query: StructureQuery): Boolean {
        if (pos.y <= EPS) return true
        val h = PLAYER_HALF_EXTENT
        val boxes = ArrayList<DoubleArray>(4)
        collectAabbs(
            query,
            pos.x - h + SKIN, pos.y - 0.002, pos.z - h + SKIN,
            pos.x + h - SKIN, pos.y + EPS, pos.z + h - SKIN,
            boxes
        )
        for (b in boxes) {
            if (kotlin.math.abs(b[4] - pos.y) <= 0.002) return true
        }
        return false
    }

    fun step(
        pos: Vec3,
        velIn: Vec3,
        keyBitmask: Int,
        yawMrad: Int,
        query: StructureQuery,
        obstacles: List<Obstacle>,
        dt: Double = TICK_DT
    ): StepResult {
        val speed = if ((keyBitmask and 32) != 0) SPRINT_SPEED else WALK_SPEED
        val yawRad = yawMrad / 1000.0
        val sinYaw = sin(yawRad)
        val cosYaw = cos(yawRad)

        var moveX = 0.0
        var moveZ = 0.0
        if ((keyBitmask and 1) != 0) { moveX += sinYaw; moveZ -= cosYaw }
        if ((keyBitmask and 2) != 0) { moveX -= sinYaw; moveZ += cosYaw }
        if ((keyBitmask and 4) != 0) { moveX -= cosYaw; moveZ -= sinYaw }
        if ((keyBitmask and 8) != 0) { moveX += cosYaw; moveZ += sinYaw }

        val mag = sqrt(moveX * moveX + moveZ * moveZ)
        var wishX = 0.0
        var wishZ = 0.0
        if (mag > 0.001) {
            wishX = (moveX / mag) * speed
            wishZ = (moveZ / mag) * speed
        }

        val grounded = velIn.y <= 0 && hasSupport(pos, query)
        var vx: Double
        var vz: Double
        var vy = velIn.y

        if (grounded) {
            vx = wishX
            vz = wishZ
            if ((keyBitmask and 16) != 0 && vy <= 0) {
                vy = JUMP_VELOCITY
            }
        } else {
            vx = velIn.x * (1.0 - AIR_FRICTION * dt) + wishX * AIR_CONTROL * dt
            vz = velIn.z * (1.0 - AIR_FRICTION * dt) + wishZ * AIR_CONTROL * dt
            val hMag = sqrt(vx * vx + vz * vz)
            if (hMag > speed) {
                vx = (vx / hMag) * speed
                vz = (vz / hMag) * speed
            }
        }

        vy -= GRAVITY * dt

        vx = roundJs(vx * 1000) / 1000
        vz = roundJs(vz * 1000) / 1000
        vy = roundJs(vy * 1000) / 1000

        val h = PLAYER_HALF_EXTENT
        val ph = PLAYER_HEIGHT
        var x = pos.x
        var y = pos.y
        var z = pos.z

        if (vx != 0.0) {
            val target = x + vx * dt
            var nx = target
            var hit = false
            val boxes = ArrayList<DoubleArray>(4)
            collectAabbs(
                query,
                minOf(x, target) - h, y + SKIN, z - h + SKIN,
                maxOf(x, target) + h, y + ph - SKIN, z + h - SKIN,
                boxes
            )
            for (b in boxes) {
                if (vx > 0 && x + h <= b[0] + SKIN && target + h > b[0]) {
                    nx = minOf(nx, b[0] - h - SKIN)
                    hit = true
                } else if (vx < 0 && x - h >= b[3] - SKIN && target - h < b[3]) {
                    nx = maxOf(nx, b[3] + h + SKIN)
                    hit = true
                }
            }
            if (hit) vx = 0.0
            x = nx
        }

        if (vz != 0.0) {
            val target = z + vz * dt
            var nz = target
            var hit = false
            val boxes = ArrayList<DoubleArray>(4)
            collectAabbs(
                query,
                x - h + SKIN, y + SKIN, minOf(z, target) - h,
                x + h - SKIN, y + ph - SKIN, maxOf(z, target) + h,
                boxes
            )
            for (b in boxes) {
                if (vz > 0 && z + h <= b[2] + SKIN && target + h > b[2]) {
                    nz = minOf(nz, b[2] - h - SKIN)
                    hit = true
                } else if (vz < 0 && z - h >= b[5] - SKIN && target - h < b[5]) {
                    nz = maxOf(nz, b[5] + h + SKIN)
                    hit = true
                }
            }
            if (hit) vz = 0.0
            z = nz
        }

        for (o in obstacles) {
            if (kotlin.math.abs(y - o.y) > 1.7) continue
            val odx = x - o.x
            val odz = z - o.z
            val minDist = o.r + h
            val d2 = odx * odx + odz * odz
            if (d2 >= minDist * minDist) continue
            if (d2 < 1e-8) {
                x += minDist
                continue
            }
            val d = sqrt(d2)
            val push = minDist - d
            x += (odx / d) * push
            z += (odz / d) * push
        }

        val bound = WORLD_RADIUS - h
        if (x > bound) { x = bound; if (vx > 0) vx = 0.0 }
        else if (x < -bound) { x = -bound; if (vx < 0) vx = 0.0 }
        if (z > bound) { z = bound; if (vz > 0) vz = 0.0 }
        else if (z < -bound) { z = -bound; if (vz < 0) vz = 0.0 }

        if (vy != 0.0) {
            val target = y + vy * dt
            var ny = target
            if (vy < 0) {
                var landed = false
                val boxes = ArrayList<DoubleArray>(4)
                collectAabbs(
                    query,
                    x - h + SKIN, target, z - h + SKIN,
                    x + h - SKIN, y, z + h - SKIN,
                    boxes
                )
                for (b in boxes) {
                    if (b[4] <= y + EPS && target < b[4]) {
                        ny = maxOf(ny, b[4])
                        landed = true
                    }
                }
                if (ny <= 0) {
                    ny = 0.0
                    landed = true
                }
                if (landed) vy = 0.0
            } else {
                var bumped = false
                val boxes = ArrayList<DoubleArray>(4)
                collectAabbs(
                    query,
                    x - h + SKIN, y + ph, z - h + SKIN,
                    x + h - SKIN, target + ph, z + h - SKIN,
                    boxes
                )
                for (b in boxes) {
                    if (b[1] >= y + ph - EPS && target + ph > b[1]) {
                        ny = minOf(ny, b[1] - ph - SKIN)
                        bumped = true
                    }
                }
                if (bumped) vy = 0.0
            }
            y = ny
        }

        x = roundJs(x * 1000) / 1000
        y = roundJs(y * 1000) / 1000
        z = roundJs(z * 1000) / 1000
        if (y < 0) y = 0.0

        return StepResult(Vec3(x, y, z), Vec3(vx, vy, vz), grounded)
    }
}
