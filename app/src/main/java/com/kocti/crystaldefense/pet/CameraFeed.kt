package com.kocti.crystaldefense.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/** Изображение с задней камеры для телефонов без ARCore. Все методы вызываются из главного потока. */
class CameraFeed(private val context: Context, private val onFailed: () -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CameraFeed").apply { start() }
    private val handler = Handler(thread.looper)

    /** Открытая камера. Меняется только в потоке [handler]. */
    private var device: CameraDevice? = null

    /** Номер текущего запуска: камера, открывшаяся после stop(), сразу закрывается. */
    @Volatile
    private var run = 0

    /** Возвращает отношение ширины к высоте вертикального кадра или null, если камеры нет. */
    fun start(texture: SurfaceTexture): Float? {
        stop()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            val characteristics = manager.getCameraCharacteristics(id)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                .orEmpty()
            val size = sizes.filter { it.width <= MAX_WIDTH }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: return null
            texture.setDefaultBufferSize(size.width, size.height)
            val current = run
            manager.openCamera(id, stateCallback(texture, current), handler)
            val sideways = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.rem(180) != 0
            if (sideways) size.height.toFloat() / size.width else size.width.toFloat() / size.height
        } catch (e: Exception) {
            // На разных телефонах камера отказывает по-разному, но без неё питомец всё равно должен работать.
            Log.w(TAG, "Camera failed to start", e)
            null
        }
    }

    fun stop() {
        run++
        handler.post {
            device?.close()
            device = null
        }
    }

    fun close() {
        stop()
        thread.quitSafely()
    }

    private fun stateCallback(texture: SurfaceTexture, started: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (started != run) {
                camera.close()
                return
            }
            device = camera
            startPreview(camera, Surface(texture), started)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.w(TAG, "Camera error $error")
            camera.close()
            if (device === camera) device = null
            if (started == run) onFailed()
        }
    }

    @Suppress("DEPRECATION") // Замена появилась только в Android 9, а приложение работает с Android 8.
    private fun startPreview(camera: CameraDevice, surface: Surface, started: Int) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (started != run) return
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            session.setRepeatingRequest(request.build(), null, handler)
                        } catch (e: Exception) {
                            Log.w(TAG, "Camera preview failed", e)
                            onFailed()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onFailed()
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Camera session failed", e)
            onFailed()
        }
    }

    private companion object {
        const val TAG = "CameraFeed"
        const val MAX_WIDTH = 1920
    }
}
