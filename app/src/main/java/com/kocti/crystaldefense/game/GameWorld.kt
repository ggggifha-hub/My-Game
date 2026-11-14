package com.kocti.crystaldefense.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Логика игры без зависимостей от Android. Кристалл стоит в начале координат, ось Y смотрит вверх, единицы — метры. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 = length().let { if (it > 1e-6f) this * (1f / it) else ZERO }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

enum class EnemyType(val hp: Int, val speed: Float, val radius: Float, val points: Int) {
    DRONE(hp = 1, speed = 0.20f, radius = 0.06f, points = 10),
    SCOUT(hp = 1, speed = 0.38f, radius = 0.04f, points = 20),
    TANK(hp = 3, speed = 0.13f, radius = 0.10f, points = 50),
}

class Enemy(val type: EnemyType, var position: Vec3, val speed: Float, val bobPhase: Float) {
    var hp = type.hp
    var hitFlash = 0f
}

class Shot(var position: Vec3, val velocity: Vec3) {
    var life = SHOT_LIFETIME
}

class Particle(var position: Vec3, val velocity: Vec3, val type: EnemyType?) {
    var life = PARTICLE_LIFETIME
}

enum class GamePhase { WAVE_BREAK, FIGHTING, GAME_OVER }

const val CRYSTAL_MAX_HP = 5
const val CRYSTAL_HEIGHT = 0.24f
const val PARTICLE_LIFETIME = 0.6f
private const val SHOT_LIFETIME = 1f
private const val SHOT_SPEED = 10f
private const val SHOT_COOLDOWN = 0.12f
private const val AIM_ASSIST = 0.05f
private const val WAVE_BREAK_SECONDS = 3f
private const val CRYSTAL_HIT_RADIUS = 0.1f
private const val MAX_PARTICLES = 150
private const val MAX_STEP = 0.05f
private val CRYSTAL_CENTER = Vec3(0f, CRYSTAL_HEIGHT / 2f, 0f)

class GameWorld(private val random: Random = Random.Default) {
    val enemies = mutableListOf<Enemy>()
    val shots = mutableListOf<Shot>()
    val particles = mutableListOf<Particle>()

    var phase = GamePhase.WAVE_BREAK
        private set
    var score = 0
        private set
    var wave = 0
        private set
    var crystalHp = CRYSTAL_MAX_HP
        private set
    var crystalFlash = 0f
        private set

    private var toSpawn = 0
    private var spawnTimer = 0f
    private var breakTimer = WAVE_BREAK_SECONDS
    private var cooldown = 0f

    fun restart() {
        enemies.clear()
        shots.clear()
        particles.clear()
        phase = GamePhase.WAVE_BREAK
        score = 0
        wave = 0
        crystalHp = CRYSTAL_MAX_HP
        crystalFlash = 0f
        toSpawn = 0
        spawnTimer = 0f
        breakTimer = WAVE_BREAK_SECONDS
        cooldown = 0f
    }

    /** Выстрел по лучу из камеры. Возвращает false, если оружие ещё перезаряжается или игра окончена. */
    fun fire(origin: Vec3, direction: Vec3): Boolean {
        if (phase == GamePhase.GAME_OVER || cooldown > 0f) return false
        val dir = direction.normalized()
        // Небольшой отступ, чтобы снаряд не попал в ближнюю плоскость отсечения камеры.
        shots += Shot(origin + dir * 0.1f, dir * SHOT_SPEED)
        cooldown = SHOT_COOLDOWN
        return true
    }

    fun spawn(type: EnemyType, position: Vec3, speedScale: Float = 1f): Enemy {
        val enemy = Enemy(type, position, type.speed * speedScale, random.nextFloat() * TWO_PI)
        enemies += enemy
        return enemy
    }

    fun update(deltaSeconds: Float) {
        // При подвисании кадра дроны не должны перепрыгивать через весь уровень.
        val dt = min(deltaSeconds, MAX_STEP)
        cooldown = max(0f, cooldown - dt)
        crystalFlash = max(0f, crystalFlash - dt * 2.5f)
        updateParticles(dt)
        if (phase == GamePhase.GAME_OVER) return
        updateWave(dt)
        updateEnemies(dt)
        updateShots(dt)
    }

    private fun updateWave(dt: Float) {
        when (phase) {
            GamePhase.WAVE_BREAK -> {
                breakTimer -= dt
                if (breakTimer <= 0f) {
                    wave++
                    toSpawn = 3 + 2 * wave
                    spawnTimer = 0f
                    phase = GamePhase.FIGHTING
                }
            }
            GamePhase.FIGHTING -> {
                if (toSpawn > 0) {
                    spawnTimer -= dt
                    if (spawnTimer <= 0f) {
                        spawnRandomEnemy()
                        toSpawn--
                        spawnTimer = max(0.5f, 2.2f - 0.2f * wave)
                    }
                } else if (enemies.isEmpty()) {
                    breakTimer = WAVE_BREAK_SECONDS
                    phase = GamePhase.WAVE_BREAK
                }
            }
            GamePhase.GAME_OVER -> Unit
        }
    }

    private fun spawnRandomEnemy() {
        val roll = random.nextFloat()
        val type = when {
            wave >= 3 && roll < 0.15f -> EnemyType.TANK
            wave >= 2 && roll < 0.40f -> EnemyType.SCOUT
            else -> EnemyType.DRONE
        }
        val angle = random.nextFloat() * TWO_PI
        val distance = 1.0f + random.nextFloat() * 0.6f
        val height = 0.3f + random.nextFloat() * 0.6f
        spawn(type, Vec3(cos(angle) * distance, height, sin(angle) * distance), 1f + 0.06f * (wave - 1))
    }

    private fun updateEnemies(dt: Float) {
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.hitFlash = max(0f, enemy.hitFlash - dt * 4f)
            val toCrystal = CRYSTAL_CENTER - enemy.position
            if (toCrystal.length() <= CRYSTAL_HIT_RADIUS + enemy.type.radius) {
                iterator.remove()
                explode(enemy.position, enemy.type)
                damageCrystal()
                // При разрушении кристалла список очищен, и итератор использовать нельзя.
                if (phase == GamePhase.GAME_OVER) return
                continue
            }
            enemy.position += toCrystal.normalized() * (enemy.speed * dt)
        }
    }

    private fun damageCrystal() {
        crystalHp = max(0, crystalHp - 1)
        crystalFlash = 1f
        if (crystalHp == 0) {
            phase = GamePhase.GAME_OVER
            repeat(3) { explode(CRYSTAL_CENTER, null) }
            enemies.clear()
            shots.clear()
        }
    }

    private fun updateShots(dt: Float) {
        val iterator = shots.iterator()
        while (iterator.hasNext()) {
            val shot = iterator.next()
            val start = shot.position
            val end = start + shot.velocity * dt
            shot.position = end
            shot.life -= dt
            val target = enemies.firstOrNull { segmentHitsSphere(start, end, it.position, it.type.radius + AIM_ASSIST) }
            if (target != null) {
                iterator.remove()
                hit(target)
            } else if (shot.life <= 0f) {
                iterator.remove()
            }
        }
    }

    private fun hit(enemy: Enemy) {
        enemy.hp--
        enemy.hitFlash = 1f
        if (enemy.hp <= 0) {
            enemies.remove(enemy)
            score += enemy.type.points
            explode(enemy.position, enemy.type)
        }
    }

    private fun explode(at: Vec3, type: EnemyType?) {
        repeat(10) {
            if (particles.size < MAX_PARTICLES) {
                val direction = Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f)
                particles += Particle(at, direction.normalized() * (0.3f + random.nextFloat() * 0.5f), type)
            }
        }
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            particle.life -= dt
            if (particle.life <= 0f) iterator.remove() else particle.position += particle.velocity * dt
        }
    }

    private companion object {
        const val TWO_PI = (2 * PI).toFloat()
    }
}

/** Проверка всего отрезка: быстрый снаряд за кадр может пролететь дрона насквозь. */
fun segmentHitsSphere(start: Vec3, end: Vec3, center: Vec3, radius: Float): Boolean {
    val segment = end - start
    val lengthSquared = segment.dot(segment)
    val t = if (lengthSquared > 0f) ((center - start).dot(segment) / lengthSquared).coerceIn(0f, 1f) else 0f
    return (start + segment * t - center).length() <= radius
}
