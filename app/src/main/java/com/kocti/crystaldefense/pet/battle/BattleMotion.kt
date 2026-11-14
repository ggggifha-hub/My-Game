package com.kocti.crystaldefense.pet.battle

/** Сколько длится бросок на соперника. */
const val ATTACK_MILLIS = 800L

/** В какой доле броска удар достигает цели. */
const val IMPACT_FRACTION = 0.45f

const val IMPACT_MILLIS = 360L

private const val SMALLEST_ARENA_HEIGHT = 0.8f
private const val ARENA_GROWTH = 0.4f
private const val LAST_FORM_LEVEL = 18

/** Доля пути к сопернику: резкий разгон до удара и более медленный возврат. */
fun lungeAmount(progress: Float): Float = when {
    progress <= 0f || progress >= 1f -> 0f
    progress < IMPACT_FRACTION -> (progress / IMPACT_FRACTION).let { it * it }
    else -> 1f - (progress - IMPACT_FRACTION) / (1f - IMPACT_FRACTION)
}

/** Рост на арене сжат: на настоящем масштабе новичок был бы еле виден рядом с соперником последней формы. */
fun arenaHeight(level: Int, formScale: Float): Float =
    (SMALLEST_ARENA_HEIGHT + ARENA_GROWTH * level.coerceAtMost(LAST_FORM_LEVEL) / LAST_FORM_LEVEL) * formScale
