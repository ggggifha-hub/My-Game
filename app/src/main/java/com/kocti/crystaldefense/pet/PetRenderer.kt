package com.kocti.crystaldefense.pet

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Camera
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.SessionPausedException
import com.kocti.crystaldefense.R
import com.kocti.crystaldefense.pet.glb.GlbParser
import com.kocti.crystaldefense.pet.glb.SkinnedInstance
import com.kocti.crystaldefense.pet.glb.SkinnedRenderer
import com.kocti.crystaldefense.render.BackgroundRenderer
import com.kocti.crystaldefense.render.Mesh
import com.kocti.crystaldefense.render.MeshRenderer
import com.kocti.crystaldefense.render.PlaneRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class PetRenderer(
    private val emulatorMode: Boolean,
    private val loadAsset: (String) -> ByteArray,
    private val displayRotation: () -> Int,
    private val onHint: (Int?) -> Unit,
    private val onArFailed: (Exception) -> Unit,
    private val readPhoneRotation: (FloatArray) -> Unit,
    private val onCameraTexture: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    /** Телефон без ARCore: фон с обычной камеры, а положение существа считается по датчикам наклона. */
    @Volatile
    var cameraMode = false

    /** Отношение сторон вертикального кадра камеры. Ноль, пока камера не запущена. */
    @Volatile
    var cameraAspect = 0f

    @Volatile
    private var newCameraFrame = false

    @Volatile
    private var walkUntil = 0L

    @Volatile
    var level = 0

    @Volatile
    var species = Species.DRAKO

    @Volatile
    private var orbitDegrees = 0f

    @Volatile
    private var celebrateUntil = 0L

    private val background = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val meshRenderer = MeshRenderer()
    private val skinnedRenderer = SkinnedRenderer()
    private val loadedModels = HashMap<String, SkinnedInstance>()
    private lateinit var disc: Mesh

    private val creature = Creature()
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var appliedRotation = -1
    private var appliedSession: Session? = null
    private var emulatorBackgroundFailed = false
    private var headingX = 0f
    private var headingZ = -1f
    private var lastFrameNanos = 0L
    private var lastHint: Int? = NO_HINT_SENT
    private var cameraTexture: SurfaceTexture? = null
    private var hasCameraFrame = false
    private val cameraTransform = FloatArray(16)
    private val phoneRotation = FloatArray(9)

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val base = FloatArray(16)
    private val part = FloatArray(16)
    private val point = FloatArray(4)
    private val clip = FloatArray(4)

    fun onOrbit(deltaDegrees: Float) {
        orbitDegrees = (orbitDegrees + deltaDegrees) % 360f
    }

    /** Анимация радости после роста или выбора питомца. */
    fun celebrate() {
        celebrateUntil = SystemClock.elapsedRealtime() + CELEBRATE_MILLIS
    }

    /** Хозяин идёт: без отслеживания положения об этом можно узнать только по шагам. */
    fun onStep() {
        walkUntil = SystemClock.elapsedRealtime() + WALK_AFTER_STEP_MILLIS
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Текстура камеры пропала вместе с прежним контекстом.
        cameraTexture?.release()
        cameraTexture = null
        hasCameraFrame = false
        if (emulatorMode) GLES20.glClearColor(0.06f, 0.09f, 0.15f, 1f) else GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        background.createOnGlThread()
        planeRenderer.createOnGlThread()
        meshRenderer.createOnGlThread()
        skinnedRenderer.createOnGlThread()
        disc = Mesh.disc()
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
        if (viewportWidth == 0 || viewportHeight == 0) return
        val deltaSeconds = nextDeltaSeconds()
        when {
            cameraMode -> drawCameraFrame(deltaSeconds)
            emulatorMode -> drawEmulatorFrame(deltaSeconds)
            else -> drawArFrame(deltaSeconds)
        }
    }

    private fun drawCameraFrame(deltaSeconds: Float) {
        val texture = cameraTexture ?: SurfaceTexture(background.textureId).also { created ->
            created.setOnFrameAvailableListener { newCameraFrame = true }
            cameraTexture = created
            onCameraTexture(created)
        }
        if (newCameraFrame) {
            newCameraFrame = false
            texture.updateTexImage()
            texture.getTransformMatrix(cameraTransform)
            hasCameraFrame = true
        }
        val aspect = viewportWidth.toFloat() / viewportHeight
        val imageAspect = cameraAspect
        if (hasCameraFrame && imageAspect > 0f) {
            val (scaleX, scaleY) = centerCropScale(imageAspect, aspect)
            background.drawCamera(cameraTransform, scaleX, scaleY)
        }

        readPhoneRotation(phoneRotation)
        phoneViewMatrix(phoneRotation, PHONE_HEIGHT_METERS, view)
        Matrix.perspectiveM(projection, 0, PHONE_FOV_DEGREES, aspect, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        // Оси телефона в мире игры: y смотрит вверх, а z на юг.
        updateHeading(phoneRotation[1] - phoneRotation[2], phoneRotation[5] - phoneRotation[4])
        creature.update(
            deltaSeconds,
            -headingZ * PHONE_BESIDE_METERS + headingX * PHONE_AHEAD_METERS,
            headingX * PHONE_BESIDE_METERS + headingZ * PHONE_AHEAD_METERS,
        )
        drawCreature(0f, light = 1f, showArena = false, deltaSeconds)
        publishHint(visibilityHint(0f))
    }

    private fun drawArFrame(deltaSeconds: Float) {
        val session = session ?: return
        prepareSession(session)
        val frame = try {
            session.update()
        } catch (_: SessionPausedException) {
            return
        } catch (e: CameraNotAvailableException) {
            onArFailed(e)
            return
        } catch (e: FatalException) {
            onArFailed(e)
            return
        }
        background.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            publishHint(trackingHint(camera.trackingFailureReason))
            return
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        val planes = session.getAllTrackables(Plane::class.java)
        val floorY = findFloorHeight(planes, camera)
        if (floorY == null) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            planeRenderer.draw(planes, viewProjection)
            GLES20.glDisable(GLES20.GL_BLEND)
            publishHint(R.string.pet_hint_find_floor)
            return
        }

        updateHeading(camera)
        val pose = camera.pose
        // Справа от хозяина и чуть впереди, чтобы существо попадало в кадр, когда смотришь под ноги.
        val rightX = -headingZ
        val rightZ = headingX
        creature.update(
            deltaSeconds,
            pose.tx() + rightX * BESIDE_METERS + headingX * AHEAD_METERS,
            pose.tz() + rightZ * BESIDE_METERS + headingZ * AHEAD_METERS,
        )
        val light = frame.lightEstimate.let {
            if (it.state == LightEstimate.State.VALID) {
                (it.pixelIntensity / AVERAGE_PIXEL_INTENSITY).coerceIn(0.5f, 1.3f)
            } else {
                1f
            }
        }
        drawCreature(floorY, light, showArena = false, deltaSeconds)
        publishHint(visibilityHint(floorY))
    }

    private fun drawEmulatorFrame(deltaSeconds: Float) {
        drawEmulatorBackground()
        // Хозяина в эмуляторе нет, поэтому существо гуляет по кругу.
        val angle = SystemClock.elapsedRealtime() / 1000f * EMULATOR_WALK_RADIANS_PER_SECOND
        creature.update(deltaSeconds, cos(angle) * EMULATOR_WALK_RADIUS, sin(angle) * EMULATOR_WALK_RADIUS)

        // Камера отъезжает вместе с ростом, но медленнее него: существо заметно крупнеет и не вылезает из кадра.
        val height = creatureHeightMeters(level)
        val distance = EMULATOR_CAMERA_BASE_DISTANCE + height * EMULATOR_CAMERA_DISTANCE_PER_HEIGHT
        val yaw = Math.toRadians(orbitDegrees.toDouble())
        Matrix.setLookAtM(
            view, 0,
            (sin(yaw) * distance).toFloat(), distance * EMULATOR_CAMERA_ELEVATION, (cos(yaw) * distance).toFloat(),
            0f, height * EMULATOR_LOOK_PER_HEIGHT, 0f,
            0f, 1f, 0f,
        )
        Matrix.perspectiveM(projection, 0, EMULATOR_FOV_DEGREES, viewportWidth.toFloat() / viewportHeight, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        drawCreature(0f, light = 1f, showArena = true, deltaSeconds)
        publishHint(R.string.pet_hint_emulator)
    }

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
            appliedRotation = -1
        }
        val rotation = displayRotation()
        if (rotation != appliedRotation) {
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

    /** Пол: самая низкая поверхность под камерой, чтобы существо не ходило по столу. */
    private fun findFloorHeight(planes: Collection<Plane>, camera: Camera): Float? {
        val cameraY = camera.pose.ty()
        return planes
            .filter {
                it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            .map { it.centerPose.ty() }
            .filter { it < cameraY - MIN_FLOOR_DEPTH }
            .minOrNull()
    }

    /** Направление хозяина по полу. Верх телефона учитывается, потому что при взгляде вниз камера смотрит в пол. */
    private fun updateHeading(camera: Camera) {
        val pose = camera.displayOrientedPose
        val z = pose.zAxis
        val y = pose.yAxis
        updateHeading(y[0] - z[0], y[2] - z[2])
    }

    private fun updateHeading(directionX: Float, directionZ: Float) {
        val length = hypot(directionX, directionZ)
        if (length < 1e-3f) return
        headingX += (directionX / length - headingX) * HEADING_SMOOTHING
        headingZ += (directionZ / length - headingZ) * HEADING_SMOOTHING
        val headingLength = max(hypot(headingX, headingZ), 1e-3f)
        headingX /= headingLength
        headingZ /= headingLength
    }

    private fun drawCreature(floorY: Float, light: Float, showArena: Boolean, deltaSeconds: Float) {
        val time = SystemClock.elapsedRealtime()
        val form = species.formFor(level)
        val height = creatureHeightMeters(level) * form.scale
        val walking = creature.speed > WALKING_SPEED || time < walkUntil
        val hover = if (form.hover > 0f) form.hover + sin(time / 420f) * 0.04f else 0f

        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, creature.x, floorY + hover * height, creature.z)
        Matrix.rotateM(base, 0, creature.yawDegrees, 0f, 1f, 0f)
        Matrix.scaleM(base, 0, height, height, height)
        drawModel(form.model, deltaSeconds, walking, time, light)

        meshRenderer.begin(light)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        if (showArena) {
            Matrix.setIdentityM(part, 0)
            Matrix.translateM(part, 0, 0f, floorY + 0.001f, 0f)
            Matrix.scaleM(part, 0, EMULATOR_ARENA_RADIUS, 1f, EMULATOR_ARENA_RADIUS)
            meshRenderer.draw(disc, part, viewProjection, 0.2f, 0.7f, 0.9f, alpha = 0.2f, emissive = 1f)
        }
        Matrix.setIdentityM(part, 0)
        Matrix.translateM(part, 0, creature.x, floorY + 0.003f, creature.z)
        Matrix.scaleM(part, 0, height * form.shadow, 1f, height * form.shadow)
        meshRenderer.draw(disc, part, viewProjection, 0f, 0f, 0f, alpha = if (form.hover > 0f) 0.2f else 0.32f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        meshRenderer.end()
    }

    private fun drawModel(source: ModelSource, deltaSeconds: Float, walking: Boolean, time: Long, light: Float) {
        val instance = loadedModels.getOrPut(source.asset) {
            SkinnedInstance(GlbParser.parse(loadAsset(source.asset)), source.idleClip)
        }
        val clip = when {
            time < celebrateUntil -> source.celebrateClip
            walking -> source.walkClip
            else -> source.idleClip
        }
        instance.animator.play(clip)
        instance.animator.update(deltaSeconds)
        Matrix.multiplyMM(part, 0, base, 0, instance.normalize, 0)
        skinnedRenderer.draw(instance, part, viewProjection, light)
    }

    private fun visibilityHint(floorY: Float): Int? {
        point[0] = creature.x
        point[1] = floorY + creatureHeightMeters(level) / 2f
        point[2] = creature.z
        point[3] = 1f
        Matrix.multiplyMV(clip, 0, viewProjection, 0, point, 0)
        val w = clip[3]
        val visible = w > 0f && abs(clip[0] / w) <= 1f && abs(clip[1] / w) <= 1f
        return if (visible) null else R.string.pet_hint_look_down
    }

    private fun trackingHint(reason: TrackingFailureReason): Int = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.tracking_dark
        TrackingFailureReason.EXCESSIVE_MOTION -> R.string.tracking_motion
        TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.tracking_features
        TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.tracking_camera
        else -> R.string.pet_hint_find_floor
    }

    private fun publishHint(hint: Int?) {
        if (hint != lastHint) {
            lastHint = hint
            onHint(hint)
        }
    }

    private companion object {
        const val NO_HINT_SENT = -1
        const val NEAR_CLIP = 0.05f
        const val FAR_CLIP = 30f
        const val AVERAGE_PIXEL_INTENSITY = 0.466f
        const val CELEBRATE_MILLIS = 2500L
        const val MIN_FLOOR_DEPTH = 0.3f
        const val BESIDE_METERS = 0.35f
        const val AHEAD_METERS = 0.35f
        const val HEADING_SMOOTHING = 0.08f
        const val WALKING_SPEED = 0.02f
        const val WALK_AFTER_STEP_MILLIS = 3000L
        const val PHONE_HEIGHT_METERS = 1.2f
        const val PHONE_FOV_DEGREES = 65f

        // Подальше и ближе к центру: хозяин не может подвинуть камеру, чтобы найти существо, как в AR.
        const val PHONE_BESIDE_METERS = 0.2f
        const val PHONE_AHEAD_METERS = 0.7f
        const val EMULATOR_WALK_RADIUS = 0.22f
        const val EMULATOR_WALK_RADIANS_PER_SECOND = 0.7f
        const val EMULATOR_CAMERA_BASE_DISTANCE = 0.95f
        const val EMULATOR_CAMERA_DISTANCE_PER_HEIGHT = 2.2f
        const val EMULATOR_CAMERA_ELEVATION = 0.45f
        const val EMULATOR_LOOK_PER_HEIGHT = 0.6f
        const val EMULATOR_FOV_DEGREES = 60f
        const val EMULATOR_ARENA_RADIUS = 1f
    }
}
