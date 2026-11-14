package com.kocti.crystaldefense.pet

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** Как изменился уровень существа за один день. [endLevel] — уровень на конец этих изменений. */
data class LevelChange(val species: Species, val gained: Int, val lost: Int, val endLevel: Int) {
    fun encode(): String = "$gained,$lost,$endLevel"

    companion object {
        fun decode(species: Species, text: String?): LevelChange? {
            val parts = text?.split(",")?.map { it.toIntOrNull() ?: return null } ?: return null
            if (parts.size != 3) return null
            return LevelChange(species, parts[0], parts[1], parts[2])
        }
    }
}

/** Что изменилось в уровнях между двумя состояниями. Прежняя запись того же дня дополняется, а не заменяется. */
fun levelChanges(before: StepState, after: StepState, earlier: (Species) -> LevelChange?): List<LevelChange> =
    Species.entries.mapNotNull { species ->
        val delta = after.levelOf(species.name) - before.levelOf(species.name)
        if (delta == 0) return@mapNotNull null
        val previous = earlier(species)
        LevelChange(
            species,
            (previous?.gained ?: 0) + delta.coerceAtLeast(0),
            (previous?.lost ?: 0) + (-delta).coerceAtLeast(0),
            after.levelOf(species.name),
        )
    }

/** Клетки месяца по неделям с понедельника. Пустые клетки до первого числа и после последнего равны null. */
fun monthGrid(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val cells = ArrayList<LocalDate?>()
    repeat(leading) { cells.add(null) }
    for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

/**
 * Номер русской формы слова: 0 — «1 шаг», 1 — «2 шага», 2 — «5 шагов». Считается здесь, потому что
 * склонения из ресурсов Android выбираются по языку телефона, а на английском «1000 шага» было бы ошибкой.
 */
fun russianFormIndex(count: Int): Int {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> 2
        last == 1 -> 0
        last in 2..4 -> 1
        else -> 2
    }
}

/** Запись для узкой клетки календаря. */
fun compactSteps(steps: Int): String = when {
    steps < 1_000 -> steps.toString()
    steps < 100_000 -> "${steps / 1_000}.${steps / 100 % 10}к"
    else -> "${steps / 1_000}к"
}

/** Настоящий рост существа в AR с учётом формы. */
fun heightCentimeters(species: Species, level: Int): Int =
    (creatureHeightMeters(level) * species.formFor(level).scale * 100f).roundToInt()
