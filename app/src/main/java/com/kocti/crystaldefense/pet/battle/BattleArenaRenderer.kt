package com.kocti.crystaldefense.pet.battle

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.kocti.crystaldefense.pet.CreatureForm
import com.kocti.crystaldefense.pet.Species
import com.kocti.crystaldefense.pet.glb.GlbParser
import com.kocti.crystaldefense.pet.glb.SkinnedInstance
import com.kocti.crystaldefense.pet.glb.SkinnedRenderer
import com.kocti.crystaldefense.render.Mesh
import com.kocti.crystaldefense.render.MeshRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.random.Random

/** Цвет удара: огонь, яд, морок и гоблинская ярость. */
private fun Species.sparkColor(): FloatArray = when (this) {
    Species.DRAKO -> floatArrayOf(1f, 0.55f, 0.1f)
    Species.POGANYSH -> floatArrayOf(0.45f, 1f, 0.2f)
    Species.MOROK -> floatArrayOf(0.75f, 0.35f, 1f)
    Species.GOBLIN -> floatArrayOf(1f, 0.2f, 0.2f)
}

/**
 * Арена с двумя существами. Методы боя вызываются на потоке GL через queueEvent,
 * а время берётся из часов устройства, чтобы удар совпадал с полосами здоровья даже при низкой частоте кадров.
 */
class BattleArenaRenderer(
    private val loadAsset: (String) -> ByteArray,
    private val onReady: () -> Unit,
) : GLSurfaceView.Renderer {

    private enum class Mode { ENTER, IDLE, ATTACK, HIT, DEAD, CHEER }

    private class Side(val form: CreatureForm, val height: Float, val homeX: Float, val homeZ: Float, val spark: FloatArray) {
        var instance: SkinnedInstance? = null
        var mode = Mode.ENTER
        var x = homeX
        var z = homeZ
        var attackStart = NEVER
        var lungeReach = LUNGE_REACH
        var impactDone = true
        var hitStart = NEVER
        var hoverLeft = 1f
    }

    private val skinned = SkinnedRenderer()
    private val meshes = MeshRenderer()
    private lateinit var disc: Mesh
    private lateinit var sphere: Mesh

    private var near: Side? = null
    private var far: Side? = null
    private var introStart = NEVER
    private var shakeStart = NEVER
    private var lastFrameMillis = 0L
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val part = FloatArray(16)

    private val sparkX = FloatArray(MAX_SPARKS)
    private val sparkY = FloatArray(MAX_SPARKS)
    private val sparkZ = FloatArray(MAX_SPARKS)
    private val sparkVelocityX = FloatArray(MAX_SPARKS)
    private val sparkVelocityY = FloatArray(MAX_SPARKS)
    private val sparkVelocityZ = FloatArray(MAX_SPARKS)
    private val sparkLife = FloatArray(MAX_SPARKS)
    private val sparkColor = Array(MAX_SPARKS) { FloatArray(3) }
    private var nextSpark = 0

    /** [first] стоит ближе к камере: это существо игрока. */
    fun start(first: Fighter, second: Fighter) {
        near = side(first, -FIGHTER_X, FIGHTER_Z)
        far = side(second, FIGHTER_X, -FIGHTER_Z)
        introStart = NEVER
        shakeStart = NEVER
        sparkLife.fill(0f)
    }

    private fun side(fighter: Fighter, x: Float, z: Float): Side {
        val form = fighter.species.formFor(fighter.level)
        return Side(form, arenaHeight(fighter.level, form.scale), x, z, fighter.species.sparkColor())
    }

    fun attack(attacker: Attacker) {
        val first = near ?: return
        val second = far ?: return
        val now = SystemClock.elapsedRealtime()
        // При столкновении оба встречаются посередине, поэтому каждый проходит меньший путь.
        val reach = if (attacker == Attacker.BOTH) LUNGE_REACH / 2f else LUNGE_REACH
        if (attacker != Attacker.SECOND) beginAttack(first, now, reach)
        if (attacker != Attacker.FIRST) beginAttack(second, now, reach)
    }

    private fun beginAttack(side: Side, now: Long, reach: Float) {
        side.mode = Mode.ATTACK
        side.attackStart = now
        side.lungeReach = reach
        side.impactDone = false
        side.instance?.animator?.play(side.form.model.attackClip, loop = false, restart = true)
    }

    /** Показывает исход: проигравший падает, победитель радуется. При ничьей оба остаются стоять. */
    fun finish(winner: Winner) {
        val first = near ?: return
        val second = far ?: return
        when (winner) {
            Winner.FIRST -> showOutcome(first, second)
            Winner.SECOND -> showOutcome(second, first)
            Winner.DRAW -> {
                idle(first)
                idle(second)
            }
        }
    }

    private fun showOutcome(winner: Side, loser: Side) {
        winner.mode = Mode.CHEER
        winner.instance?.animator?.play(winner.form.model.celebrateClip)
        loser.mode = Mode.DEAD
        loser.instance?.animator?.play(loser.form.model.deathClip, loop = false)
    }

    private fun idle(side: Side) {
        side.mode = Mode.IDLE
        side.instance?.animator?.play(side.form.model.idleClip)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.09f, 0.16f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        skinned.createOnGlThread()
        meshes.createOnGlThread()
        disc = Mesh.disc(48)
        sphere = Mesh.sphere(1)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val first = near ?: return
        val second = far ?: return
        if (viewportWidth == 0 || viewportHeight == 0) return
        if (introStart == NEVER) {
            // Модели читаются здесь, на потоке GL: отсчёт боя начинается только после их загрузки.
            load(first)
            load(second)
            introStart = SystemClock.elapsedRealtime()
            lastFrameMillis = introStart
            onReady()
        }
        val now = SystemClock.elapsedRealtime()
        val deltaSeconds = ((now - lastFrameMillis) / 1000f).coerceIn(0f, MAX_FRAME_SECONDS)
        lastFrameMillis = now

        setUpCamera(now)
        drawFloor(first, second)
        update(first, second, now, deltaSeconds)
        update(second, first, now, deltaSeconds)
        draw(first, second, now)
        draw(second, first, now)
        drawSparks(deltaSeconds)
    }

    private fun load(side: Side) {
        val source = side.form.model
        val instance = SkinnedInstance(GlbParser.parse(loadAsset(source.asset)), source.idleClip)
        instance.animator.play(source.walkClip)
        side.instance = instance
    }

    private fun setUpCamera(now: Long) {
        val aspect = viewportWidth.toFloat() / viewportHeight
        // В портретном экране важнее ширина: оба бойца должны помещаться при любой форме телефона.
        val tangent = if (aspect < 1f) CAMERA_TANGENT / aspect else CAMERA_TANGENT
        val fieldOfView = Math.toDegrees(2.0 * atan(tangent.toDouble())).toFloat()
        Matrix.perspectiveM(projection, 0, fieldOfView, aspect, 0.1f, 40f)
        val shake = fade(now, shakeStart, SHAKE_MILLIS)
        val offset = if (shake > 0f) sin(shake * SHAKE_WAVES) * shake * SHAKE_METERS else 0f
        Matrix.setLookAtM(view, 0, offset, CAMERA_Y + offset / 2f, CAMERA_Z, offset / 2f, LOOK_Y, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    private fun update(side: Side, other: Side, now: Long, deltaSeconds: Float) {
        val animator = side.instance?.animator ?: return
        val directionX = other.homeX - side.homeX
        val directionZ = other.homeZ - side.homeZ
        var along = 0f
        var speed = 1f
        val entering = (now - introStart).toFloat() / ENTER_MILLIS
        if (entering < 1f) {
            // Выходят из-за края арены навстречу друг другу.
            along = -ENTER_DISTANCE * (1f - entering)
        } else if (side.mode == Mode.ENTER) {
            idle(side)
        }
        when (side.mode) {
            Mode.ATTACK -> {
                val progress = (now - side.attackStart).toFloat() / ATTACK_MILLIS
                along = lungeAmount(progress) * side.lungeReach
                speed = (animator.clip?.duration ?: 0f) * 1000f / ATTACK_MILLIS
                if (!side.impactDone && progress >= IMPACT_FRACTION) {
                    side.impactDone = true
                    land(side, other, now)
                }
                if (progress >= 1f) idle(side)
            }
            Mode.HIT -> if (animator.finished) idle(side)
            Mode.DEAD -> {
                speed = DEATH_SPEED
                side.hoverLeft = (side.hoverLeft - deltaSeconds * FALL_PER_SECOND).coerceAtLeast(0f)
                // Анимация падает назад: без сдвига к центру крупное существо уходит за край экрана.
                along += (1f - side.hoverLeft) * side.height * DEATH_SLIDE
            }
            else -> Unit
        }
        val recoil = fade(now, side.hitStart, RECOIL_MILLIS)
        along -= recoil * recoil * RECOIL_METERS
        side.x = side.homeX + directionX * along
        side.z = side.homeZ + directionZ * along
        animator.update(deltaSeconds, speed)
    }

    private fun land(attacker: Side, target: Side, now: Long) {
        target.hitStart = now
        shakeStart = now
        if (target.mode == Mode.IDLE) {
            target.mode = Mode.HIT
            target.instance?.animator?.play(target.form.model.hitClip, loop = false, restart = true)
        }
        spawnSparks(target, attacker.spark)
    }

    private fun spawnSparks(target: Side, color: FloatArray) {
        val y = target.height * (0.5f + target.form.hover)
        repeat(SPARKS_PER_HIT) {
            val i = nextSpark
            nextSpark = (nextSpark + 1) % MAX_SPARKS
            sparkX[i] = target.x
            sparkY[i] = y
            sparkZ[i] = target.z
            sparkVelocityX[i] = Random.nextFloat() * 3f - 1.5f
            sparkVelocityY[i] = Random.nextFloat() * 2.5f + 0.5f
            sparkVelocityZ[i] = Random.nextFloat() * 3f - 1.5f
            sparkLife[i] = 1f
            color.copyInto(sparkColor[i])
        }
    }

    private fun draw(side: Side, other: Side, now: Long) {
        val instance = side.instance ?: return
        val flash = fade(now, side.hitStart, FLASH_MILLIS)
        val bob = if (side.form.hover > 0f) sin(now / 420f) * 0.04f else 0f
        val hover = (side.form.hover + bob) * side.hoverLeft * side.height
        val yaw = Math.toDegrees(atan2(other.homeX - side.homeX, other.homeZ - side.homeZ).toDouble()).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, side.x, hover, side.z)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, side.height, side.height, side.height)
        Matrix.multiplyMM(part, 0, model, 0, instance.normalize, 0)
        skinned.draw(instance, part, viewProjection, 1f + flash * flash * HIT_FLASH)
    }

    private fun drawFloor(first: Side, second: Side) {
        meshes.begin(1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        floorDisc(0f, 0f, ARENA_RADIUS * 6f, 0.1f, 0.15f, 0.25f, 1f)
        floorDisc(0f, 0f, ARENA_RADIUS, 0.25f, 0.55f, 0.75f, 0.45f)
        floorDisc(0f, 0f, ARENA_RADIUS * 0.94f, 0.09f, 0.16f, 0.27f, 1f)
        floorDisc(first.x, first.z, first.height * first.form.shadow, 0f, 0f, 0f, 0.4f)
        floorDisc(second.x, second.z, second.height * second.form.shadow, 0f, 0f, 0f, 0.4f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        meshes.end()
    }

    /** Запись в буфер глубины выключена, поэтому диски просто ложатся друг на друга в порядке вызова. */
    private fun floorDisc(x: Float, z: Float, radius: Float, r: Float, g: Float, b: Float, alpha: Float) {
        Matrix.setIdentityM(part, 0)
        Matrix.translateM(part, 0, x, 0f, z)
        Matrix.scaleM(part, 0, radius, 1f, radius)
        meshes.draw(disc, part, viewProjection, r, g, b, alpha = alpha, emissive = 1f)
    }

    private fun drawSparks(deltaSeconds: Float) {
        meshes.begin(1f)
        for (i in 0 until MAX_SPARKS) {
            if (sparkLife[i] <= 0f) continue
            sparkLife[i] -= deltaSeconds / SPARK_SECONDS
            sparkVelocityY[i] -= SPARK_GRAVITY * deltaSeconds
            sparkX[i] += sparkVelocityX[i] * deltaSeconds
            sparkY[i] = (sparkY[i] + sparkVelocityY[i] * deltaSeconds).coerceAtLeast(0f)
            sparkZ[i] += sparkVelocityZ[i] * deltaSeconds
            val radius = SPARK_RADIUS * sparkLife[i].coerceAtLeast(0f)
            Matrix.setIdentityM(part, 0)
            Matrix.translateM(part, 0, sparkX[i], sparkY[i], sparkZ[i])
            Matrix.scaleM(part, 0, radius, radius, radius)
            val color = sparkColor[i]
            meshes.draw(sphere, part, viewProjection, color[0], color[1], color[2], emissive = 1f)
        }
        meshes.end()
    }

    /** От 1 сразу после события до 0 к его концу. */
    private fun fade(now: Long, start: Long, millis: Long): Float {
        if (start == NEVER) return 0f
        return (1f - (now - start).toFloat() / millis).coerceIn(0f, 1f)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val FIGHTER_X = 0.62f
        const val FIGHTER_Z = 0.5f
        const val LUNGE_REACH = 0.62f
        const val ENTER_MILLIS = 1000L
        const val ENTER_DISTANCE = 0.45f
        const val RECOIL_MILLIS = 350L
        const val RECOIL_METERS = 0.12f
        const val FLASH_MILLIS = 300L
        const val HIT_FLASH = 1.3f
        const val SHAKE_MILLIS = 350L
        const val SHAKE_WAVES = 22f
        const val SHAKE_METERS = 0.09f
        const val DEATH_SPEED = 0.7f
        const val FALL_PER_SECOND = 1.6f
        const val DEATH_SLIDE = 0.28f
        const val CAMERA_Y = 1.55f
        const val CAMERA_Z = 4.2f
        const val LOOK_Y = 0.45f
        const val CAMERA_TANGENT = 0.38f
        const val ARENA_RADIUS = 1.45f
        const val MAX_FRAME_SECONDS = 0.1f
        const val MAX_SPARKS = 48
        const val SPARKS_PER_HIT = 14
        const val SPARK_SECONDS = 0.6f
        const val SPARK_GRAVITY = 6f
        const val SPARK_RADIUS = 0.055f
    }
}
