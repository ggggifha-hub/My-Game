package com.kocti.crystaldefense.pet.glb

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.Matrix
import com.kocti.crystaldefense.pet.ModelSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Рисует неподвижный кадр модели в прозрачную картинку. Нужен, чтобы показать существо
 * на фоне его названия в списке персонажей, не запуская отдельную сцену OpenGL на каждую карточку.
 */
object ModelThumbnail {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** Готовая картинка формы, если её уже нарисовали. Ключ — путь к модели, то есть форма и уровень. */
    fun cached(model: ModelSource): Bitmap? = cache[model.asset]

    /**
     * Рисует модель в позе покоя и запоминает результат. Тяжёлую работу (разбор GLB и OpenGL)
     * вызывающий код должен выполнять не в главном потоке. При любой ошибке возвращает null —
     * тогда карточка просто останется без фона.
     */
    fun render(model: ModelSource, bytes: ByteArray, sizePx: Int): Bitmap? {
        cache[model.asset]?.let { return it }
        return try {
            val bitmap = Offscreen(sizePx).use { draw(it, model, bytes, sizePx) }
            bitmap?.also { cache[model.asset] = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun draw(offscreen: Offscreen, model: ModelSource, bytes: ByteArray, sizePx: Int): Bitmap {
        GLES20.glViewport(0, 0, sizePx, sizePx)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val glb = GlbParser.parse(bytes)
        val instance = SkinnedInstance(glb, model.idleClip)
        val renderer = SkinnedRenderer().apply { createOnGlThread() }

        // Фронтальный вид: камера чуть выше середины фигуры, как в предпросмотре на эмуляторе.
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, 0f, 0.52f, 2.2f, 0f, 0.5f, 0f, 0f, 1f, 0f)
        val projection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 32f, 1f, 0.1f, 10f)
        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        renderer.draw(instance, instance.normalize, viewProjection, light = 1f)
        GLES20.glFinish()
        return readPixels(sizePx)
    }

    private fun readPixels(sizePx: Int): Bitmap {
        val pixels = ByteBuffer.allocateDirect(sizePx * sizePx * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, sizePx, sizePx, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        val raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(pixels)
        // OpenGL отдаёт строки снизу вверх, поэтому переворачиваем картинку по вертикали.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(raw, 0, 0, sizePx, sizePx, flip, false)
        if (flipped !== raw) raw.recycle()
        return flipped
    }

    /** Отдельный контекст OpenGL, рисующий в буфер без экрана. Закрывается через [use]. */
    private class Offscreen(sizePx: Int) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val surface: android.opengl.EGLSurface

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val config = chooseConfig()
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, sizePx, EGL14.EGL_HEIGHT, sizePx, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        private fun chooseConfig(): EGLConfig {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "No matching EGL config"
            }
            return configs[0]!!
        }

        inline fun <T> use(block: (Offscreen) -> T): T = try {
            block(this)
        } finally {
            release()
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
