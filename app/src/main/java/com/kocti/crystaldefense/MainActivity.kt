package com.kocti.crystaldefense

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlin.math.hypot

class MainActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArGameRenderer
    private lateinit var stats: LinearLayout
    private lateinit var score: TextView
    private lateinit var wave: TextView
    private lateinit var health: TextView
    private lateinit var hint: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelAction: Button

    private var session: Session? = null

    /** На эмуляторе и на телефоне без ARCore игра идёт вокруг виртуальной камеры. */
    private val emulatorMode by lazy {
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            !ArCoreApk.getInstance().checkAvailability(this).isSupported
    }
    private var gles3 = true
    private var glSupported = true
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchLastX = 0f
    private var touchDragging = false
    private var installRequested = false
    private var permissionRequested = false
    private var bestScore = 0
    private val preferences by lazy { getSharedPreferences("game", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.surface)
        stats = findViewById(R.id.stats)
        score = findViewById(R.id.score)
        wave = findViewById(R.id.wave)
        health = findViewById(R.id.health)
        hint = findViewById(R.id.hint)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        panelAction = findViewById(R.id.panelAction)
        bestScore = preferences.getInt(KEY_BEST_SCORE, 0)

        applySystemBarInsets(findViewById(R.id.hud))

        renderer = ArGameRenderer(
            emulatorMode = emulatorMode,
            displayRotation = ::currentDisplayRotation,
            onHud = { state -> runOnUiThread { showHud(state) } },
            onCameraFailed = { runOnUiThread { showCameraFailure() } },
            onArFailed = { e -> runOnUiThread { showArFailure(e) } },
        )
        // Без этой проверки GLSurfaceView упадёт в своём потоке с IllegalArgumentException.
        val glVersion = getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion
        gles3 = glVersion >= GL_ES_3
        // На эмуляторе без ES 3 игра работает без ARCore: её шейдерам хватает ES 2.
        glSupported = gles3 || emulatorMode
        if (!glSupported) {
            showError(getString(R.string.gles3_required))
            return
        }
        surface.setPreserveEGLContextOnPause(true)
        surface.setEGLContextClientVersion(if (gles3) 3 else 2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.setOnTouchListener { view, event ->
            if (emulatorMode) {
                handleEmulatorTouch(view, event)
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                view.performClick()
                renderer.onTap(event.x, event.y)
            }
            true
        }
    }

    /** Мышью нельзя обойти кристалл, как с телефоном, поэтому перетаскивание вращает камеру, а клик стреляет. */
    private fun handleEmulatorTouch(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchLastX = event.x
                touchDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (hypot(event.x - touchDownX, event.y - touchDownY) > slop) touchDragging = true
                if (touchDragging) renderer.onOrbit((touchLastX - event.x) * ORBIT_DEGREES_PER_PIXEL)
                touchLastX = event.x
            }
            MotionEvent.ACTION_UP -> if (!touchDragging) {
                view.performClick()
                renderer.onTap(event.x, event.y)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!glSupported) return
        // Игрок мог установить ARCore или закрыть приложение с камерой, поэтому после возврата пробуем снова.
        if (panelAction.tag == TAG_ERROR) {
            panelAction.tag = null
            panel.visibility = View.GONE
        }
        if (emulatorMode) {
            resumeOnEmulator()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            } else {
                showPermissionPanel()
            }
            return
        }
        if (panel.visibility == View.VISIBLE && panelAction.tag == TAG_PERMISSION) panel.visibility = View.GONE
        if (!ensureSession()) return
        try {
            session?.resume()
        } catch (_: CameraNotAvailableException) {
            showCameraFailure()
            return
        } catch (e: FatalException) {
            showArFailure(e)
            return
        }
        surface.onResume()
    }

    private fun resumeOnEmulator() {
        val hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera && !permissionRequested) {
            permissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (hasCamera && gles3) startEmulatorBackground()
        surface.onResume()
    }

    /** ARCore здесь нужен только как источник фона. Если он недоступен, игра идёт на однотонном фоне. */
    private fun startEmulatorBackground() {
        try {
            val current = session ?: Session(this).also { created ->
                created.configure(
                    Config(created).apply {
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    },
                )
                session = created
                renderer.session = created
            }
            current.resume()
        } catch (e: UnavailableException) {
            Log.w(TAG, "No ARCore background on emulator", e)
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "No camera background on emulator", e)
        } catch (e: FatalException) {
            Log.w(TAG, "No ARCore background on emulator", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!glSupported) return
        // Порядок важен: сначала останавливается рендер, который вызывает session.update().
        surface.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (!emulatorMode && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) showPermissionPanel()
        // При согласии сессию создаст onResume, который система вызовет после закрытия диалога.
    }

    /** Возвращает true, когда сессия готова. При false приложение ждёт установку ARCore или показывает ошибку. */
    private fun ensureSession(): Boolean {
        if (session != null) return true
        try {
            val install = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return false
            }
            val created = Session(this)
            created.configure(
                Config(created).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                },
            )
            session = created
            renderer.session = created
            return true
        } catch (e: UnavailableException) {
            showError(
                when (e) {
                    is UnavailableDeviceNotCompatibleException -> getString(R.string.arcore_unsupported)
                    is UnavailableUserDeclinedInstallationException,
                    is UnavailableArcoreNotInstalledException,
                    -> getString(R.string.arcore_declined)
                    is UnavailableApkTooOldException, is UnavailableSdkTooOldException -> getString(R.string.arcore_update)
                    else -> getString(R.string.arcore_failed, e.javaClass.simpleName)
                },
            )
            return false
        } catch (e: FatalException) {
            // Непроверяемая ошибка ARCore: например, сбой камеры или служб AR на конкретном устройстве.
            showArFailure(e)
            return false
        }
    }

    private fun showArFailure(e: FatalException) {
        Log.e(TAG, "ARCore failed", e)
        surface.onPause()
        showError(getString(R.string.arcore_failed, e.javaClass.simpleName))
    }

    private fun showHud(state: HudState) {
        stats.visibility = if (state.inGame) View.VISIBLE else View.GONE
        score.text = getString(R.string.hud_score, state.score)
        wave.text = getString(R.string.hud_wave, maxOf(state.wave, 1))
        health.text = "♦".repeat(state.crystalHp)

        val hintRes = state.hint
        hint.visibility = if (hintRes != null) View.VISIBLE else View.GONE
        if (hintRes != null) hint.setText(hintRes)

        if (state.gameOver) {
            if (state.score > bestScore) {
                bestScore = state.score
                preferences.edit().putInt(KEY_BEST_SCORE, bestScore).apply()
            }
            showPanel(
                title = getString(R.string.game_over),
                body = getString(R.string.game_over_stats, state.score, state.wave, bestScore),
                action = getString(R.string.restart),
                tag = TAG_RESTART,
            ) {
                panel.visibility = View.GONE
                renderer.onRestart()
            }
        } else if (panelAction.tag == TAG_RESTART) {
            panel.visibility = View.GONE
        }
    }

    private fun showPermissionPanel() {
        showPanel(
            title = getString(R.string.app_name),
            body = getString(R.string.permission_needed),
            action = getString(R.string.open_settings),
            tag = TAG_PERMISSION,
        ) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun showCameraFailure() {
        // Иначе поток рендера будет получать ту же ошибку в каждом кадре.
        surface.onPause()
        showError(getString(R.string.camera_failed))
    }

    private fun showError(message: String) {
        stats.visibility = View.GONE
        hint.visibility = View.GONE
        panelAction.tag = TAG_ERROR
        panelTitle.text = getString(R.string.app_name)
        panelBody.text = message
        panelAction.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    private fun showPanel(title: String, body: String, action: String, tag: String, onClick: () -> Unit) {
        panelTitle.text = title
        panelBody.text = body
        panelAction.text = action
        panelAction.tag = tag
        panelAction.visibility = View.VISIBLE
        panelAction.setOnClickListener { onClick() }
        panel.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.rotation else windowManager.defaultDisplay.rotation

    private companion object {
        const val TAG = "CrystalDefense"
        const val GL_ES_3 = 0x30000
        const val ORBIT_DEGREES_PER_PIXEL = 0.25f
        const val REQUEST_CAMERA = 1
        const val KEY_BEST_SCORE = "best_score"
        const val TAG_PERMISSION = "permission"
        const val TAG_RESTART = "restart"
        const val TAG_ERROR = "error"
    }
}
