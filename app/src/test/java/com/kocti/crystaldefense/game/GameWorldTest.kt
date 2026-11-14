package com.kocti.crystaldefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameWorldTest {
    private val world = GameWorld(Random(42))

    private fun advance(seconds: Float) {
        repeat((seconds / 0.02f).toInt()) { world.update(0.02f) }
    }

    /** Переводит игру в первую волну: дальше дроны появляются сами. */
    private fun startFirstWave() {
        advance(3.1f)
        assertEquals(GamePhase.FIGHTING, world.phase)
    }

    @Test
    fun firstWaveStartsAfterBreakAndSpawnsEnemies() {
        assertEquals(0, world.wave)
        startFirstWave()
        assertEquals(1, world.wave)
        assertTrue(world.enemies.isNotEmpty())
    }

    @Test
    fun spawnedEnemiesStayInRoomSizedArea() {
        startFirstWave()
        advance(6f)
        world.enemies.forEach {
            val horizontal = Vec3(it.position.x, 0f, it.position.z).length()
            assertTrue("distance $horizontal", horizontal <= 1.6f)
            assertTrue("height ${it.position.y}", it.position.y in 0f..0.9f)
        }
    }

    @Test
    fun shotKillsDroneAndAddsScore() {
        val drone = world.spawn(EnemyType.DRONE, Vec3(0f, 0.5f, -1f))
        assertTrue(world.fire(Vec3(0f, 0.5f, 1f), Vec3(0f, 0f, -1f)))
        advance(0.4f)
        assertFalse(world.enemies.contains(drone))
        assertEquals(EnemyType.DRONE.points, world.score)
        assertTrue(world.shots.isEmpty())
    }

    @Test
    fun shotDoesNotTunnelThroughSmallEnemyOnLongFrame() {
        val scout = world.spawn(EnemyType.SCOUT, Vec3(0f, 0.5f, 0f))
        world.fire(Vec3(0f, 0.5f, 0.3f), Vec3(0f, 0f, -1f))
        world.update(0.05f)
        assertFalse(world.enemies.contains(scout))
    }

    @Test
    fun shotPastEnemyMisses() {
        val drone = world.spawn(EnemyType.DRONE, Vec3(0f, 0.5f, -1f))
        world.fire(Vec3(0.5f, 0.5f, 1f), Vec3(0f, 0f, -1f))
        advance(0.5f)
        assertTrue(world.enemies.contains(drone))
        assertEquals(0, world.score)
    }

    @Test
    fun tankSurvivesUntilThirdHit() {
        val tank = world.spawn(EnemyType.TANK, Vec3(0f, 0.5f, -1f))
        repeat(2) {
            world.fire(Vec3(0f, 0.5f, 1f), Vec3(0f, 0f, -1f))
            advance(0.4f)
            assertTrue(world.enemies.contains(tank))
        }
        world.fire(Vec3(0f, 0.5f, 1f), Vec3(0f, 0f, -1f))
        advance(0.4f)
        assertFalse(world.enemies.contains(tank))
        assertEquals(EnemyType.TANK.points, world.score)
    }

    @Test
    fun weaponCooldownLimitsFireRate() {
        assertTrue(world.fire(Vec3.ZERO, Vec3(0f, 0f, -1f)))
        assertFalse(world.fire(Vec3.ZERO, Vec3(0f, 0f, -1f)))
        advance(0.2f)
        assertTrue(world.fire(Vec3.ZERO, Vec3(0f, 0f, -1f)))
    }

    @Test
    fun enemyReachingCrystalDealsOneDamage() {
        world.spawn(EnemyType.DRONE, Vec3(0.1f, 0.12f, 0f))
        world.update(0.02f)
        assertEquals(CRYSTAL_MAX_HP - 1, world.crystalHp)
        assertTrue(world.enemies.isEmpty())
    }

    @Test
    fun gameEndsWhenCrystalDestroyedAndShootingStops() {
        repeat(CRYSTAL_MAX_HP) {
            world.spawn(EnemyType.DRONE, Vec3(0.1f, 0.12f, 0f))
            world.update(0.02f)
        }
        assertEquals(GamePhase.GAME_OVER, world.phase)
        assertEquals(0, world.crystalHp)
        advance(0.2f)
        assertFalse(world.fire(Vec3.ZERO, Vec3(0f, 0f, -1f)))
    }

    @Test
    fun lastHitWithOtherEnemiesAliveEndsGameWithoutCrash() {
        repeat(CRYSTAL_MAX_HP - 1) {
            world.spawn(EnemyType.DRONE, Vec3(0.1f, 0.12f, 0f))
            world.update(0.02f)
        }
        world.spawn(EnemyType.DRONE, Vec3(0.1f, 0.12f, 0f))
        world.spawn(EnemyType.DRONE, Vec3(0f, 0.5f, -1f))
        world.spawn(EnemyType.DRONE, Vec3(1f, 0.5f, 0f))
        world.update(0.02f)
        assertEquals(GamePhase.GAME_OVER, world.phase)
        assertTrue(world.enemies.isEmpty())
    }

    @Test
    fun nextWaveIsLargerThanPrevious() {
        startFirstWave()
        // Сбиваем всех прямым удалением по очереди, пока волна не закончится.
        var guard = 0
        while (world.wave == 1 && guard++ < 5000) {
            world.enemies.clear()
            world.update(0.02f)
        }
        assertEquals(2, world.wave)
        assertEquals(GamePhase.FIGHTING, world.phase)
    }

    @Test
    fun restartResetsEverything() {
        startFirstWave()
        world.spawn(EnemyType.DRONE, Vec3(0.1f, 0.12f, 0f))
        world.update(0.02f)
        world.restart()
        assertEquals(0, world.wave)
        assertEquals(0, world.score)
        assertEquals(CRYSTAL_MAX_HP, world.crystalHp)
        assertEquals(GamePhase.WAVE_BREAK, world.phase)
        assertTrue(world.enemies.isEmpty())
    }

    @Test
    fun longFrameIsClampedSoEnemiesDoNotJump() {
        val drone = world.spawn(EnemyType.DRONE, Vec3(0f, 0.5f, -1f))
        val before = drone.position
        world.update(10f)
        assertTrue((drone.position - before).length() <= EnemyType.DRONE.speed * 0.05f + 1e-4f)
    }
}
