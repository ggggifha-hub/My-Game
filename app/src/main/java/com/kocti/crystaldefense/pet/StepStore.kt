package com.kocti.crystaldefense.pet

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Хранит шаги между запусками. Вызывается только из главного потока: и экраном, и фоновой задачей. */
class StepStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private val history = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    init {
        migrateSharedLevel()
        migrateHungerScale()
    }

    fun load(): StepState = catchUpHours(readState())

    fun recordCounter(counter: Long): StepState {
        val before = load()
        return save(before.withCounter(counter, today()).also { recordLevels(before, it, today()) })
    }

    fun addSteps(steps: Int): StepState {
        val before = load()
        return save(before.withSteps(steps, today()).also { recordLevels(before, it, today()) })
    }

    /** Обнуляет шаги за сегодня, уровни, показатели всех существ и записи календаря. */
    fun resetProgress(): StepState {
        history.edit().clear().apply()
        return save(load().withProgressReset())
    }

    fun currentDate(): LocalDate = clock().toLocalDate()

    fun stepsOn(date: LocalDate): Int = history.getInt("steps_${date.toEpochDay()}", 0)

    fun levelChangesOn(date: LocalDate): List<LevelChange> =
        Species.entries.mapNotNull { levelChangeOn(it, date.toEpochDay()) }

    private fun levelChangeOn(species: Species, day: Long): LevelChange? =
        LevelChange.decode(species, history.getString("level_${day}_${species.name}", null))

    private fun recordLevels(before: StepState, after: StepState, day: Long) {
        val changes = levelChanges(before, after) { levelChangeOn(it, day) }
        if (changes.isEmpty()) return
        val editor = history.edit()
        changes.forEach { editor.putString("level_${day}_${it.species.name}", it.encode()) }
        editor.apply()
    }

    fun recordBattle(pet: Species, won: Boolean?): StepState = save(load().afterBattle(pet.name, won))

    /** Старое уведомление могут нажать и утром, поэтому время проверяется здесь, а не только на экране. */
    fun putToSleep(): StepState {
        val state = load()
        return if (canPutToSleep(state)) save(state.withAsleep(true)) else state
    }

    fun canPutToSleep(state: StepState): Boolean = state.canPutToSleep(clock().hour)

    /** Инструмент разработчика: перематывает часы приложения на час вперёд, чтобы не ждать суток. */
    fun skipHour(): StepState {
        preferences.edit().putLong(KEY_CLOCK_OFFSET, preferences.getLong(KEY_CLOCK_OFFSET, 0L) + HOUR_MILLIS).apply()
        return load()
    }

    /** Время, которое видит приложение. Для показа при перемотке. */
    fun clockText(): String = clock().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun nowMillis(): Long = System.currentTimeMillis() + preferences.getLong(KEY_CLOCK_OFFSET, 0L)

    private fun clock(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault())

    fun selectedSpecies(): Species = Species.fromName(preferences.getString(KEY_SPECIES, null))

    fun selectSpecies(species: Species) {
        preferences.edit().putString(KEY_SPECIES, species.name).apply()
    }

    /** Открытые кодом инструменты разработчика. Сброс прогресса их не закрывает. */
    fun isDeveloperMode(): Boolean = preferences.getBoolean(KEY_DEVELOPER_MODE, false)

    /** Выключение возвращает настоящее время. */
    fun setDeveloperMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).remove(KEY_CLOCK_OFFSET).apply()
    }

    private fun readState() = StepState(
        epochDay = preferences.getLong(KEY_DAY, NO_DAY),
        todaySteps = preferences.getInt(KEY_TODAY, 0),
        lastCounter = preferences.getLong(KEY_COUNTER, NO_COUNTER),
        activePet = selectedSpecies().name,
        pets = Species.entries.associate { species ->
            val name = species.name
            val defaults = PetVitals()
            name to PetProgress(
                level = preferences.getInt(levelKey(name), 0),
                fedToday = preferences.getInt(fedKey(name), 0),
                vitals = PetVitals(
                    sleep = preferences.getInt("sleep_$name", defaults.sleep),
                    hunger = preferences.getInt("hunger_$name", defaults.hunger),
                    sadness = preferences.getInt("sadness_$name", defaults.sadness),
                    joy = preferences.getInt("joy_$name", defaults.joy),
                ),
                asleep = preferences.getBoolean("asleep_$name", false),
                starvingHours = preferences.getInt("starving_$name", 0),
                lostLevelTonight = preferences.getBoolean("lost_tonight_$name", false),
            )
        },
    ).onDay(today())

    /**
     * Показатели меняются и пока приложение закрыто, поэтому при чтении учитывается каждое начало часа
     * по часам телефона с прошлого чтения: существо просыпается именно в 9:00, а не через час после запуска.
     */
    private fun catchUpHours(state: StepState): StepState {
        val now = nowMillis()
        val last = preferences.getLong(KEY_VITALS_TIME, 0L)
        if (last == 0L || now < last) {
            preferences.edit().putLong(KEY_VITALS_TIME, now).apply()
            return state
        }
        // За две недели отсутствия показатели всё равно упираются в предел, считать дальше незачем.
        val start = maxOf(last, now - MAX_CATCH_UP_HOURS * HOUR_MILLIS)
        var hour = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        var result = state
        var counted = last
        while (hour.toInstant().toEpochMilli() <= now) {
            val previous = result
            result = result.afterHour(hour.hour)
            // Пока приложение было закрыто, могло пройти несколько дней: изменение относится к своему дню.
            recordLevels(previous, result, hour.toLocalDate().toEpochDay())
            counted = hour.toInstant().toEpochMilli()
            hour = hour.plusHours(1)
        }
        if (counted == last) return state
        preferences.edit().putLong(KEY_VITALS_TIME, counted).apply()
        return save(result)
    }

    private fun save(state: StepState): StepState {
        history.edit().putInt("steps_${state.epochDay}", state.todaySteps).apply()
        val editor = preferences.edit()
            .putLong(KEY_DAY, state.epochDay)
            .putInt(KEY_TODAY, state.todaySteps)
            .putLong(KEY_COUNTER, state.lastCounter)
        state.pets.forEach { (pet, progress) ->
            editor.putInt(levelKey(pet), progress.level)
                .putInt(fedKey(pet), progress.fedToday)
                .putInt("sleep_$pet", progress.vitals.sleep)
                .putInt("hunger_$pet", progress.vitals.hunger)
                .putInt("sadness_$pet", progress.vitals.sadness)
                .putInt("joy_$pet", progress.vitals.joy)
                .putBoolean("asleep_$pet", progress.asleep)
                .putInt("starving_$pet", progress.starvingHours)
                .putBoolean("lost_tonight_$pet", progress.lostLevelTonight)
        }
        editor.apply()
        return state
    }

    /** Раньше уровень был общим. Весь накопленный прогресс получает существо, выбранное на момент обновления. */
    private fun migrateSharedLevel() {
        if (!preferences.contains(LEGACY_KEY_LEVEL)) return
        val pet = selectedSpecies().name
        preferences.edit()
            .putInt(levelKey(pet), preferences.getInt(LEGACY_KEY_LEVEL, 0))
            .putInt(fedKey(pet), preferences.getInt(KEY_TODAY, 0))
            .remove(LEGACY_KEY_LEVEL)
            .apply()
    }

    /** Раньше большее значение голода означало более голодное существо, а теперь более сытое. */
    private fun migrateHungerScale() {
        if (preferences.getBoolean(KEY_HUNGER_IS_FOOD, false)) return
        val editor = preferences.edit().putBoolean(KEY_HUNGER_IS_FOOD, true)
        for (species in Species.entries) {
            val key = "hunger_${species.name}"
            if (preferences.contains(key)) editor.putInt(key, 100 - preferences.getInt(key, 0))
        }
        editor.apply()
    }

    private fun levelKey(pet: String) = "level_$pet"

    private fun fedKey(pet: String) = "fed_$pet"

    private fun today(): Long = clock().toLocalDate().toEpochDay()

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_TODAY = "today"
        const val KEY_COUNTER = "counter"
        const val KEY_SPECIES = "species"
        const val KEY_VITALS_TIME = "vitals_time"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_HUNGER_IS_FOOD = "hunger_is_food"
        const val KEY_CLOCK_OFFSET = "clock_offset"
        const val LEGACY_KEY_LEVEL = "level"
        const val HOUR_MILLIS = 3_600_000L
        const val MAX_CATCH_UP_HOURS = 24 * 14
    }
}
