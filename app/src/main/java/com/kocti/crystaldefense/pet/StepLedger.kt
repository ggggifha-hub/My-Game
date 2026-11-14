package com.kocti.crystaldefense.pet

const val STEPS_PER_GROWTH = 10_000
const val NO_DAY = Long.MIN_VALUE
const val NO_COUNTER = -1L
const val STRENGTH_PER_LEVEL = 5
const val DEVELOPER_CODE_LENGTH = 8

private const val DEVELOPER_CODE = "13243546"

fun isDeveloperCode(code: String): Boolean = code == DEVELOPER_CODE

const val BEDTIME_HOUR = 21
const val WAKE_HOUR = 9

/** С 21:00 до 9:00 существо можно уложить спать в любом состоянии. */
fun isBedtimeHour(hour: Int): Boolean = hour >= BEDTIME_HOUR || hour < WAKE_HOUR

/** Часы, заканчивающиеся с 10:00 по 21:00, дневные. Остальные ночные. */
private fun isDayHour(hour: Int): Boolean = hour in (WAKE_HOUR + 1)..BEDTIME_HOUR

/** Показатели существа от 0 до 100. У [sleep] и [hunger] больше значит лучше: 0 — совсем не спал и совсем не ел. */
data class PetVitals(
    val sleep: Int = 100,
    val hunger: Int = 10,
    val sadness: Int = 10,
    val joy: Int = 60,
) {
    /**
     * Час бодрствования, закончившийся в [hour]:00. Часы с 10 по 21 дневные: сон падает ровно на 90 за день.
     * Часы с 22 по 9 ночные: без укладывания сон к полуночи доходит до нуля, а голод падает на 60 за ночь.
     */
    fun afterHour(hour: Int): PetVitals {
        val tired = sleep <= LOW_SLEEP
        val starving = hunger <= LOW_HUNGER
        val day = isDayHour(hour)
        return PetVitals(
            sleep = if (day) limit(sleep - daySleepLoss(hour)) else nightSleep(hour),
            hunger = limit(hunger - if (day) HUNGER_LOSS_PER_DAY_HOUR else HUNGER_LOSS_PER_NIGHT_HOUR),
            sadness = limit(sadness + 1 - (if (joy >= HIGH_JOY) 2 else 0) + (if (starving) 2 else 0) + (if (tired) 1 else 0)),
            joy = limit(joy - 2 - (if (starving) 1 else 0)),
        )
    }

    /** 90 единиц на 12 часов не делятся нацело, поэтому потери чередуются так, чтобы за день вышло ровно 90. */
    private fun daySleepLoss(hour: Int): Int {
        val done = hour - WAKE_HOUR
        return done * SLEEP_LOSS_PER_DAY / DAY_HOURS - (done - 1) * SLEEP_LOSS_PER_DAY / DAY_HOURS
    }

    /** Остаток сна равномерно тает к полуночи и дальше остаётся нулём. */
    private fun nightSleep(hour: Int): Int = when {
        hour == 0 -> 0
        hour >= BEDTIME_HOUR -> sleep * (24 - hour) / (25 - hour)
        else -> sleep
    }

    /**
     * Час сна. Ночью к 9:00 сон полный, а голод за ночь падает на 60, как и у бодрствующего.
     * Днём отсыпается только тот, кто пропустил ночь: сон растёт от нуля до 100 примерно за 8 часов.
     */
    fun afterSleepingHour(hour: Int): PetVitals {
        val rested = if (isDayHour(hour)) {
            sleep + DAY_SLEEP_GAIN_PER_HOUR
        } else {
            sleep + (100 - sleep) / ((WAKE_HOUR - hour + 24) % 24 + 1)
        }
        return copy(sleep = limit(rested), hunger = limit(hunger - HUNGER_LOSS_PER_NIGHT_HOUR))
    }

    /** Прогулка кормит и радует. Считаются только целые порции шагов. */
    fun afterWalk(stepsBefore: Int, stepsAfter: Int): PetVitals {
        fun portions(size: Int) = stepsAfter / size - stepsBefore / size
        return copy(
            hunger = limit(hunger + portions(STEPS_PER_FOOD_POINT)),
            sadness = limit(sadness - portions(200)),
            joy = limit(joy + portions(200)),
        )
    }

    fun afterBattle(won: Boolean?): PetVitals = when (won) {
        true -> copy(joy = limit(joy + 15), sadness = limit(sadness - 10))
        false -> copy(joy = limit(joy - 10), sadness = limit(sadness + 15))
        null -> this
    }

    private fun limit(value: Int) = value.coerceIn(0, 100)

    companion object {
        const val LOW_SLEEP = 20
        const val LOW_HUNGER = 20
        const val HIGH_JOY = 70

        /** 5000 шагов наполняют шкалу еды от 0 до 100. */
        const val STEPS_PER_FOOD_POINT = 50
        const val HUNGER_LOSS_PER_DAY_HOUR = 10

        /** 60 единиц за 12 ночных часов. */
        const val HUNGER_LOSS_PER_NIGHT_HOUR = 5
        const val SLEEP_LOSS_PER_DAY = 90
        const val DAY_HOURS = 12
        const val DAY_SLEEP_GAIN_PER_HOUR = 13
    }
}

/** Сила зависит от вида и растёт с уровнем. Голодное или сонное существо слабее, радостное сильнее. */
fun strengthOf(species: Species, level: Int, vitals: PetVitals): Int {
    var percent = 100
    if (vitals.hunger <= PetVitals.LOW_HUNGER) percent -= 20
    if (vitals.sleep <= PetVitals.LOW_SLEEP) percent -= 20
    if (vitals.joy >= PetVitals.HIGH_JOY) percent += 10
    return (species.baseStrength + STRENGTH_PER_LEVEL * level) * percent / 100
}

/** Часов голода днём на один потерянный уровень. */
const val STARVING_HOURS_PER_LEVEL = 3

/**
 * Уровень существа, сколько шагов оно съело сегодня, его показатели и спит ли оно.
 * [starvingHours] считает дневные часы с пустым голодом, [lostLevelTonight] не даёт потерять второй уровень за ночь.
 */
data class PetProgress(
    val level: Int = 0,
    val fedToday: Int = 0,
    val vitals: PetVitals = PetVitals(),
    val asleep: Boolean = false,
    val starvingHours: Int = 0,
    val lostLevelTonight: Boolean = false,
) {
    /** Днём кнопка сна остаётся у того, кто не спал ночь и остался с нулевым сном. */
    fun canPutToSleep(hour: Int): Boolean = !asleep && (isBedtimeHour(hour) || vitals.sleep == 0)

    /** Час выбранного существа, закончившийся в [hour]:00. Спящее защищено от потери уровня. */
    fun afterHour(hour: Int): PetProgress {
        if (asleep) return afterSleepingHour(hour)
        val next = vitals.afterHour(hour)
        return if (isDayHour(hour)) afterDayHour(next) else afterNightHour(next, hour)
    }

    /** Ночью существо спит до 9:00, а днём просыпается, как только сон восстановится до 100. */
    private fun afterSleepingHour(hour: Int): PetProgress {
        val rested = vitals.afterSleepingHour(hour)
        val wakes = hour == WAKE_HOUR || (isDayHour(hour) && rested.sleep == 100)
        if (!wakes) return copy(vitals = rested)
        return copy(
            vitals = rested,
            asleep = false,
            starvingHours = 0,
            lostLevelTonight = false,
        )
    }

    private fun afterDayHour(next: PetVitals): PetProgress {
        val starving = if (next.hunger == 0) starvingHours + 1 else 0
        val lost = starving >= STARVING_HOURS_PER_LEVEL
        return copy(
            vitals = next,
            level = if (lost) (level - 1).coerceAtLeast(0) else level,
            starvingHours = if (lost) 0 else starving,
        )
    }

    private fun afterNightHour(next: PetVitals, hour: Int): PetProgress {
        val lost = !lostLevelTonight && (next.hunger == 0 || next.sleep == 0)
        return copy(
            vitals = next,
            level = if (lost) (level - 1).coerceAtLeast(0) else level,
            starvingHours = 0,
            lostLevelTonight = hour != WAKE_HOUR && (lostLevelTonight || lost),
        )
    }
}

/**
 * Учёт шагов без зависимостей от Android. Шаги кормят только выбранное существо,
 * а рост засчитывается лишь за шаги, съеденные им за один календарный день.
 */
data class StepState(
    val epochDay: Long = NO_DAY,
    val todaySteps: Int = 0,
    val lastCounter: Long = NO_COUNTER,
    val activePet: String = Species.DRAKO.name,
    val pets: Map<String, PetProgress> = emptyMap(),
) {
    private val active: PetProgress get() = progressOf(activePet)

    val level: Int get() = active.level

    val asleep: Boolean get() = active.asleep

    fun canPutToSleep(hour: Int): Boolean = active.canPutToSleep(hour)

    val vitals: PetVitals get() = active.vitals

    /** Сколько шагов выбранное существо уже съело на пути к следующему росту. */
    val stepsTowardGrowth: Int get() = active.fedToday % STEPS_PER_GROWTH

    fun progressOf(pet: String): PetProgress = pets[pet] ?: PetProgress()

    fun levelOf(pet: String): Int = progressOf(pet).level

    fun withActivePet(pet: String): StepState = copy(activePet = pet)

    fun onDay(day: Long): StepState =
        if (day == epochDay) this else withoutTodaySteps().copy(epochDay = day)

    /** Датчик отдаёт сумму шагов с момента включения телефона, поэтому считается только разница показаний. */
    fun withCounter(counter: Long, day: Long): StepState {
        val delta = when {
            lastCounter == NO_COUNTER -> 0L
            // Показание меньше прежнего: телефон перезагружался, и счёт датчика начался заново.
            counter < lastCounter -> counter
            else -> counter - lastCounter
        }
        return withSteps(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), day).copy(lastCounter = counter)
    }

    fun withSteps(steps: Int, day: Long): StepState {
        val current = onDay(day)
        val pet = current.active
        val fed = (pet.fedToday.toLong() + steps).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val growths = fed / STEPS_PER_GROWTH - pet.fedToday / STEPS_PER_GROWTH
        return current.copy(
            todaySteps = (current.todaySteps.toLong() + steps).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            pets = current.pets + (activePet to pet.copy(
                level = pet.level + growths,
                fedToday = fed,
                vitals = pet.vitals.afterWalk(pet.fedToday, fed),
            )),
        )
    }

    /**
     * Час, закончившийся в [hour]:00. Живёт только выбранное существо: остальных нельзя ни покормить, ни уложить.
     * Утром просыпаются все, иначе существо, которое перестали выбирать во сне, спало бы бесконечно.
     */
    fun afterHour(hour: Int): StepState {
        val others = if (hour == WAKE_HOUR) pets.mapValues { (_, pet) -> pet.copy(asleep = false) } else pets
        return copy(pets = others + (activePet to active.afterHour(hour)))
    }

    fun withAsleep(asleep: Boolean): StepState = copy(pets = pets + (activePet to active.copy(asleep = asleep)))

    /** [won] равен null при ничьей. */
    fun afterBattle(pet: String, won: Boolean?): StepState {
        val progress = progressOf(pet)
        return copy(pets = pets + (pet to progress.copy(vitals = progress.vitals.afterBattle(won))))
    }

    private fun withoutTodaySteps(): StepState = copy(
        todaySteps = 0,
        pets = pets.mapValues { (_, progress) -> progress.copy(fedToday = 0) },
    )

    /**
     * Полный сброс: шаги за сегодня, уровни и показатели всех существ.
     * Показание датчика остаётся, иначе следующее чтение вернуло бы обнулённые шаги обратно.
     */
    fun withProgressReset(): StepState = copy(
        todaySteps = 0,
        pets = pets.mapValues { PetProgress() },
    )
}
