package dev.briiqn.verbalize.domain.world

import dev.briiqn.verbalize.domain.simulation.Simulation
import kotlin.math.floor

/**
 * 1:1 port of the client's src/core/worldgen.js. The rand() call order and
 * integer/float expression shapes must match exactly — both sides derive the
 * village (collision cells, obstacles, spawns) independently from the seed.
 */
object WorldGen {
    const val TREE_TRUNK_RADIUS = 0.3
    const val PLAZA_RADIUS = 5.5
    private const val TREE_COUNT = 36
    private const val TREE_MARGIN = 3
    private const val TREE_MIN_SPACING_SQ = 25.0
    private const val MAX_BUILDINGS = 9
    private const val AVENUE_END = 42
    private const val WALL_CELL_TYPE = 9
    private val WORLD_RADIUS = Simulation.WORLD_RADIUS

    class SplitMix32(seed: Int) {
        private var a = seed
        fun next(): Double {
            a += 0x9e3779b9.toInt()
            var t = a xor (a ushr 16)
            t *= 0x21f0aaad
            t = t xor (t ushr 15)
            t *= 0x735a2d97
            t = t xor (t ushr 15)
            return (t.toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    data class Tree(val x: Double, val z: Double, val trunkHeight: Double, val foliageRadius: Double, val tint: Double)
    data class Lamp(val x: Double, val z: Double)
    data class Prop(val x: Double, val z: Double, val kind: Int)
    data class Building(
        val type: Int, val height: Int, val tint: Double,
        val x0: Int, val z0: Int, val w: Int, val d: Int,
        val doorX: Int, val doorZ: Int,
        val doorX2: Int, val doorZ2: Int,
        val frontX: Int, val frontZ: Int,
        val wallCells: List<Pair<Int, Int>>,
        val props: List<Prop>
    )
    data class SpawnPoint(val x: Double, val z: Double)

    class Features(
        val trees: List<Tree>,
        val obstacles: List<Simulation.Obstacle>,
        val staticCells: HashMap<Long, Int>,
        val blockedCells: HashSet<Long>,
        val pathCells: HashSet<Long>,
        val buildings: List<Building>,
        val lamps: List<Lamp>,
        val spawnPoints: List<SpawnPoint>,
        val npcSpots: List<SpawnPoint>
    ) {
        fun structureTypeAt(gx: Int, gy: Int, gz: Int): Int =
            staticCells[packCell3(gx, gy, gz)] ?: 0
    }

    fun packCell2(x: Int, z: Int): Long =
        ((x.toLong() and 0xffffffL) shl 24) or (z.toLong() and 0xffffffL)

    fun packCell3(x: Int, y: Int, z: Int): Long =
        ((y.toLong() and 0xffL) shl 48) or ((x.toLong() and 0xffffffL) shl 24) or (z.toLong() and 0xffffffL)

    fun generate(seed32: Int): Features {
        val rand = SplitMix32(seed32)

        val pathCells = HashSet<Long>()
        val pathList = ArrayList<Pair<Int, Int>>()
        fun addPath(x: Int, z: Int) {
            val k = packCell2(x, z)
            if (pathCells.add(k)) pathList.add(x to z)
        }

        val pr = kotlin.math.ceil(PLAZA_RADIUS).toInt()
        for (x in -pr - 1..pr) {
            for (z in -pr - 1..pr) {
                val cx = x + 0.5
                val cz = z + 0.5
                if (cx * cx + cz * cz <= PLAZA_RADIUS * PLAZA_RADIUS) addPath(x, z)
            }
        }

        val towerCells = ArrayList<Pair<Int, Int>>()
        for (x in -1..1) for (z in -1..1) towerCells.add(x to z)

        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((dx, dz) in dirs) {
            for (t in 3..AVENUE_END) {
                for (off in -1..1) {
                    addPath(if (dx != 0) dx * t else off, if (dz != 0) dz * t else off)
                }
            }
        }

        val buildingReserve = HashSet<Long>()
        fun reserveFootprint(x0: Int, z0: Int, w: Int, d: Int) {
            for (x in x0 - 1..x0 + w) for (z in z0 - 1..z0 + d) buildingReserve.add(packCell2(x, z))
        }
        fun footprintConflicts(x0: Int, z0: Int, w: Int, d: Int): Boolean {
            for (x in x0 - 1..x0 + w) for (z in z0 - 1..z0 + d) {
                if (buildingReserve.contains(packCell2(x, z))) return true
            }
            return false
        }

        val buildings = ArrayList<Building>()
        val slotBases = intArrayOf(10, 17, 24, 31)
        var slotIndex = 0

        outer@ for (round in 0 until 2) {
        for ((dirX, dirZ) in dirs) {
            for (side in intArrayOf(1, -1)) {
                if (buildings.size >= MAX_BUILDINGS) break@outer
                if (round == 1 && rand.next() < 0.35) {
                    slotIndex++
                    continue
                }
                val along0 = slotBases[slotIndex++ % slotBases.size] + floor(rand.next() * 3).toInt()
                val w = 5 + floor(rand.next() * 4).toInt()
                val depth = 5 + floor(rand.next() * 4).toInt()
                if (along0 + w >= WORLD_RADIUS - 4) continue

                val x0: Int; val z0: Int; val bw: Int; val bd: Int
                val doorX: Int; val doorZ: Int; val doorX2: Int; val doorZ2: Int
                val frontX: Int; val frontZ: Int
                if (dirX != 0) {
                    x0 = if (dirX > 0) along0 else -(along0 + w)
                    z0 = if (side > 0) 2 else -(1 + depth)
                    bw = w
                    bd = depth
                    doorZ = if (side > 0) z0 else z0 + depth - 1
                    doorX = x0 + w / 2 - 1
                    doorX2 = doorX + 1
                    doorZ2 = doorZ
                    frontX = doorX
                    frontZ = if (side > 0) doorZ - 1 else doorZ + 1
                } else {
                    z0 = if (dirZ > 0) along0 else -(along0 + w)
                    x0 = if (side > 0) 2 else -(1 + depth)
                    bw = depth
                    bd = w
                    doorX = if (side > 0) x0 else x0 + depth - 1
                    doorZ = z0 + w / 2 - 1
                    doorZ2 = doorZ + 1
                    doorX2 = doorX
                    frontZ = doorZ
                    frontX = if (side > 0) doorX - 1 else doorX + 1
                }
                if (footprintConflicts(x0, z0, bw, bd)) continue

                fun isDoor(x: Int, z: Int) = (x == doorX && z == doorZ) || (x == doorX2 && z == doorZ2)
                val wallCells = ArrayList<Pair<Int, Int>>()
                for (x in x0 until x0 + bw) {
                    for (z in z0 until z0 + bd) {
                        val edge = x == x0 || x == x0 + bw - 1 || z == z0 || z == z0 + bd - 1
                        if (!edge || isDoor(x, z)) continue
                        wallCells.add(x to z)
                    }
                }

                val props = ArrayList<Prop>()
                val propCount = 1 + floor(rand.next() * 2).toInt()
                for (p in 0 until propCount) {
                    val px: Double
                    val pz: Double
                    if (doorZ == z0 || doorZ == z0 + bd - 1) {
                        pz = if (doorZ == z0) z0 + bd - 1.5 else z0 + 1.5
                        px = x0 + 1.6 + rand.next() * maxOf(0.5, (bw - 3.2))
                    } else {
                        px = if (doorX == x0) x0 + bw - 1.5 else x0 + 1.5
                        pz = z0 + 1.6 + rand.next() * maxOf(0.5, (bd - 3.2))
                    }
                    props.add(Prop(px, pz, floor(rand.next() * 2).toInt()))
                }

                reserveFootprint(x0, z0, bw, bd)
                // types: 0 cottage, 1 forge/workshop, 2 warehouse, 3 two-story house, 4 shop
                val type = if (buildings.size == 0) 1 else if (buildings.size == 1) 2 else floor(rand.next() * 5).toInt()
                val height = if (type == 3) 3 else 2
                buildings.add(
                    Building(
                        type = type,
                        height = height,
                        tint = rand.next(),
                        x0 = x0, z0 = z0, w = bw, d = bd,
                        doorX = doorX, doorZ = doorZ,
                        doorX2 = doorX2, doorZ2 = doorZ2,
                        frontX = frontX, frontZ = frontZ,
                        wallCells = wallCells,
                        props = props
                    )
                )
            }
        }
        }

        val lamps = ArrayList<Lamp>()
        outer@ for ((dirX, dirZ) in dirs) {
            for (t in intArrayOf(8, 15, 22, 28)) {
                if (lamps.size >= 12) break@outer
                val side = if (t % 2 == 0) 1.8 else -1.8
                val lx = if (dirX != 0) dirX * t + 0.5 else side
                val lz = if (dirZ != 0) dirZ * t + 0.5 else side
                if (buildingReserve.contains(packCell2(floor(lx).toInt(), floor(lz).toInt()))) continue
                if (buildings.any { b ->
                        kotlin.math.abs(lx - (b.frontX + 0.5)) < 2.5 && kotlin.math.abs(lz - (b.frontZ + 0.5)) < 2.5
                    }) continue
                lamps.add(Lamp(lx, lz))
            }
        }

        val staticCells = HashMap<Long, Int>()
        val blockedCells = HashSet<Long>()
        fun addWall(x: Int, z: Int, height: Int) {
            for (y in 0 until height) staticCells[packCell3(x, y, z)] = WALL_CELL_TYPE
            blockedCells.add(packCell2(x, z))
        }
        for (b in buildings) {
            for ((cx, cz) in b.wallCells) addWall(cx, cz, b.height)
            if (b.height == 3) {
                staticCells[packCell3(b.doorX, 2, b.doorZ)] = WALL_CELL_TYPE
                staticCells[packCell3(b.doorX2, 2, b.doorZ2)] = WALL_CELL_TYPE
            }
        }
        for ((cx, cz) in towerCells) addWall(cx, cz, 3)

        fun nearVillage(gx: Int, gz: Int): Boolean {
            for (x in gx - 1..gx + 1) {
                for (z in gz - 1..gz + 1) {
                    val k = packCell2(x, z)
                    if (pathCells.contains(k) || buildingReserve.contains(k)) return true
                }
            }
            return false
        }

        val trees = ArrayList<Tree>()
        var attempt = 0
        while (attempt < 600 && trees.size < TREE_COUNT) {
            attempt++
            val x = Math.round((rand.next() * 2 - 1) * (WORLD_RADIUS - TREE_MARGIN) * 2).toDouble() / 2
            val z = Math.round((rand.next() * 2 - 1) * (WORLD_RADIUS - TREE_MARGIN) * 2).toDouble() / 2
            if (x * x + z * z < (PLAZA_RADIUS + 3) * (PLAZA_RADIUS + 3)) continue
            if (nearVillage(floor(x).toInt(), floor(z).toInt())) continue
            var spaced = true
            for (t in trees) {
                val dx = t.x - x
                val dz = t.z - z
                if (dx * dx + dz * dz < TREE_MIN_SPACING_SQ) {
                    spaced = false
                    break
                }
            }
            if (!spaced) continue
            trees.add(Tree(x, z, 1.8 + rand.next() * 1.5, 0.9 + rand.next() * 0.7, rand.next()))
            blockedCells.add(packCell2(floor(x).toInt(), floor(z).toInt()))
        }

        val obstacles = ArrayList<Simulation.Obstacle>()
        for (t in trees) obstacles.add(Simulation.Obstacle(t.x, 0.0, t.z, TREE_TRUNK_RADIUS))
        for (l in lamps) obstacles.add(Simulation.Obstacle(l.x, 0.0, l.z, 0.14))
        for (b in buildings) for (p in b.props) obstacles.add(Simulation.Obstacle(p.x, 0.0, p.z, 0.35))

        val spawnPoints = ArrayList<SpawnPoint>()
        for (i in 0 until 8) {
            val a = (i / 8.0) * Math.PI * 2
            spawnPoints.add(SpawnPoint(kotlin.math.sin(a) * (PLAZA_RADIUS - 1.4), -kotlin.math.cos(a) * (PLAZA_RADIUS - 1.4)))
        }
        val npcSpots = ArrayList<SpawnPoint>()
        for (b in buildings.take(3)) npcSpots.add(SpawnPoint(b.frontX + 0.5, b.frontZ + 0.5))
        while (npcSpots.size < 3) npcSpots.add(SpawnPoint(3.0 + npcSpots.size, 3.0))

        return Features(
            trees = trees,
            obstacles = obstacles,
            staticCells = staticCells,
            blockedCells = blockedCells,
            pathCells = pathCells,
            buildings = buildings,
            lamps = lamps,
            spawnPoints = spawnPoints,
            npcSpots = npcSpots
        )
    }
}
