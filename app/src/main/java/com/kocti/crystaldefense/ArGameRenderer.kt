package com.kocti.crystaldefense

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import androidx.annotation.StringRes
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.SessionPausedException
import com.kocti.crystaldefense.game.CRYSTAL_HEIGHT
import com.kocti.crystaldefense.game.CRYSTAL_MAX_HP
import com.kocti.crystaldefense.game.EnemyType
import com.kocti.crystaldefense.game.GamePhase
import com.kocti.crystaldefense.game.GameWorld
import com.kocti.crystaldefense.game.PARTICLE_LIFETIME
import com.kocti.crystaldefense.game.Vec3
import com.kocti.crystaldefense.render.BackgroundRenderer
import com.kocti.crystaldefense.render.Mesh
import com.kocti.crystaldefense.render.MeshRenderer
import com.kocti.crystaldefense.render.PlaneRenderer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Снимок состояния для интерфейса. Экран обновляется только при его изменении. */
data class HudState(
    val inGame: Boolean,
    val score: Int,
    val wave: Int,
    val crystalHp: Int,
    @param:StringRes val hint: Int?,
    val gameOver: Boolean,
)

class ArGameRenderer(
    /** На эмуляторе отслеживание ARCore ненадёжно: сцена стоит перед неподвижной виртуальной камерой. */
    private val emulatorMode: Boolean,
    private val displayRotation: () -> Int,
    private val onHud: (HudState) -> Unit,
    private val onCameraFailed: () -> Unit,
    private val onArFailed: (FatalException) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    private sealed interface Input {
        data class Tap(val x: Float, val y: Float) : Input
        data object Restart : Input
    }

    private val input = ConcurrentLinkedQueue<Input>()
    private val world = GameWorld()

    private val background = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val meshRenderer = MeshRenderer()
    private lateinit var crystalMesh: Mesh
    private lateinit var sphereMesh: Mesh
    private lateinit var lowSphereMesh: Mesh
    private lateinit var discMesh: Mesh

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var appliedRotation = -1
    private var appliedSession: Session? = null
    private var anchor: Anchor? = null
    private var lastFrameNanos = 0L
    private var lastHud: HudState? = null
    private var arenaPlaced = false
    private var emulatorBackgroundFailed = false

    @Volatile
    private var orbitDegrees = 0f

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val anchorMatrix = FloatArray(16)
    private val anchorInverse = FloatArray(16)
    private val anchorViewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val unprojectInverse = FloatArray(16)
    private val point = FloatArray(4)
    private val result = FloatArray(4)

    fun onTap(x: Float, y: Float) {
        input.add(Input.Tap(x, y))
    }

    fun onRestart() {
        input.add(Input.Restart)
    }

    fun onOrbit(deltaDegrees: Float) {
        orbitDegrees = (orbitDegrees + deltaDegrees) % 360f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (emulatorMode) GLES20.glClearColor(0.06f, 0.09f, 0.15f, 1f) else GLES20.glClearColor(0f, 0f, 0f, 1f)
        // Без камеры фон не рисуется, а именно он включал тест глубины.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        background.createOnGlThread()
        planeRenderer.createOnGlThread()
        meshRenderer.createOnGlThread()
        crystalMesh = Mesh.crystal(CRYSTAL_HEIGHT, 0.07f)
        sphereMesh = Mesh.sphere(2)
        lowSphereMesh = Mesh.sphere(0)
        discMesh = Mesh.disc()
        appliedSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        appliedRotation = -1
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (emulatorMode) {
            drawEmulatorFrame()
            return
        }
        val session = session ?: return
        prepareSession(session)
        val frame = try {
            session.update()
        } catch (_: SessionPausedException) {
            return
        } catch (_: CameraNotAvailableException) {
            onCameraFailed()
            return
        } catch (e: FatalException) {
            onArFailed(e)
            return
        }
        background.draw(frame)

        val camera = frame.camera
        val deltaSeconds = nextDeltaSeconds()
        if (camera.trackingState != TrackingState.TRACKING) {
            input.clear()
            publishHud(trackingHint(camera.trackingFailureReason))
            return
        }

        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        val currentAnchor = anchor
        if (currentAnchor == null) {
            drawPlacement(session, frame)
            return
        }
        when (currentAnchor.trackingState) {
            TrackingState.STOPPED -> {
                // ARCore окончательно потерял точку: кристалл ставится заново.
                currentAnchor.detach()
                anchor = null
                world.restart()
                return
            }
            TrackingState.PAUSED -> {
                input.clear()
                publishHud(R.string.tracking_lost)
                return
            }
            TrackingState.TRACKING -> Unit
        }

        currentAnchor.pose.toMatrix(anchorMatrix, 0)
        Matrix.invertM(anchorInverse, 0, anchorMatrix, 0)
        Matrix.multiplyMM(anchorViewProjection, 0, viewProjection, 0, anchorMatrix, 0)

        handleGameInput()
        world.update(deltaSeconds)
        drawWorld(lightFrom(frame.lightEstimate))
        publishHud(gameHint())
    }

    private fun drawEmulatorFrame() {
        drawEmulatorBackground()
        val deltaSeconds = nextDeltaSeconds()
        if (viewportWidth == 0 || viewportHeight == 0) return
        if (!arenaPlaced) {
            var tapped = false
            while (true) {
                if (input.poll() ?: break is Input.Tap) tapped = true
            }
            if (!tapped) {
                publishHud(R.string.hint_place_emulator)
                return
            }
            arenaPlaced = true
            world.restart()
        }

        val yaw = Math.toRadians(orbitDegrees.toDouble())
        Matrix.setLookAtM(
            view, 0,
            (sin(yaw) * EMULATOR_CAMERA_DISTANCE).toFloat(), EMULATOR_CAMERA_HEIGHT, (cos(yaw) * EMULATOR_CAMERA_DISTANCE).toFloat(),
            0f, EMULATOR_LOOK_HEIGHT, 0f,
            0f, 1f, 0f,
        )
        Matrix.perspectiveM(projection, 0, EMULATOR_FOV_DEGREES, viewportWidth.toFloat() / viewportHeight, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        Matrix.setIdentityM(anchorMatrix, 0)
        viewProjection.copyInto(anchorViewProjection)

        handleGameInput()
        world.update(deltaSeconds)
        drawWorld(light = 1f)
        publishHud(gameHint())
    }

    /** Изображение ARCore здесь только фон, поэтому его сбой не мешает игре. */
    private fun drawEmulatorBackground() {
        val session = session ?: return
        if (emulatorBackgroundFailed) return
        prepareSession(session)
        try {
            background.draw(session.update())
        } catch (_: SessionPausedException) {
            return
        } catch (_: CameraNotAvailableException) {
            emulatorBackgroundFailed = true
        } catch (_: FatalException) {
            emulatorBackgroundFailed = true
        }
    }

    private fun prepareSession(session: Session) {
        if (session !== appliedSession) {
            session.setCameraTextureName(background.textureId)
            appliedSession = session
            if (!emulatorMode) {
                anchor = null
                world.restart()
            }
            appliedRotation = -1
        }
        val rotation = displayRotation()
        if (rotation != appliedRotation && viewportWidth > 0) {
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            appliedRotation = rotation
        }
    }

    private fun nextDeltaSeconds(): Float {
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        return delta
    }

    private fun drawPlacement(session: Session, frame: Frame) {
        var placed = false
        while (true) {
            val event = input.poll() ?: break
            if (placed || event !is Input.Tap) continue
            val hit = frame.hitTest(event.x, event.y).firstOrNull { result ->
                val plane = result.trackable as? Plane
                plane != null &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.isPoseInPolygon(result.hitPose)
            }
            if (hit != null) {
                anchor = hit.createAnchor()
                world.restart()
                placed = true
            }
        }

        val planes = session.getAllTrackables(Plane::class.java)
        val hasPlanes = planes.any {
            it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        planeRenderer.draw(planes, viewProjection)
        GLES20.glDisable(GLES20.GL_BLEND)
        publishHud(if (hasPlanes) R.string.hint_place else R.string.hint_scan)
    }

    private fun handleGameInput() {
        while (true) {
            when (val event = input.poll() ?: break) {
                is Input.Restart -> world.restart()
                is Input.Tap -> {
                    val (origin, direction) = tapRay(event.x, event.y)
                    world.fire(origin, direction)
                }
            }
        }
    }

    /** Луч из камеры через точку касания в координатах кристалла. */
    private fun tapRay(x: Float, y: Float): Pair<Vec3, Vec3> {
        val ndcX = 2f * x / viewportWidth - 1f
        val ndcY = 1f - 2f * y / viewportHeight
        Matrix.invertM(unprojectInverse, 0, anchorViewProjection, 0)
        val near = unproject(ndcX, ndcY, -1f)
        val far = unproject(ndcX, ndcY, 1f)
        return near to (far - near).normalized()
    }

    private fun unproject(ndcX: Float, ndcY: Float, ndcZ: Float): Vec3 {
        point[0] = ndcX
        point[1] = ndcY
        point[2] = ndcZ
        point[3] = 1f
        Matrix.multiplyMV(result, 0, unprojectInverse, 0, point, 0)
        return Vec3(result[0] / result[3], result[1] / result[3], result[2] / result[3])
    }

    private fun lightFrom(lightEstimate: LightEstimate): Float =
        if (lightEstimate.state == LightEstimate.State.VALID) {
            (lightEstimate.pixelIntensity / AVERAGE_PIXEL_INTENSITY).coerceIn(0.4f, 1.4f)
        } else {
            1f
        }

    private fun drawWorld(light: Float) {
        val time = SystemClock.elapsedRealtime() / 1000f
        meshRenderer.begin(light)

        if (world.phase != GamePhase.GAME_OVER) {
            setModel(Vec3.ZERO, 1f, angleDegrees = time * 30f)
            val damage = 1f - world.crystalHp.toFloat() / CRYSTAL_MAX_HP
            val flash = world.crystalFlash
            meshRenderer.draw(
                crystalMesh, model, viewProjection,
                r = 0.25f + 0.75f * maxOf(damage, flash),
                g = 0.85f * (1f - damage) * (1f - flash) + 0.1f,
                b = 1f * (1f - damage) * (1f - flash) + 0.1f,
                emissive = 0.35f + 0.5f * flash,
            )
        }

        for (enemy in world.enemies) {
            val bob = sin(time * 3f + enemy.bobPhase) * 0.015f
            setModel(enemy.position + Vec3(0f, bob, 0f), enemy.type.radius)
            val flash = enemy.hitFlash
            val (r, g, b) = colorOf(enemy.type)
            meshRenderer.draw(
                sphereMesh, model, viewProjection,
                r = r + (1f - r) * flash,
                g = g + (1f - g) * flash,
                b = b + (1f - b) * flash,
                emissive = 0.15f + 0.7f * flash,
            )
        }

        for (shot in world.shots) {
            setModel(shot.position, SHOT_RADIUS)
            meshRenderer.draw(lowSphereMesh, model, viewProjection, 1f, 0.95f, 0.45f, emissive = 1f)
        }

        // Полупрозрачное рисуется последним и не пишет в буфер глубины.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        drawShadows()
        for (particle in world.particles) {
            val fade = (particle.life / PARTICLE_LIFETIME).coerceIn(0f, 1f)
            setModel(particle.position, 0.012f + 0.012f * fade)
            val (r, g, b) = particle.type?.let(::colorOf) ?: CRYSTAL_COLOR
            meshRenderer.draw(lowSphereMesh, model, viewProjection, r, g, b, alpha = fade, emissive = 1f)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        meshRenderer.end()
    }

    /** Тени на полу визуально привязывают объекты к реальной комнате. */
    private fun drawShadows() {
        if (emulatorMode) {
            // Без найденного пола площадка показывает, где находится земля.
            setModel(Vec3(0f, SHADOW_HEIGHT / 2f, 0f), EMULATOR_ARENA_RADIUS)
            meshRenderer.draw(discMesh, model, viewProjection, 0.2f, 0.7f, 0.9f, alpha = 0.22f, emissive = 1f)
        }
        if (world.phase != GamePhase.GAME_OVER) {
            setModel(Vec3(0f, SHADOW_HEIGHT, 0f), 0.09f)
            meshRenderer.draw(discMesh, model, viewProjection, 0f, 0f, 0f, alpha = 0.35f)
        }
        for (enemy in world.enemies) {
            val position = enemy.position
            setModel(Vec3(position.x, SHADOW_HEIGHT, position.z), enemy.type.radius)
            meshRenderer.draw(discMesh, model, viewProjection, 0f, 0f, 0f, alpha = 0.25f)
        }
    }

    private fun setModel(position: Vec3, scale: Float, angleDegrees: Float = 0f) {
        Matrix.translateM(model, 0, anchorMatrix, 0, position.x, position.y, position.z)
        if (angleDegrees != 0f) Matrix.rotateM(model, 0, angleDegrees, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)
    }

    private fun gameHint(): Int? {
        if (world.phase == GamePhase.GAME_OVER) return null
        if (world.phase == GamePhase.WAVE_BREAK) {
            return when {
                world.wave > 0 -> R.string.hint_next_wave
                emulatorMode -> R.string.hint_shoot_emulator
                else -> R.string.hint_shoot
            }
        }
        if (world.enemies.isEmpty()) return null
        var nearestSide = 0f
        var nearestDistance = Float.MAX_VALUE
        for (enemy in world.enemies) {
            point[0] = enemy.position.x
            point[1] = enemy.position.y
            point[2] = enemy.position.z
            point[3] = 1f
            Matrix.multiplyMV(result, 0, anchorViewProjection, 0, point, 0)
            val w = result[3]
            if (w > 0f && abs(result[0] / w) <= 1f && abs(result[1] / w) <= 1f) return null
            val distance = enemy.position.length()
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestSide = result[0]
            }
        }
        return if (nearestSide < 0f) R.string.hint_enemy_left else R.string.hint_enemy_right
    }

    @StringRes
    private fun trackingHint(reason: TrackingFailureReason): Int = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.tracking_dark
        TrackingFailureReason.EXCESSIVE_MOTION -> R.string.tracking_motion
        TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.tracking_features
        TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.tracking_camera
        else -> if (anchor == null) R.string.hint_scan else R.string.tracking_lost
    }

    private fun publishHud(@StringRes hint: Int?) {
        val state = HudState(
            inGame = anchor != null || arenaPlaced,
            score = world.score,
            wave = world.wave,
            crystalHp = world.crystalHp,
            hint = hint,
            gameOver = world.phase == GamePhase.GAME_OVER,
        )
        if (state != lastHud) {
            lastHud = state
            onHud(state)
        }
    }

    private fun colorOf(type: EnemyType): Triple<Float, Float, Float> = when (type) {
        EnemyType.DRONE -> Triple(0.95f, 0.25f, 0.3f)
        EnemyType.SCOUT -> Triple(1f, 0.65f, 0.1f)
        EnemyType.TANK -> Triple(0.65f, 0.25f, 0.95f)
    }

    private companion object {
        const val NEAR_CLIP = 0.05f
        const val FAR_CLIP = 30f
        const val AVERAGE_PIXEL_INTENSITY = 0.466f
        const val SHOT_RADIUS = 0.012f
        const val SHADOW_HEIGHT = 0.003f
        const val EMULATOR_CAMERA_DISTANCE = 1.8
        const val EMULATOR_CAMERA_HEIGHT = 0.8f
        const val EMULATOR_LOOK_HEIGHT = 0.25f
        const val EMULATOR_FOV_DEGREES = 70f
        const val EMULATOR_ARENA_RADIUS = 1.7f
        val CRYSTAL_COLOR = Triple(0.4f, 0.9f, 1f)
    }
}
