package com.kocti.crystaldefense.pet

/** Поворот телефона, который держат вертикально экраном к себе. Матрица 3×3 по строкам, как у SensorManager. */
val UPRIGHT_PHONE = floatArrayOf(
    1f, 0f, 0f,
    0f, 0f, -1f,
    0f, 1f, 0f,
)

/**
 * Матрица вида для камеры без отслеживания положения: телефон только вращается на месте на высоте [height] над полом.
 * [rotation] переводит оси телефона в оси восток, север, верх. В мире игры y смотрит вверх, а z на юг.
 */
fun phoneViewMatrix(rotation: FloatArray, height: Float, out: FloatArray) {
    for (axis in 0..2) {
        val east = rotation[axis]
        val north = rotation[3 + axis]
        val up = rotation[6 + axis]
        // Строка матрицы вида — ось камеры в мире, поэтому оси записываются поперёк столбцов.
        out[axis] = east
        out[4 + axis] = up
        out[8 + axis] = -north
        out[12 + axis] = -up * height
    }
    out[3] = 0f
    out[7] = 0f
    out[11] = 0f
    out[15] = 1f
}

/** Во сколько раз уменьшить текстурные координаты по x и y, чтобы картинка заполнила экран без искажений. */
fun centerCropScale(imageAspect: Float, viewAspect: Float): Pair<Float, Float> =
    if (imageAspect > viewAspect) {
        viewAspect / imageAspect to 1f
    } else {
        1f to imageAspect / viewAspect
    }
