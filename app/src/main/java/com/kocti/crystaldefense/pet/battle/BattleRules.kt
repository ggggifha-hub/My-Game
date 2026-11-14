package com.kocti.crystaldefense.pet.battle

import com.kocti.crystaldefense.pet.Species
import org.json.JSONException
import org.json.JSONObject

/** Кого каждое существо побеждает при равной силе. */
fun Species.beats(): Species = when (this) {
    Species.DRAKO -> Species.GOBLIN
    Species.GOBLIN -> Species.MOROK
    Species.MOROK -> Species.POGANYSH
    Species.POGANYSH -> Species.DRAKO
}

private const val BASE_HEALTH = 100
private const val HEALTH_PER_LEVEL = 10
private const val FULL_PERCENT = 100
private const val MAX_LEVEL_FROM_PEER = 100_000
private const val MAX_STRENGTH_FROM_PEER = 1_000_000
private const val MESSAGE_VERSION = 1
private const val DRAW_ROUNDS = 5
private const val DRAW_HEALTH_LEFT = 15

/** Здоровье проигравшего после каждого удара победителя: последний удар решающий. */
private val LOSER_HEALTH_AFTER_HIT = listOf(70, 40, 0)

/** Порядок ударов, true — бьёт победитель. Он всегда наносит последний удар, а начать может любой. */
private val HIT_ORDERS = listOf(
    listOf(true, false, true, false, true),
    listOf(false, true, false, true, true),
    listOf(false, true, true, false, true),
    listOf(true, false, false, true, true),
)

data class Fighter(val species: Species, val level: Int, val strength: Int) {
    fun encode(): ByteArray = JSONObject()
        .put("version", MESSAGE_VERSION)
        .put("species", species.name)
        .put("level", level)
        .put("strength", strength)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        /** Данные приходят с чужого устройства, поэтому любое неверное сообщение отбрасывается, а не роняет игру. */
        fun decode(bytes: ByteArray): Fighter? = try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val species = Species.entries.firstOrNull { it.name == json.getString("species") }
            val level = json.getInt("level")
            val strength = json.getInt("strength")
            val valid = json.getInt("version") == MESSAGE_VERSION &&
                level in 0..MAX_LEVEL_FROM_PEER &&
                strength in 0..MAX_STRENGTH_FROM_PEER
            if (species != null && valid) Fighter(species, level, strength) else null
        } catch (_: JSONException) {
            null
        }
    }
}

enum class Winner { FIRST, SECOND, DRAW }

enum class WinReason { STRENGTH, TYPE, LEVEL, EQUAL }

/** Кто бьёт в раунде. [BOTH] — столкновение при ничьей: у равных бойцов нет честного способа выбрать первого. */
enum class Attacker { FIRST, SECOND, BOTH }

/** С таким запасом ХП существо начинает бой. */
fun maxHealth(level: Int): Int = BASE_HEALTH + HEALTH_PER_LEVEL * level

/** Кто бил и ХП обоих бойцов после удара, от 0 до [maxHealth] своего уровня. */
data class Round(val attacker: Attacker, val firstHealth: Int, val secondHealth: Int)

data class BattleResult(val winner: Winner, val reason: WinReason, val rounds: List<Round>)

/**
 * Итог определяется только данными бойцов, без случайности: оба телефона считают его независимо
 * и обязаны получить одно и то же.
 */
fun resolveBattle(first: Fighter, second: Fighter): BattleResult {
    val (winner, reason) = decide(first, second)
    val rounds = percentRounds(first, second, winner).map {
        it.copy(
            firstHealth = it.firstHealth * maxHealth(first.level) / FULL_PERCENT,
            secondHealth = it.secondHealth * maxHealth(second.level) / FULL_PERCENT,
        )
    }
    return BattleResult(winner, reason, rounds)
}

/** Побеждает тот, у кого больше сила. Цепочка видов и уровень решают только при равной силе. */
private fun decide(first: Fighter, second: Fighter): Pair<Winner, WinReason> = when {
    first.strength > second.strength -> Winner.FIRST to WinReason.STRENGTH
    second.strength > first.strength -> Winner.SECOND to WinReason.STRENGTH
    first.species.beats() == second.species -> Winner.FIRST to WinReason.TYPE
    second.species.beats() == first.species -> Winner.SECOND to WinReason.TYPE
    first.level > second.level -> Winner.FIRST to WinReason.LEVEL
    second.level > first.level -> Winner.SECOND to WinReason.LEVEL
    else -> Winner.DRAW to WinReason.EQUAL
}

/** Ход боя в процентах здоровья: запас ХП меняет числа, но не победителя. */
private fun percentRounds(first: Fighter, second: Fighter, winner: Winner): List<Round> = when (winner) {
    Winner.DRAW -> List(DRAW_ROUNDS) { i ->
        val health = FULL_PERCENT - (FULL_PERCENT - DRAW_HEALTH_LEFT) * (i + 1) / DRAW_ROUNDS
        Round(Attacker.BOTH, health, health)
    }
    Winner.FIRST -> winnerRounds(first, second, firstWon = true)
    Winner.SECOND -> winnerRounds(second, first, firstWon = false)
}

/** Чем больше перевес победителя в уровнях, тем больше у него остаётся здоровья. */
private fun winnerRounds(winner: Fighter, loser: Fighter, firstWon: Boolean): List<Round> {
    val order = HIT_ORDERS[orderSeed(winner, loser) % HIT_ORDERS.size]
    val winnerEnd = remainingHealth(winner.level - loser.level)
    val loserHits = order.count { !it }
    var winnerHitsDone = 0
    var loserHitsDone = 0
    return order.map { winnerHits ->
        if (winnerHits) winnerHitsDone++ else loserHitsDone++
        val winnerHealth = FULL_PERCENT - (FULL_PERCENT - winnerEnd) * loserHitsDone / loserHits
        val loserHealth = if (winnerHitsDone == 0) FULL_PERCENT else LOSER_HEALTH_AFTER_HIT[winnerHitsDone - 1]
        val attacker = if (winnerHits == firstWon) Attacker.FIRST else Attacker.SECOND
        if (firstWon) Round(attacker, winnerHealth, loserHealth) else Round(attacker, loserHealth, winnerHealth)
    }
}

/** Не зависит от порядка бойцов: иначе телефоны показали бы разный бой. */
private fun orderSeed(a: Fighter, b: Fighter): Int =
    a.level + b.level + a.strength + b.strength + a.species.ordinal + b.species.ordinal

private fun remainingHealth(levelLead: Int): Int = (45 + levelLead.coerceIn(-7, 9) * 5).coerceIn(10, 90)
