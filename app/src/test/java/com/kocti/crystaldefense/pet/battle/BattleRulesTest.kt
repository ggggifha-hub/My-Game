package com.kocti.crystaldefense.pet.battle

import com.kocti.crystaldefense.pet.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleRulesTest {
    private fun fighter(species: Species, level: Int = 5, strength: Int = 35) = Fighter(species, level, strength)

    @Test
    fun typeChainMatchesTheGameRules() {
        assertEquals(Species.GOBLIN, Species.DRAKO.beats())
        assertEquals(Species.MOROK, Species.GOBLIN.beats())
        assertEquals(Species.POGANYSH, Species.MOROK.beats())
        assertEquals(Species.DRAKO, Species.POGANYSH.beats())
    }

    @Test
    fun strongerFighterAlwaysWinsWhateverTheTypeAndLevel() {
        for (a in Species.entries) for (b in Species.entries) for (levelA in listOf(0, 6, 18)) for (levelB in listOf(0, 6, 18)) {
            val first = resolveBattle(Fighter(a, levelA, 51), Fighter(b, levelB, 50))
            assertEquals(Winner.FIRST, first.winner)
            assertEquals(WinReason.STRENGTH, first.reason)
            val second = resolveBattle(Fighter(a, levelA, 50), Fighter(b, levelB, 51))
            assertEquals(Winner.SECOND, second.winner)
        }
    }

    @Test
    fun drakoBeatsMoldDespiteTheChain() {
        // Сила 14 против 8: цепочка «Поганыш побеждает Драко» работает только при равной силе.
        val result = resolveBattle(fighter(Species.DRAKO, level = 0, strength = 14), fighter(Species.POGANYSH, level = 0, strength = 8))
        assertEquals(Winner.FIRST, result.winner)
    }

    @Test
    fun chainDecidesWhenStrengthIsEqual() {
        for (species in Species.entries) {
            val result = resolveBattle(fighter(species, level = 0), fighter(species.beats(), level = 18))
            assertEquals(species.title, Winner.FIRST, result.winner)
            assertEquals(WinReason.TYPE, result.reason)
        }
    }

    @Test
    fun creaturesWithoutTypeEdgeFightByStrength() {
        val result = resolveBattle(fighter(Species.DRAKO, strength = 50), fighter(Species.MOROK, strength = 40))
        assertEquals(Winner.FIRST, result.winner)
        assertEquals(WinReason.STRENGTH, result.reason)
    }

    @Test
    fun equalStrengthFallsBackToLevelThenDraw() {
        val byLevel = resolveBattle(fighter(Species.DRAKO, level = 1), fighter(Species.DRAKO, level = 2))
        assertEquals(Winner.SECOND, byLevel.winner)
        assertEquals(WinReason.LEVEL, byLevel.reason)
        val draw = resolveBattle(fighter(Species.MOROK), fighter(Species.MOROK))
        assertEquals(Winner.DRAW, draw.winner)
        assertEquals(WinReason.EQUAL, draw.reason)
    }

    @Test
    fun bothPhonesAlwaysAgreeOnTheWinner() {
        val levels = listOf(0, 3, 6, 18)
        val strengths = listOf(10, 35, 100)
        for (a in Species.entries) for (b in Species.entries) for (la in levels) for (lb in levels) for (sa in strengths) for (sb in strengths) {
            val mine = resolveBattle(Fighter(a, la, sa), Fighter(b, lb, sb))
            val theirs = resolveBattle(Fighter(b, lb, sb), Fighter(a, la, sa))
            val mirrored = when (theirs.winner) {
                Winner.FIRST -> Winner.SECOND
                Winner.SECOND -> Winner.FIRST
                Winner.DRAW -> Winner.DRAW
            }
            assertEquals("$a$la$sa vs $b$lb$sb", mine.winner, mirrored)
            assertEquals(mine.rounds.map { it.firstHealth }, theirs.rounds.map { it.secondHealth })
            val mirroredAttackers = theirs.rounds.map {
                when (it.attacker) {
                    Attacker.FIRST -> Attacker.SECOND
                    Attacker.SECOND -> Attacker.FIRST
                    Attacker.BOTH -> Attacker.BOTH
                }
            }
            assertEquals(mine.rounds.map { it.attacker }, mirroredAttackers)
        }
    }

    @Test
    fun loserEndsAtZeroAndWinnerSurvives() {
        val last = resolveBattle(fighter(Species.DRAKO), fighter(Species.GOBLIN)).rounds.last()
        assertEquals(0, last.secondHealth)
        assertTrue(last.firstHealth > 0)
    }

    @Test
    fun higherLevelMakesTheWinEasier() {
        fun healthLeft(level: Int) = resolveBattle(fighter(Species.DRAKO, level = level), fighter(Species.GOBLIN, level = 5)).rounds.last().firstHealth
        assertTrue(healthLeft(10) > healthLeft(5))
        assertTrue(healthLeft(5) > healthLeft(0))
    }

    @Test
    fun healthOnlyGoesDownDuringTheFight() {
        val rounds = resolveBattle(fighter(Species.MOROK), fighter(Species.POGANYSH)).rounds
        assertTrue(rounds.zipWithNext().all { (a, b) -> b.firstHealth <= a.firstHealth && b.secondHealth <= a.secondHealth })
    }

    @Test
    fun maxHealthGrowsWithLevel() {
        assertEquals(100, maxHealth(0))
        assertEquals(110, maxHealth(1))
        assertEquals(280, maxHealth(18))
    }

    @Test
    fun healthStaysWithinEachFightersOwnMaximum() {
        for (a in Species.entries) for (b in Species.entries) for (level in listOf(0, 6, 18)) {
            for (round in resolveBattle(fighter(a, level = level), fighter(b)).rounds) {
                assertTrue(round.firstHealth in 0..maxHealth(level))
                assertTrue(round.secondHealth in 0..maxHealth(5))
            }
        }
    }

    @Test
    fun healthDoesNotChangeWhoWins() {
        // Победителя решают тип, сила и уровень, поэтому слабый тип не выигрывает за счёт запаса ХП.
        val result = resolveBattle(fighter(Species.DRAKO, level = 0), fighter(Species.GOBLIN, level = 5))
        assertEquals(Winner.FIRST, result.winner)
        assertEquals(0, result.rounds.last().secondHealth)
    }

    @Test
    fun sameHitTakesTheSameShareOfHealthAtAnyLevel() {
        fun firstHit(level: Int) = resolveBattle(fighter(Species.DRAKO, level = level), fighter(Species.GOBLIN, level = level))
            .rounds.first { it.attacker == Attacker.FIRST }.secondHealth
        assertEquals(70, firstHit(0))
        assertEquals(70 * maxHealth(10) / 100, firstHit(10))
    }

    @Test
    fun onlyTheTargetOfAHitLosesHealth() {
        val rounds = resolveBattle(fighter(Species.MOROK), fighter(Species.POGANYSH)).rounds
        var previous = Round(Attacker.BOTH, maxHealth(5), maxHealth(5))
        for (round in rounds) {
            if (round.attacker == Attacker.FIRST) {
                assertEquals(previous.firstHealth, round.firstHealth)
                assertTrue(round.secondHealth < previous.secondHealth)
            } else {
                assertEquals(previous.secondHealth, round.secondHealth)
                assertTrue(round.firstHealth < previous.firstHealth)
            }
            previous = round
        }
    }

    @Test
    fun winnerLandsTheLastHit() {
        for (level in 0..12) {
            val first = resolveBattle(fighter(Species.DRAKO, level = level), fighter(Species.GOBLIN)).rounds.last()
            assertEquals(Attacker.FIRST, first.attacker)
            val second = resolveBattle(fighter(Species.GOBLIN), fighter(Species.DRAKO, level = level)).rounds.last()
            assertEquals(Attacker.SECOND, second.attacker)
        }
    }

    @Test
    fun drawIsAClashWithEqualHealth() {
        val rounds = resolveBattle(fighter(Species.MOROK), fighter(Species.MOROK)).rounds
        assertTrue(rounds.all { it.attacker == Attacker.BOTH && it.firstHealth == it.secondHealth })
        assertTrue(rounds.last().firstHealth > 0)
    }

    @Test
    fun lungePeaksAtImpactAndReturnsHome() {
        assertEquals(0f, lungeAmount(0f), 0f)
        assertEquals(1f, lungeAmount(IMPACT_FRACTION), 1e-4f)
        assertEquals(0f, lungeAmount(1f), 0f)
        assertEquals(0f, lungeAmount(5f), 0f)
    }

    @Test
    fun impactTimeMatchesTheLunge() {
        assertEquals(IMPACT_MILLIS.toFloat(), ATTACK_MILLIS * IMPACT_FRACTION, 1f)
    }

    @Test
    fun higherLevelLooksBiggerInTheArenaWithinLimits() {
        assertTrue(arenaHeight(10, 1f) > arenaHeight(0, 1f))
        assertEquals(arenaHeight(18, 1f), arenaHeight(500, 1f), 0f)
    }

    @Test
    fun messageSurvivesEncoding() {
        val fighter = Fighter(Species.MOROK, 12, 70)
        assertEquals(fighter, Fighter.decode(fighter.encode()))
        assertNotNull(Fighter.decode(fighter.encode()))
    }

    @Test
    fun badMessagesFromOtherPhonesAreRejected() {
        val bad = listOf(
            "",
            "not json",
            "{}",
            """{"version":1,"species":"UNKNOWN","level":1,"strength":1}""",
            """{"version":1,"species":"DRAKO","level":-1,"strength":1}""",
            """{"version":1,"species":"DRAKO","level":1,"strength":-5}""",
            """{"version":1,"species":"DRAKO","level":999999999,"strength":1}""",
            """{"version":99,"species":"DRAKO","level":1,"strength":1}""",
            """{"version":1,"species":"DRAKO","level":"много","strength":1}""",
        )
        for (message in bad) assertNull(message, Fighter.decode(message.toByteArray()))
    }
}
