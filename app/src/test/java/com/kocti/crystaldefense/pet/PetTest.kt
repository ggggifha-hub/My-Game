package com.kocti.crystaldefense.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StepLedgerTest {
    @Test
    fun firstReadingOnlySetsBaseline() {
        val state = StepState().withCounter(52_000, day = 1)
        assertEquals(0, state.todaySteps)
        assertEquals(52_000L, state.lastCounter)
    }

    @Test
    fun countsDifferenceBetweenReadings() {
        val state = StepState().withCounter(100, 1).withCounter(350, 1)
        assertEquals(250, state.todaySteps)
    }

    @Test
    fun rebootRestartsSensorWithoutLosingNewSteps() {
        val state = StepState().withCounter(5_000, 1).withCounter(5_100, 1).withCounter(40, 1)
        assertEquals(140, state.todaySteps)
    }

    @Test
    fun grows_onceAtTenThousandStepsInOneDay() {
        val almost = StepState().withSteps(9_999, 1)
        assertEquals(0, almost.level)
        assertEquals(1, almost.withSteps(1, 1).level)
    }

    @Test
    fun stepsAreNotCarriedOverBetweenDays() {
        val state = StepState().withSteps(9_000, 1).withSteps(9_000, 2)
        assertEquals(0, state.level)
        assertEquals(9_000, state.todaySteps)
    }

    @Test
    fun everyFullTenThousandInOneDayGrowsAgain() {
        assertEquals(2, StepState().withSteps(20_000, 1).level)
        assertEquals(1, StepState().withSteps(19_999, 1).level)
    }

    @Test
    fun oneLargeSensorJumpCountsAllGrowths() {
        val state = StepState().withCounter(0, 1).withCounter(25_000, 1)
        assertEquals(2, state.level)
        assertEquals(5_000, state.stepsTowardGrowth)
    }

    @Test
    fun levelSurvivesNewDay() {
        val state = StepState().withSteps(10_000, 1).onDay(2)
        assertEquals(1, state.level)
        assertEquals(0, state.todaySteps)
    }

    @Test
    fun newDayResetsCountWhenFirstSensorReadingArrives() {
        val state = StepState().withCounter(100, 1).withCounter(200, 1).withCounter(230, 2)
        assertEquals(30, state.todaySteps)
        assertEquals(2L, state.epochDay)
    }

    @Test
    fun repeatedReadingAddsNothing() {
        val state = StepState().withCounter(100, 1).withCounter(100, 1)
        assertEquals(0, state.todaySteps)
    }
}

class StepHistoryTest {
    private fun state(level: Int) = StepState(pets = mapOf(Species.DRAKO.name to PetProgress(level = level)))

    @Test
    fun monthGridStartsOnMondayAndFillsWholeWeeks() {
        // 1 ноября 2026 года — воскресенье.
        val grid = monthGrid(java.time.YearMonth.of(2026, 11))
        assertEquals(0, grid.size % 7)
        assertTrue(grid.take(6).all { it == null })
        assertEquals(java.time.LocalDate.of(2026, 11, 1), grid[6])
        assertEquals(30, grid.count { it != null })
    }

    @Test
    fun leapFebruaryHasAllItsDays() {
        assertEquals(29, monthGrid(java.time.YearMonth.of(2028, 2)).count { it != null })
    }

    @Test
    fun russianWordFormsFollowTheNumber() {
        for (one in listOf(1, 21, 101, 1001)) assertEquals("$one", 0, russianFormIndex(one))
        for (few in listOf(2, 3, 4, 22, 104)) assertEquals("$few", 1, russianFormIndex(few))
        for (many in listOf(0, 5, 11, 12, 14, 20, 100, 111, 1000, 21000)) assertEquals("$many", 2, russianFormIndex(many))
    }

    @Test
    fun cellTextStaysShort() {
        assertEquals("999", compactSteps(999))
        assertEquals("8.1к", compactSteps(8_123))
        assertEquals("12.3к", compactSteps(12_345))
        assertEquals("120к", compactSteps(120_000))
    }

    @Test
    fun growthAndLossAreRecordedSeparately() {
        val grew = levelChanges(state(2), state(5)) { null }.single()
        assertEquals(LevelChange(Species.DRAKO, 3, 0, 5), grew)
        val shrank = levelChanges(state(5), state(4)) { grew }.single()
        assertEquals(LevelChange(Species.DRAKO, 3, 1, 4), shrank)
    }

    @Test
    fun unchangedLevelLeavesNoRecord() {
        assertTrue(levelChanges(state(2), state(2)) { null }.isEmpty())
    }

    @Test
    fun recordSurvivesSavingAndBadTextIsIgnored() {
        val change = LevelChange(Species.MOROK, 2, 1, 7)
        assertEquals(change, LevelChange.decode(Species.MOROK, change.encode()))
        for (bad in listOf(null, "", "1,2", "a,b,c", "1,2,3,4")) assertEquals(null, LevelChange.decode(Species.MOROK, bad))
    }

    @Test
    fun heightGrowsWithLevelAndMatchesTheForm() {
        // Плесень: 16 см × масштаб формы 0,8.
        assertEquals(13, heightCentimeters(Species.POGANYSH, 0))
        assertTrue(heightCentimeters(Species.DRAKO, 18) > heightCentimeters(Species.DRAKO, 0))
    }
}

class CameraViewTest {
    /** Точка мира в координатах камеры: x вправо, y вверх, а взгляд вдоль -z. */
    private fun inCamera(rotation: FloatArray, x: Float, y: Float, z: Float): List<Float> {
        val m = FloatArray(16)
        phoneViewMatrix(rotation, 1.2f, m)
        return List(3) { row -> m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] }
    }

    private fun assertPoint(expected: List<Float>, actual: List<Float>) {
        for (i in 0..2) assertEquals(expected[i], actual[i], 1e-4f)
    }

    @Test
    fun uprightPhoneLooksStraightAheadFromHandHeight() {
        // Точка в двух метрах на север на высоте телефона.
        assertPoint(listOf(0f, 0f, -2f), inCamera(UPRIGHT_PHONE, 0f, 1.2f, -2f))
        // Пол ниже взгляда на высоту телефона.
        assertPoint(listOf(0f, -1.2f, -2f), inCamera(UPRIGHT_PHONE, 0f, 0f, -2f))
        // Восток справа.
        assertPoint(listOf(1f, 0f, 0f), inCamera(UPRIGHT_PHONE, 1f, 1.2f, 0f))
    }

    @Test
    fun phoneLyingFlatLooksAtTheFloorUnderIt() {
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertPoint(listOf(0f, 0f, -1.2f), inCamera(flat, 0f, 0f, 0f))
    }

    @Test
    fun cropKeepsTheImageProportions() {
        // Кадр 3:4 на вытянутом экране 9:20 обрезается по бокам.
        val (x, y) = centerCropScale(0.75f, 0.45f)
        assertEquals(0.6f, x, 1e-4f)
        assertEquals(1f, y, 0f)
        // Кадр уже экрана обрезается сверху и снизу.
        val (wideX, wideY) = centerCropScale(0.5f, 1f)
        assertEquals(1f, wideX, 0f)
        assertEquals(0.5f, wideY, 1e-4f)
    }
}

class DeveloperCodeTest {
    @Test
    fun onlyTheExactCodeOpensDeveloperMode() {
        assertTrue(isDeveloperCode("13243546"))
        for (wrong in listOf("", "1324354", "132435460", "13243547", "00000000", " 13243546")) {
            assertTrue(wrong, !isDeveloperCode(wrong))
        }
    }
}

class VitalsTest {
    @Test
    fun hungerFallsTenPerHourAndStepsFillIt() {
        val hungry = PetVitals(hunger = 50).afterHour(hour = 12)
        assertEquals(40, hungry.hunger)
        assertEquals(60, hungry.afterWalk(0, 1_000).hunger)
    }

    @Test
    fun firstInstallStartsAtTenFood() {
        assertEquals(10, PetVitals().hunger)
        assertEquals(100, PetVitals().sleep)
    }

    @Test
    fun fiveThousandStepsFillFoodFromZeroToFull() {
        assertEquals(100, PetVitals(hunger = 0).afterWalk(0, 5_000).hunger)
        assertEquals(99, PetVitals(hunger = 0).afterWalk(0, 4_999).hunger)
    }

    @Test
    fun onlyFullPortionsOfStepsCount() {
        val vitals = PetVitals(hunger = 50)
        assertEquals(50, vitals.afterWalk(0, 49).hunger)
        assertEquals(51, vitals.afterWalk(49, 50).hunger)
    }

    /** Прогоняет часы подряд. */
    private fun PetProgress.through(hours: Iterable<Int>) = hours.fold(this) { pet, hour -> pet.afterHour(hour) }

    private val dayHours = 10..21
    private val nightHours = listOf(22, 23, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    @Test
    fun dayTakesExactlyNinetySleep() {
        val evening = PetProgress(vitals = PetVitals(sleep = 100, hunger = 100)).through(dayHours)
        assertEquals(10, evening.vitals.sleep)
    }

    @Test
    fun forgottenCreatureRunsOutOfSleepExactlyAtMidnight() {
        val evening = PetProgress(vitals = PetVitals(sleep = 10, hunger = 100))
        assertTrue(evening.through(listOf(22, 23)).vitals.sleep > 0)
        assertEquals(0, evening.through(listOf(22, 23, 0)).vitals.sleep)
    }

    @Test
    fun forgottenCreatureLosesSixtyFoodOverTheNight() {
        val morning = PetProgress(vitals = PetVitals(sleep = 10, hunger = 90)).through(nightHours)
        assertEquals(30, morning.vitals.hunger)
    }

    @Test
    fun sleepingCreatureWakesAtNineWithFullSleepAndSixtyLessFood() {
        val asleep = PetProgress(vitals = PetVitals(sleep = 10, hunger = 80), asleep = true)
        val almost = asleep.through(nightHours.dropLast(1))
        assertTrue(almost.asleep)
        val morning = almost.afterHour(9)
        assertTrue(!morning.asleep)
        assertEquals(100, morning.vitals.sleep)
        assertEquals(20, morning.vitals.hunger)
    }

    @Test
    fun nightTakesSixtyFoodAndNeverGoesBelowZero() {
        for ((evening, morning) in listOf(100 to 40, 80 to 20, 60 to 0, 30 to 0, 0 to 0)) {
            val pet = PetProgress(vitals = PetVitals(hunger = evening), asleep = true).through(nightHours)
            assertEquals(morning, pet.vitals.hunger)
            assertEquals(100, pet.vitals.sleep)
        }
    }

    @Test
    fun forgottenNightCostsOnlyOneLevel() {
        val evening = PetProgress(level = 5, vitals = PetVitals(sleep = 10, hunger = 0))
        assertEquals(4, evening.through(nightHours).level)
    }

    @Test
    fun nextNightCanCostAnotherLevel() {
        val morning = PetProgress(level = 5, vitals = PetVitals(sleep = 10, hunger = 100)).through(nightHours)
        assertEquals(4, morning.level)
        // День без потерь: голод не успел опуститься до нуля.
        val evening = morning.copy(vitals = morning.vitals.copy(hunger = 100)).through(10..15)
        assertEquals(4, evening.level)
        assertEquals(3, evening.through(nightHours).level)
    }

    @Test
    fun sleepingCreatureLosesNoLevelEvenStarving() {
        val pet = PetProgress(level = 5, vitals = PetVitals(hunger = 0), asleep = true)
        assertEquals(5, pet.through(nightHours).level)
    }

    @Test
    fun starvingDayCostsOneLevelEveryThreeHours() {
        val hungry = PetProgress(level = 5, vitals = PetVitals(hunger = 0))
        assertEquals(5, hungry.through(10..11).level)
        assertEquals(4, hungry.through(10..12).level)
        assertEquals(1, hungry.through(10..21).level)
    }

    @Test
    fun feedingResetsTheStarvingCount() {
        val hungry = PetProgress(level = 5, vitals = PetVitals(hunger = 0)).through(10..11)
        val fed = hungry.copy(vitals = hungry.vitals.copy(hunger = 30)).afterHour(12)
        assertEquals(5, fed.level)
        assertEquals(0, fed.starvingHours)
    }

    @Test
    fun emptySleepAloneCostsNoLevelDuringTheDay() {
        assertEquals(5, PetProgress(level = 5, vitals = PetVitals(sleep = 0, hunger = 100)).through(10..12).level)
    }

    @Test
    fun levelNeverGoesBelowZero() {
        assertEquals(0, PetProgress(level = 0, vitals = PetVitals(hunger = 0)).through(10..21).level)
    }

    @Test
    fun sleepButtonRunsFromNineInTheEveningToNineInTheMorning() {
        val rested = PetProgress(vitals = PetVitals(sleep = 50))
        for (hour in listOf(21, 23, 0, 8)) assertTrue("$hour", rested.canPutToSleep(hour))
        for (hour in listOf(9, 12, 20)) assertTrue("$hour", !rested.canPutToSleep(hour))
    }

    @Test
    fun sleepButtonStaysDuringTheDayWhileSleepIsEmpty() {
        val sleepless = PetProgress(vitals = PetVitals(sleep = 0))
        for (hour in listOf(9, 12, 20)) assertTrue("$hour", sleepless.canPutToSleep(hour))
        // Уже спящего уложить нельзя.
        assertTrue(!sleepless.copy(asleep = true).canPutToSleep(12))
    }

    @Test
    fun daytimeNapLastsUntilSleepIsFullAndThenButtonDisappears() {
        var pet = PetProgress(level = 3, vitals = PetVitals(sleep = 0, hunger = 100), asleep = true)
        pet = pet.through(10..16)
        assertTrue(pet.asleep)
        assertEquals(91, pet.vitals.sleep)
        pet = pet.afterHour(17)
        assertTrue(!pet.asleep)
        assertEquals(100, pet.vitals.sleep)
        assertEquals(3, pet.level)
        assertTrue(!pet.canPutToSleep(17))
    }

    @Test
    fun napReachingBedtimeContinuesUntilMorning() {
        val pet = PetProgress(vitals = PetVitals(sleep = 0, hunger = 100), asleep = true).through(19..21)
        assertTrue(pet.asleep)
        val morning = pet.through(nightHours)
        assertTrue(!morning.asleep)
        assertEquals(100, morning.vitals.sleep)
    }

    @Test
    fun walkingDoesNotTireTheCreature() {
        assertEquals(50, PetVitals(sleep = 50).afterWalk(0, 10_000).sleep)
    }

    @Test
    fun bedtimeIsNextNineInTheEvening() {
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val morning = java.time.ZonedDateTime.of(2026, 11, 2, 9, 30, 0, 0, zone)
        assertEquals(morning.withHour(21).withMinute(0), nextBedtime(morning))
        val evening = morning.withHour(21).withMinute(0)
        assertEquals(evening.plusDays(1), nextBedtime(evening))
    }

    @Test
    fun neglectMakesCreatureSad() {
        val happy = PetVitals(hunger = 90, sleep = 80, sadness = 20, joy = 50).afterHour(hour = 12)
        val neglected = PetVitals(hunger = 10, sleep = 10, sadness = 20, joy = 50).afterHour(hour = 12)
        assertTrue(neglected.sadness > happy.sadness)
        assertTrue(neglected.joy < happy.joy)
    }

    @Test
    fun vitalsStayInRange() {
        var vitals = PetVitals()
        repeat(1000) { vitals = vitals.afterHour(hour = it % 24) }
        for (value in listOf(vitals.sleep, vitals.hunger, vitals.sadness, vitals.joy)) assertTrue("$value", value in 0..100)
        repeat(1000) { vitals = vitals.afterBattle(won = true) }
        for (value in listOf(vitals.sleep, vitals.hunger, vitals.sadness, vitals.joy)) assertTrue("$value", value in 0..100)
    }

    @Test
    fun strengthGrowsWithLevelAndDependsOnCondition() {
        val fine = PetVitals(hunger = 70, sleep = 80, joy = 50)
        assertTrue(strengthOf(Species.MOROK, 5, fine) > strengthOf(Species.MOROK, 1, fine))
        assertTrue(strengthOf(Species.MOROK, 5, fine.copy(hunger = 10)) < strengthOf(Species.MOROK, 5, fine))
        assertTrue(strengthOf(Species.MOROK, 5, fine.copy(sleep = 10)) < strengthOf(Species.MOROK, 5, fine))
        assertTrue(strengthOf(Species.MOROK, 5, fine.copy(joy = 90)) > strengthOf(Species.MOROK, 5, fine))
    }

    @Test
    fun everySpeciesStartsWithItsOwnStrength() {
        val fed = PetVitals(hunger = 100)
        val atStart = Species.entries.map { strengthOf(it, 0, fed) }
        assertEquals(atStart.size, atStart.toSet().size)
        assertEquals(14, strengthOf(Species.DRAKO, 0, fed))
        assertEquals(8, strengthOf(Species.POGANYSH, 0, fed))
        // Сразу после установки существо голодно, но разница между видами остаётся.
        val hungry = Species.entries.map { strengthOf(it, 0, PetVitals()) }
        assertEquals(hungry.size, hungry.toSet().size)
    }

    @Test
    fun levelAddsTheSameStrengthToEverySpecies() {
        for (species in Species.entries) {
            val fed = PetVitals(hunger = 100)
            assertEquals(STRENGTH_PER_LEVEL, strengthOf(species, 1, fed) - strengthOf(species, 0, fed))
        }
    }

    @Test
    fun walkingFeedsOnlyTheChosenCreature() {
        val pets = mapOf("FIRST" to PetProgress(vitals = PetVitals(hunger = 50)), "SECOND" to PetProgress(vitals = PetVitals(hunger = 50)))
        val state = StepState(activePet = "FIRST", pets = pets).withSteps(1_000, 1)
        assertEquals(70, state.progressOf("FIRST").vitals.hunger)
        assertEquals(50, state.progressOf("SECOND").vitals.hunger)
    }

    @Test
    fun hourChangesOnlyTheChosenCreature() {
        val waiting = PetProgress(level = 3, vitals = PetVitals(hunger = 0))
        val first = PetProgress(vitals = PetVitals(hunger = 50))
        val state = StepState(activePet = "FIRST", pets = mapOf("FIRST" to first, "SECOND" to waiting)).afterHour(hour = 12)
        // Выбранное голодает, а остальных нельзя покормить, поэтому они не теряют уровень.
        assertEquals(40, state.progressOf("FIRST").vitals.hunger)
        assertEquals(waiting, state.progressOf("SECOND"))
    }

    @Test
    fun morningWakesCreaturesThatAreNoLongerSelected() {
        val state = StepState(activePet = "FIRST", pets = mapOf("SECOND" to PetProgress(asleep = true)))
        assertTrue(state.afterHour(hour = 8).progressOf("SECOND").asleep)
        assertTrue(!state.afterHour(hour = 9).progressOf("SECOND").asleep)
    }

    @Test
    fun putToSleepAppliesToTheChosenCreature() {
        val state = StepState(activePet = "FIRST").withAsleep(true)
        assertTrue(state.asleep)
        assertTrue(!state.progressOf("SECOND").asleep)
        assertTrue(!state.withAsleep(false).asleep)
    }

    @Test
    fun battleChangesOnlyThatCreaturesMood() {
        val base = StepState().withActivePet("FIRST").withSteps(100, 1).withActivePet("SECOND").withSteps(100, 1)
        val won = base.afterBattle("FIRST", won = true)
        assertTrue(won.progressOf("FIRST").vitals.joy > base.progressOf("FIRST").vitals.joy)
        assertEquals(base.progressOf("SECOND"), won.progressOf("SECOND"))
        val lost = base.afterBattle("FIRST", won = false)
        assertTrue(lost.progressOf("FIRST").vitals.sadness > base.progressOf("FIRST").vitals.sadness)
    }

    @Test
    fun resetRestoresDefaultVitals() {
        val state = StepState().withSteps(30_000, 1).afterBattle(Species.DRAKO.name, won = false).withProgressReset()
        assertEquals(PetVitals(), state.progressOf(Species.DRAKO.name).vitals)
    }
}

class SeparateProgressTest {
    private val first = "FIRST"
    private val second = "SECOND"

    @Test
    fun stepsGrowOnlyTheChosenCreature() {
        val state = StepState().withActivePet(first).withSteps(10_000, 1)
        assertEquals(1, state.levelOf(first))
        assertEquals(0, state.levelOf(second))
    }

    @Test
    fun stepsSplitBetweenCreaturesDoNotAddUp() {
        val state = StepState()
            .withActivePet(first).withSteps(6_000, 1)
            .withActivePet(second).withSteps(6_000, 1)
        assertEquals(0, state.levelOf(first))
        assertEquals(0, state.levelOf(second))
        assertEquals(12_000, state.todaySteps)
    }

    @Test
    fun switchingBackContinuesTodaysProgress() {
        val state = StepState()
            .withActivePet(first).withSteps(6_000, 1)
            .withActivePet(second).withSteps(1_000, 1)
            .withActivePet(first).withSteps(4_000, 1)
        assertEquals(1, state.levelOf(first))
        assertEquals(0, state.levelOf(second))
    }

    @Test
    fun switchingCreatureShowsItsOwnLevelAndProgress() {
        val state = StepState().withActivePet(first).withSteps(13_000, 1).withActivePet(second)
        assertEquals(0, state.level)
        assertEquals(0, state.stepsTowardGrowth)
        assertEquals(1, state.withActivePet(first).level)
        assertEquals(3_000, state.withActivePet(first).stepsTowardGrowth)
    }

    @Test
    fun newDayClearsEveryCreaturesFoodButKeepsLevels() {
        val state = StepState()
            .withActivePet(first).withSteps(12_000, 1)
            .withActivePet(second).withSteps(9_000, 1)
            .withSteps(1_000, 2)
        assertEquals(1, state.levelOf(first))
        assertEquals(0, state.progressOf(first).fedToday)
        assertEquals(1_000, state.progressOf(second).fedToday)
        assertEquals(1_000, state.todaySteps)
    }

    @Test
    fun resetClearsStepsAndLevelsOfEveryCreature() {
        val state = StepState()
            .withActivePet(first).withSteps(12_000, 1)
            .withActivePet(second).withSteps(25_000, 1)
            .withProgressReset()
        assertEquals(0, state.todaySteps)
        assertEquals(0, state.stepsTowardGrowth)
        assertEquals(PetProgress(), state.progressOf(first))
        assertEquals(PetProgress(), state.progressOf(second))
    }

    @Test
    fun resetKeepsStoredEntriesSoZerosAreSaved() {
        // Хранилище записывает только существ из списка: пустой список оставил бы старые уровни на диске.
        val state = StepState().withActivePet(first).withSteps(12_000, 1).withProgressReset()
        assertTrue(first in state.pets)
    }

    @Test
    fun resetDoesNotBringStepsBackOnNextSensorReading() {
        val state = StepState().withCounter(100, 1).withCounter(5_100, 1).withProgressReset().withCounter(5_150, 1)
        assertEquals(50, state.todaySteps)
    }

    @Test
    fun growthAfterResetStartsFromZero() {
        val state = StepState().withSteps(30_000, 1).withProgressReset().withSteps(10_000, 1)
        assertEquals(1, state.level)
    }
}

class SpeciesTest {
    @Test
    fun formChangesAtExpectedLevels() {
        val forms = Species.DRAKO.forms
        FORM_LEVELS.forEachIndexed { index, level ->
            assertEquals(forms[index], Species.DRAKO.formFor(level))
            if (index > 0) assertEquals(forms[index - 1], Species.DRAKO.formFor(level - 1))
        }
        assertEquals(forms.last(), Species.DRAKO.formFor(1000))
    }

    @Test
    fun everySpeciesHasAFormForEachStageWithUniqueNames() {
        assertEquals(listOf(0, 3, 7, 12, 18), FORM_LEVELS)
        for (species in Species.entries) assertEquals(species.title, FORM_LEVELS.size, species.forms.size)
        val names = Species.entries.flatMap { species -> species.forms.map { it.name } }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun creatureKeepsGrowingUntilTheLastForm() {
        val last = FORM_LEVELS.last()
        assertTrue(creatureHeightMeters(last) > creatureHeightMeters(last - 1))
    }

    @Test
    fun everyFormUsesItsOwnModel() {
        val assets = Species.entries.flatMap { species -> species.forms.map { it.model.asset } }
        assertEquals(assets.size, assets.toSet().size)
    }

    @Test
    fun unknownSavedSpeciesFallsBackToDefault() {
        assertEquals(Species.DRAKO, Species.fromName(null))
        // Питомец из старой версии приложения, которого больше нет.
        assertEquals(Species.DRAKO, Species.fromName("TOPOTUN"))
        assertEquals(Species.MOROK, Species.fromName("MOROK"))
    }
}

class CreatureTest {
    private fun Creature.distanceTo(x: Float, z: Float) = hypot(x - this.x, z - this.z)

    @Test
    fun appearsAtTargetOnFirstUpdate() {
        val creature = Creature()
        creature.update(0.016f, 1f, 2f)
        assertEquals(0f, creature.distanceTo(1f, 2f), 1e-4f)
    }

    @Test
    fun walksTowardTargetAndStops() {
        val creature = Creature()
        creature.update(0.016f, 0f, 0f)
        repeat(200) { creature.update(0.016f, 1f, 0f) }
        assertTrue(creature.distanceTo(1f, 0f) <= 0.05f)
        creature.update(0.016f, 1f, 0f)
        assertEquals(0f, creature.speed, 0f)
    }

    @Test
    fun doesNotOvershootOnLongFrame() {
        val creature = Creature()
        creature.update(0.016f, 0f, 0f)
        creature.update(10f, 0.2f, 0f)
        assertTrue(creature.x <= 0.2f)
    }

    @Test
    fun snapsWhenOwnerIsFarAway() {
        val creature = Creature()
        creature.update(0.016f, 0f, 0f)
        creature.update(0.016f, 10f, 0f)
        assertEquals(10f, creature.x, 1e-4f)
    }

    @Test
    fun facesDirectionOfTravel() {
        val creature = Creature()
        creature.update(0.016f, 0f, 0f)
        repeat(100) { creature.update(0.016f, 1f, 0f) }
        assertEquals(90f, creature.yawDegrees, 1f)
    }

    @Test
    fun heightGrowsWithLevelUpToLimit() {
        assertTrue(creatureHeightMeters(1) > creatureHeightMeters(0))
        assertEquals(0.8f, creatureHeightMeters(1000), 1e-4f)
    }
}
