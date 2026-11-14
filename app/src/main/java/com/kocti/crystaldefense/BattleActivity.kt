package com.kocti.crystaldefense

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.kocti.crystaldefense.pet.PetVitals
import com.kocti.crystaldefense.pet.Species
import com.kocti.crystaldefense.pet.StepStore
import com.kocti.crystaldefense.pet.battle.Attacker
import com.kocti.crystaldefense.pet.battle.BattleArenaRenderer
import com.kocti.crystaldefense.pet.battle.BattleResult
import com.kocti.crystaldefense.pet.battle.Fighter
import com.kocti.crystaldefense.pet.battle.IMPACT_MILLIS
import com.kocti.crystaldefense.pet.battle.NearbyBattle
import com.kocti.crystaldefense.pet.battle.WinReason
import com.kocti.crystaldefense.pet.battle.Winner
import com.kocti.crystaldefense.pet.battle.maxHealth
import com.kocti.crystaldefense.pet.battle.resolveBattle
import com.kocti.crystaldefense.pet.strengthOf

/** Поиск соперника рядом и сам бой. */
class BattleActivity : Activity(), NearbyBattle.Listener {
    private lateinit var store: StepStore
    private lateinit var nearby: NearbyBattle
    private lateinit var status: TextView
    private lateinit var searchButton: Button
    private lateinit var opponents: LinearLayout
    private lateinit var fightScreen: FrameLayout
    private lateinit var fightResult: LinearLayout
    private lateinit var arena: GLSurfaceView
    private lateinit var arenaRenderer: BattleArenaRenderer
    private lateinit var hitFlash: View
    private lateinit var banner: TextView
    private lateinit var myName: TextView
    private lateinit var theirName: TextView
    private lateinit var myHealth: ProgressBar
    private lateinit var theirHealth: ProgressBar
    private lateinit var myHealthText: TextView
    private lateinit var theirHealthText: TextView
    private lateinit var resultText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, String>()
    private var connectedEndpoint: String? = null
    private var searching = false
    private var fightStarted = false
    private var botRound = 0

    /** Бой, который сейчас показывается. Пусто, когда итог уже на экране. */
    private var currentFight: CurrentFight? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battle)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBarInsets(findViewById(R.id.screenRoot))
        applySystemBarInsets(findViewById(R.id.fightOverlay))
        store = StepStore(this)
        nearby = NearbyBattle(this, this)
        buildScreen(findViewById(R.id.screenContent))
        bindFightScreen()
    }

    override fun onResume() {
        super.onResume()
        arena.onResume()
        showMyFighter()
    }

    override fun onPause() {
        super.onPause()
        arena.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Пока экран не виден, игрок не должен оставаться доступным для вызова.
        handler.removeCallbacksAndMessages(null)
        stopNearby()
        // Итог уже сохранён, поэтому после возврата игрок должен увидеть его, а не пустой экран.
        if (currentFight != null) {
            finishFight()
        } else if (fightScreen.visibility != View.VISIBLE) {
            showIdle()
        }
    }

    private fun buildScreen(content: LinearLayout) {
        content.addView(label(getString(R.string.battle_title), sizeSp = 24f, bold = true))
        content.addView(label(getString(R.string.battle_rules)), matchWidth(dp(4)))
        status = label("", bold = true)
        content.addView(status, matchWidth(dp(12)))
        searchButton = Button(this).apply {
            setText(R.string.battle_search)
            setOnClickListener { if (searching) showIdle().also { stopNearby() } else startSearchWithPermissions() }
        }
        content.addView(searchButton, matchWidth(dp(8)))
        opponents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(opponents, matchWidth())

        if (store.isDeveloperMode()) {
            content.addView(
                Button(this).apply {
                    setText(R.string.battle_bot)
                    setOnClickListener { fightBot() }
                },
                matchWidth(dp(8)),
            )
        }
    }

    private fun bindFightScreen() {
        fightScreen = findViewById(R.id.fightScreen)
        fightResult = findViewById(R.id.fightResult)
        hitFlash = findViewById(R.id.hitFlash)
        banner = findViewById(R.id.fightBanner)
        myName = findViewById(R.id.myName)
        theirName = findViewById(R.id.theirName)
        myHealth = findViewById(R.id.myHealth)
        theirHealth = findViewById(R.id.theirHealth)
        myHealthText = findViewById(R.id.myHealthText)
        theirHealthText = findViewById(R.id.theirHealthText)
        resultText = findViewById(R.id.resultText)
        findViewById<Button>(R.id.fightContinue).setOnClickListener { closeFight() }
        arenaRenderer = BattleArenaRenderer(
            loadAsset = { path -> assets.open(path).use { it.readBytes() } },
            onReady = { runOnUiThread(::onArenaReady) },
        )
        arena = findViewById(R.id.arena)
        arena.setPreserveEGLContextOnPause(true)
        arena.setEGLContextClientVersion(2)
        arena.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        arena.setRenderer(arenaRenderer)
        arena.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun myFighter(): Fighter {
        val species = store.selectedSpecies()
        val progress = store.load().progressOf(species.name)
        return Fighter(species, progress.level, strengthOf(species, progress.level, progress.vitals))
    }

    private fun describe(fighter: Fighter) = getString(
        R.string.battle_fighter,
        fighter.species.formFor(fighter.level).name,
        fighter.level,
        fighter.strength,
    )

    private fun showMyFighter() {
        if (!searching && !fightStarted) status.text = getString(R.string.battle_you, describe(myFighter()))
    }

    private fun showIdle() {
        searching = false
        fightStarted = false
        connectedEndpoint = null
        found.clear()
        opponents.removeAllViews()
        searchButton.setText(R.string.battle_search)
        searchButton.isEnabled = true
        showMyFighter()
    }

    private fun stopNearby() {
        nearby.stop()
    }

    private fun startSearchWithPermissions() {
        val missing = nearbyPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSearch() else requestPermissions(missing.toTypedArray(), REQUEST_NEARBY)
    }

    /** Набор разрешений для Nearby зависит от версии Android. */
    private fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NEARBY) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startSearch()
        } else {
            status.setText(R.string.battle_permission_needed)
        }
    }

    private fun startSearch() {
        searching = true
        fightStarted = false
        found.clear()
        opponents.removeAllViews()
        status.setText(R.string.battle_searching)
        searchButton.setText(R.string.battle_stop_search)
        nearby.start(describe(myFighter()))
    }

    override fun onOpponentFound(endpointId: String, name: String) {
        found[endpointId] = name
        renderOpponents()
    }

    override fun onOpponentLost(endpointId: String) {
        found.remove(endpointId)
        renderOpponents()
    }

    private fun renderOpponents() {
        opponents.removeAllViews()
        for ((endpointId, name) in found) {
            opponents.addView(
                Button(this).apply {
                    text = getString(R.string.battle_challenge, name)
                    setOnClickListener {
                        status.text = getString(R.string.battle_waiting, name)
                        opponents.removeAllViews()
                        nearby.challenge(endpointId, describe(myFighter()))
                    }
                },
                matchWidth(dp(4)),
            )
        }
    }

    override fun onChallenged(endpointId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.battle_challenged_title)
            .setMessage(getString(R.string.battle_challenged, name))
            .setCancelable(false)
            .setPositiveButton(R.string.battle_accept) { _, _ -> nearby.accept(endpointId) }
            .setNegativeButton(R.string.battle_decline) { _, _ -> nearby.decline(endpointId) }
            .show()
    }

    override fun onConnected(endpointId: String) {
        connectedEndpoint = endpointId
        status.setText(R.string.battle_connected)
        opponents.removeAllViews()
        searchButton.isEnabled = false
        nearby.send(endpointId, myFighter().encode())
    }

    override fun onMessage(bytes: ByteArray) {
        if (fightStarted) return
        val opponent = Fighter.decode(bytes)
        if (opponent == null) {
            status.setText(R.string.battle_bad_data)
            stopNearby()
            searching = false
            searchButton.isEnabled = true
            searchButton.setText(R.string.battle_search)
            return
        }
        fight(myFighter(), opponent, recordResult = true)
    }

    override fun onConnectionFailed() {
        status.setText(R.string.battle_declined)
        if (searching) renderOpponents()
    }

    override fun onDisconnected() {
        connectedEndpoint = null
        searchButton.isEnabled = true
        searchButton.setText(R.string.battle_search)
        searching = false
        if (!fightStarted) status.setText(R.string.battle_lost_connection)
    }

    override fun onSearchFailed(statusCode: Int) {
        stopNearby()
        showIdle()
        status.text = getString(R.string.battle_search_failed, statusCode)
    }

    /** Бой с ботом только проверяет экран боя на эмуляторе, поэтому не влияет на показатели существа. */
    private fun fightBot() {
        stopNearby()
        val mine = myFighter()
        val opponentSpecies = Species.entries[botRound++ % Species.entries.size]
        val opponent = Fighter(opponentSpecies, mine.level, strengthOf(opponentSpecies, mine.level, PetVitals()))
        fight(mine, opponent, recordResult = false)
    }

    private fun fight(mine: Fighter, opponent: Fighter, recordResult: Boolean) {
        fightStarted = true
        searching = false
        val result = resolveBattle(mine, opponent)
        // Итог сохраняется сразу: закрытие экрана во время показа боя не отменяет поражение.
        if (recordResult) {
            store.recordBattle(
                mine.species,
                when (result.winner) {
                    Winner.FIRST -> true
                    Winner.SECOND -> false
                    Winner.DRAW -> null
                },
            )
        }
        opponents.removeAllViews()
        myName.text = getString(R.string.battle_side_you, describe(mine))
        theirName.text = getString(R.string.battle_side_them, describe(opponent))
        myHealth.max = maxHealth(mine.level)
        theirHealth.max = maxHealth(opponent.level)
        showHealth(myHealth.max, theirHealth.max)
        banner.text = ""
        fightResult.visibility = View.GONE
        currentFight = CurrentFight(result, mine, opponent)
        fightScreen.visibility = View.VISIBLE
        // Скрытый родитель не убирает поверхность с экрана, поэтому она переключается отдельно.
        arena.visibility = View.VISIBLE
        arena.queueEvent { arenaRenderer.start(mine, opponent) }
    }

    /** Модели загружены: можно начинать показ. */
    private fun onArenaReady() {
        if (currentFight == null) return
        showBanner(getString(R.string.battle_go))
        handler.postDelayed({ playRound(0, myHealth.max, theirHealth.max) }, INTRO_MILLIS)
    }

    private fun playRound(index: Int, myBefore: Int, theirBefore: Int) {
        val fight = currentFight ?: return
        val round = fight.result.rounds.getOrNull(index)
        if (round == null) {
            finishFight()
            return
        }
        showBanner(
            getString(
                when (round.attacker) {
                    Attacker.FIRST -> R.string.battle_round_you
                    Attacker.SECOND -> R.string.battle_round_them
                    Attacker.BOTH -> R.string.battle_round_both
                },
                index + 1,
            ),
        )
        arena.queueEvent { arenaRenderer.attack(round.attacker) }
        handler.postDelayed({
            showHealth(round.firstHealth, round.secondHealth)
            showDamage(myBefore - round.firstHealth, NEAR_DAMAGE_X, NEAR_DAMAGE_Y)
            showDamage(theirBefore - round.secondHealth, FAR_DAMAGE_X, FAR_DAMAGE_Y)
            hitFlash.alpha = HIT_FLASH_ALPHA
            hitFlash.animate().alpha(0f).setDuration(HIT_FLASH_MILLIS).start()
        }, IMPACT_MILLIS)
        handler.postDelayed({ playRound(index + 1, round.firstHealth, round.secondHealth) }, ROUND_MILLIS)
    }

    private fun showHealth(mine: Int, theirs: Int) {
        myHealth.setProgress(mine, true)
        theirHealth.setProgress(theirs, true)
        myHealthText.text = getString(R.string.battle_health, mine, myHealth.max)
        theirHealthText.text = getString(R.string.battle_health, theirs, theirHealth.max)
        myHealth.progressTintList = ColorStateList.valueOf(healthColor(mine, myHealth.max))
        theirHealth.progressTintList = ColorStateList.valueOf(healthColor(theirs, theirHealth.max))
    }

    private fun healthColor(health: Int, max: Int) = when {
        health * 2 > max -> HEALTH_GOOD
        health * 4 > max -> HEALTH_LOW
        else -> HEALTH_CRITICAL
    }

    private fun showBanner(text: String) {
        banner.text = text
        banner.scaleX = BANNER_START_SCALE
        banner.scaleY = BANNER_START_SCALE
        banner.animate().scaleX(1f).scaleY(1f).setDuration(BANNER_MILLIS).start()
    }

    /** Число урона всплывает над существом. Положение задано в долях экрана. */
    private fun showDamage(damage: Int, xFraction: Float, yFraction: Float) {
        if (damage <= 0) return
        val number = label(getString(R.string.battle_damage, damage), sizeSp = 30f, bold = true).apply {
            setTextColor(DAMAGE_COLOR)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        fightScreen.addView(number)
        number.x = fightScreen.width * xFraction
        number.y = fightScreen.height * yFraction
        number.animate()
            .translationYBy(-dp(DAMAGE_RISE_DP).toFloat())
            .alpha(0f)
            .setDuration(DAMAGE_MILLIS)
            .withEndAction { fightScreen.removeView(number) }
            .start()
    }

    /** Показ итога. Вызывается и при уходе с экрана посреди боя: тогда полосы сразу встают на конечные значения. */
    private fun finishFight() {
        val fight = currentFight ?: return
        currentFight = null
        val result = fight.result
        result.rounds.lastOrNull()?.let { showHealth(it.firstHealth, it.secondHealth) }
        arena.queueEvent { arenaRenderer.finish(result.winner) }
        val winnerSpecies = if (result.winner == Winner.FIRST) fight.mine.species else fight.opponent.species
        val loserSpecies = if (result.winner == Winner.FIRST) fight.opponent.species else fight.mine.species
        showBanner(
            getString(
                when (result.winner) {
                    Winner.FIRST -> R.string.battle_won
                    Winner.SECOND -> R.string.battle_lost
                    Winner.DRAW -> R.string.battle_draw
                },
            ),
        )
        resultText.text = when (result.reason) {
            WinReason.STRENGTH -> getString(R.string.battle_reason_strength)
            WinReason.TYPE -> getString(R.string.battle_reason_type, winnerSpecies.title, loserSpecies.title)
            WinReason.LEVEL -> getString(R.string.battle_reason_level)
            WinReason.EQUAL -> getString(R.string.battle_reason_equal)
        }
        fightResult.visibility = View.VISIBLE
        stopNearby()
    }

    private fun closeFight() {
        fightScreen.visibility = View.GONE
        arena.visibility = View.GONE
        showIdle()
    }

    private class CurrentFight(val result: BattleResult, val mine: Fighter, val opponent: Fighter)

    private companion object {
        const val REQUEST_NEARBY = 3
        const val INTRO_MILLIS = 1200L
        const val ROUND_MILLIS = 1500L
        const val BANNER_START_SCALE = 1.5f
        const val BANNER_MILLIS = 220L
        const val HIT_FLASH_ALPHA = 0.3f
        const val HIT_FLASH_MILLIS = 180L
        const val DAMAGE_RISE_DP = 70
        const val DAMAGE_MILLIS = 800L
        const val DAMAGE_COLOR = 0xFFFFD54F.toInt()
        const val HEALTH_GOOD = 0xFF4CAF50.toInt()
        const val HEALTH_LOW = 0xFFFFB300.toInt()
        const val HEALTH_CRITICAL = 0xFFE53935.toInt()
        const val NEAR_DAMAGE_X = 0.18f
        const val NEAR_DAMAGE_Y = 0.46f
        const val FAR_DAMAGE_X = 0.62f
        const val FAR_DAMAGE_Y = 0.4f
    }
}
