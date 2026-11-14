package com.kocti.crystaldefense

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.UnavailableException
import com.kocti.crystaldefense.pet.BedtimeReceiver
import com.kocti.crystaldefense.pet.CameraFeed
import com.kocti.crystaldefense.pet.DEVELOPER_CODE_LENGTH
import com.kocti.crystaldefense.pet.PhonePose
import com.kocti.crystaldefense.pet.PetRenderer
import com.kocti.crystaldefense.pet.STEPS_PER_GROWTH
import com.kocti.crystaldefense.pet.Species
import com.kocti.crystaldefense.pet.formIndexForLevel
import com.kocti.crystaldefense.pet.isDeveloperCode
import com.kocti.crystaldefense.pet.StepState
import com.kocti.crystaldefense.pet.StepStore
import com.kocti.crystaldefense.pet.StepSyncJobService

/** Главный экран: шагомер и AR-питомец, который питается шагами. */
class PetActivity : Activity(), SensorEventListener {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: PetRenderer
    private lateinit var store: StepStore
    private lateinit var stepsText: TextView
    private lateinit var levelText: TextView
    private lateinit var vitalsText: TextView
    private lateinit var sleepButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelBody: TextView
    private lateinit var panelAction: Button

    private val handler = Handler(Looper.getMainLooper())
    private val phonePose by lazy { PhonePose(this) }
    private val cameraFeed by lazy {
        CameraFeed(this) { runOnUiThread { Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show() } }
    }
    private val isEmulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    private val cameraModeForced by lazy {
        // Проверка режима камеры на эмуляторе: на нём нельзя получить ответ ARCore «телефон не поддерживается».
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent.getBooleanExtra(EXTRA_CAMERA_MODE, false)
    }
    private val emulatorMode by lazy { isEmulator && !cameraModeForced }
    private var cameraMode = false
    private var cameraTexture: SurfaceTexture? = null
    private var resumed = false
    private var arCheckDeadline = 0L
    private var session: Session? = null
    private var gles3 = true
    private var installRequested = false
    private var permissionsRequested = false
    private var shownLevel = 0
    private var touchLastX = 0f
    private lateinit var menu: ScrollView
    private lateinit var bottomBar: View
    private lateinit var species: Species

    // Android 13+ с предиктивным жестом «Назад» не вызывает onBackPressed.
    private val backCallback by lazy { OnBackInvokedCallback { closeMenu() } }

    /** На Android до 13 «Назад» приходит как обычная клавиша. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && menu.visibility == View.VISIBLE) {
            closeMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.surface)
        stepsText = findViewById(R.id.stepsText)
        levelText = findViewById(R.id.levelText)
        progress = findViewById(R.id.stepsProgress)
        hint = findViewById(R.id.hint)
        panel = findViewById(R.id.panel)
        panelBody = findViewById(R.id.panelBody)
        panelAction = findViewById(R.id.panelAction)
        store = StepStore(this)
        progress.max = STEPS_PER_GROWTH
        progress.progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
        progress.progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
        applySystemBarInsets(findViewById(R.id.hud))

        menu = findViewById(R.id.menu)
        bottomBar = findViewById(R.id.bottomBar)
        species = store.selectedSpecies()
        findViewById<Button>(R.id.menuButton).setOnClickListener { openMenu() }
        findViewById<Button>(R.id.closeMenu).setOnClickListener { closeMenu() }
        findViewById<Button>(R.id.playGame).setOnClickListener {
            closeMenu()
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.openCharacters).setOnClickListener {
            closeMenu()
            startActivity(Intent(this, CharactersActivity::class.java))
        }
        findViewById<Button>(R.id.openHistory).setOnClickListener {
            closeMenu()
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.openBattle).setOnClickListener {
            closeMenu()
            startActivity(Intent(this, BattleActivity::class.java))
        }
        findViewById<Button>(R.id.developerMode).setOnClickListener {
            if (store.isDeveloperMode()) {
                store.setDeveloperMode(false)
                showDeveloperMode()
            } else {
                askDeveloperCode()
            }
        }
        showDeveloperMode()
        findViewById<Button>(R.id.addSteps).setOnClickListener { showSteps(store.addSteps(SIMULATED_STEPS)) }
        findViewById<Button>(R.id.addGrowth).setOnClickListener { showSteps(store.addSteps(STEPS_PER_GROWTH)) }
        findViewById<Button>(R.id.resetSteps).setOnClickListener { confirmStepsReset() }
        findViewById<Button>(R.id.skipHour).setOnClickListener {
            showSteps(store.skipHour())
            Toast.makeText(this, getString(R.string.dev_clock, store.clockText()), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.testBedtime).setOnClickListener { BedtimeReceiver.notify(this, species) }
        vitalsText = findViewById(R.id.vitalsText)
        sleepButton = findViewById(R.id.sleepButton)
        sleepButton.setOnClickListener { showSteps(store.putToSleep()) }
        BedtimeReceiver.schedule(this)

        renderer = PetRenderer(
            emulatorMode = emulatorMode,
            displayRotation = ::currentDisplayRotation,
            loadAsset = { path -> assets.open(path).use { it.readBytes() } },
            onHint = { res -> runOnUiThread { showHint(res) } },
            onArFailed = { e -> runOnUiThread { showArFailure(e) } },
            readPhoneRotation = { out -> phonePose.read(out) },
            onCameraTexture = { texture ->
                runOnUiThread {
                    cameraTexture = texture
                    if (resumed) startCameraFeed(texture)
                }
            },
        )
        renderer.species = species
        if (cameraModeForced) {
            cameraMode = true
            renderer.cameraMode = true
        }
        val state = store.load()
        shownLevel = state.level
        showSteps(state)

        gles3 = getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= GL_ES_3
        if (!gles3) {
            showPanel(getString(R.string.gles3_required))
            surface.visibility = View.GONE
            return
        }
        surface.setPreserveEGLContextOnPause(true)
        surface.setEGLContextClientVersion(if (gles3) 3 else 2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.setOnTouchListener { view, event ->
            if (emulatorMode) handleEmulatorTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    /** Пока экран открыт, кнопка сна и показатели должны меняться сами: например, ровно в 21:00. */
    private val clockTick = object : Runnable {
        override fun run() {
            showSteps(store.load())
            handler.postDelayed(this, CLOCK_TICK_MILLIS)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.postDelayed(clockTick, CLOCK_TICK_MILLIS)
        val state = store.load()
        val chosen = store.selectedSpecies()
        if (chosen != species) {
            // Питомца выбрали на экране персонажей. Его уровень не должен выглядеть как только что случившийся рост.
            species = chosen
            renderer.species = chosen
            renderer.celebrate()
            shownLevel = state.level
        }
        showSteps(state)
        if (!gles3) return
        panel.visibility = View.GONE
        val missing = missingPermissions()
        if (missing.isNotEmpty() && !permissionsRequested) {
            permissionsRequested = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startStepCounting()
        val hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when {
            emulatorMode -> {
                if (hasCamera && gles3) startEmulatorBackground()
                surface.onResume()
            }
            !hasCamera -> showPanel(getString(R.string.permission_needed), showSettings = true)
            else -> {
                arCheckDeadline = SystemClock.elapsedRealtime() + AR_CHECK_MILLIS
                resumeOnPhone()
            }
        }
    }

    /** ARCore используется только там, где он есть. Иначе работает обычная камера с датчиками наклона. */
    private fun resumeOnPhone() {
        if (!resumed) return
        if (!cameraMode && session == null) {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isTransient && SystemClock.elapsedRealtime() < arCheckDeadline) {
                // Первый ответ приходит не сразу: ARCore уточняет поддержку телефона.
                handler.postDelayed(::resumeOnPhone, AR_CHECK_RETRY_MILLIS)
                return
            }
            if (!availability.isSupported) switchToCameraMode()
        }
        if (!cameraMode && !startArSession()) {
            if (!cameraMode) return
        }
        if (cameraMode) resumeCameraMode() else surface.onResume()
    }

    private fun switchToCameraMode() {
        if (cameraMode) return
        cameraMode = true
        Toast.makeText(this, R.string.ar_fallback, Toast.LENGTH_LONG).show()
        // Сначала останавливается рендер, который вызывает session.update().
        surface.onPause()
        renderer.session = null
        session?.close()
        session = null
        renderer.cameraMode = true
    }

    private fun resumeCameraMode() {
        phonePose.start()
        cameraTexture?.let(::startCameraFeed)
        surface.onResume()
    }

    private fun startCameraFeed(texture: SurfaceTexture) {
        val aspect = cameraFeed.start(texture)
        if (aspect == null) Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
        renderer.cameraAspect = aspect ?: 0f
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        if (!gles3) return
        surface.onPause()
        session?.pause()
        if (cameraMode) {
            phonePose.stop()
            cameraFeed.stop()
        }
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        session = null
        if (cameraMode) cameraFeed.close()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val before = store.load().todaySteps
        val state = store.recordCounter(event.values[0].toLong())
        if (state.todaySteps > before) renderer.onStep()
        showSteps(state)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun missingPermissions(): List<String> = buildList {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.CAMERA)
        }
        if (!hasStepPermission()) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** До Android 10 разрешение на шаги не требуется, а его проверка вернула бы отказ. */
    private fun hasStepPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun startStepCounting() {
        val manager = getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        when {
            emulatorMode && sensor == null -> Unit
            sensor == null -> showStepsUnavailable(R.string.steps_no_sensor)
            !hasStepPermission() -> showStepsUnavailable(R.string.steps_no_permission)
            else -> {
                manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                StepSyncJobService.schedule(this)
            }
        }
    }

    private fun showStepsUnavailable(message: Int) {
        stepsText.setText(message)
    }

    private fun showSteps(state: StepState) {
        val form = species.formFor(state.level)
        stepsText.text = getString(R.string.steps_today, state.todaySteps)
        levelText.text = getString(
            R.string.pet_level,
            form.name,
            state.level,
            STEPS_PER_GROWTH - state.stepsTowardGrowth,
        )
        progress.progress = state.stepsTowardGrowth
        renderer.level = state.level
        vitalsText.text = getString(
            if (state.asleep) R.string.pet_vitals_asleep else R.string.pet_vitals,
            state.vitals.hunger,
            state.vitals.sleep,
        )
        sleepButton.visibility = if (store.canPutToSleep(state)) View.VISIBLE else View.GONE
        if (state.level < shownLevel) {
            Toast.makeText(this, getString(R.string.pet_lost_level, form.name, state.level), Toast.LENGTH_LONG).show()
        } else if (state.level > shownLevel) {
            renderer.celebrate()
            val message = if (formIndexForLevel(state.level) > formIndexForLevel(shownLevel)) {
                getString(R.string.pet_evolved, form.name, state.level)
            } else {
                getString(R.string.pet_grew, form.name, state.level)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        shownLevel = state.level
    }

    private fun openMenu() {
        // Высота панели зависит от длины текста, поэтому отступ считается при каждом открытии.
        val stats = findViewById<View>(R.id.statsPanel)
        (menu.layoutParams as FrameLayout.LayoutParams).topMargin = stats.bottom - stats.top + menuGap()
        menu.requestLayout()
        menu.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback,
            )
        }
    }

    private fun menuGap(): Int = (MENU_GAP_DP * resources.displayMetrics.density).toInt()

    private fun closeMenu() {
        menu.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
    }

    private fun showDeveloperMode() {
        val enabled = store.isDeveloperMode()
        findViewById<View>(R.id.devTools).visibility = if (enabled) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.developerMode).setText(if (enabled) R.string.dev_mode_off else R.string.dev_mode)
    }

    private fun askDeveloperCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(DEVELOPER_CODE_LENGTH))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dev_code_title)
            .setView(box)
            .setPositiveButton(R.string.dev_code_enter, null)
            .setNegativeButton(R.string.reset_steps_no, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        // Обычная кнопка диалога закрыла бы его и при неверном коде.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (isDeveloperCode(input.text.toString())) {
                store.setDeveloperMode(true)
                showDeveloperMode()
                dialog.dismiss()
            } else {
                input.text.clear()
                dialog.setTitle(R.string.dev_code_wrong)
            }
        }
    }

    private fun confirmStepsReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.reset_steps_confirm)
            .setPositiveButton(R.string.reset_steps_yes) { _, _ -> showSteps(store.resetProgress()) }
            .setNegativeButton(R.string.reset_steps_no, null)
            .show()
    }

    private fun showHint(res: Int?) {
        hint.visibility = if (res != null) View.VISIBLE else View.GONE
        if (res != null) hint.setText(res)
    }

    /** Возвращает true, когда сессия готова. При false приложение ждёт установку ARCore или показывает ошибку. */
    private fun startArSession(): Boolean {
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
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
            }
            session?.resume()
            return true
        } catch (e: UnavailableException) {
            // Телефон не подходит, игрок отказался от установки или она не удалась: без ARCore приложение тоже работает.
            Log.w(TAG, "ARCore is not available", e)
            switchToCameraMode()
        } catch (e: CameraNotAvailableException) {
            showArFailure(e)
        } catch (e: FatalException) {
            Log.w(TAG, "ARCore failed to start", e)
            switchToCameraMode()
        }
        return false
    }

    /** На эмуляторе ARCore нужен только как источник фона. */
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

    private fun handleEmulatorTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchLastX = event.x
            MotionEvent.ACTION_MOVE -> {
                renderer.onOrbit((touchLastX - event.x) * ORBIT_DEGREES_PER_PIXEL)
                touchLastX = event.x
            }
        }
    }

    private fun showArFailure(e: Exception) {
        Log.e(TAG, "ARCore failed", e)
        if (e is FatalException && !cameraMode) {
            switchToCameraMode()
            if (resumed) resumeCameraMode()
            return
        }
        surface.onPause()
        showPanel(getString(R.string.arcore_failed, e.javaClass.simpleName))
    }

    private fun showPanel(message: String, showSettings: Boolean = false) {
        panelBody.text = message
        panelAction.visibility = if (showSettings) View.VISIBLE else View.GONE
        panelAction.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
        panel.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.rotation else windowManager.defaultDisplay.rotation

    private companion object {
        const val TAG = "PetActivity"
        const val REQUEST_PERMISSIONS = 2
        const val GL_ES_3 = 0x30000
        const val EXTRA_CAMERA_MODE = "camera_mode"
        const val AR_CHECK_MILLIS = 5000L
        const val AR_CHECK_RETRY_MILLIS = 200L
        const val CLOCK_TICK_MILLIS = 20_000L
        const val MENU_GAP_DP = 8
        const val SIMULATED_STEPS = 1000
        const val ORBIT_DEGREES_PER_PIXEL = 0.25f
    }
}
