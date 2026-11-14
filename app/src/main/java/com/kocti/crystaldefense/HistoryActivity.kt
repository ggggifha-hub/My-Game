package com.kocti.crystaldefense

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kocti.crystaldefense.pet.LevelChange
import com.kocti.crystaldefense.pet.STEPS_PER_GROWTH
import com.kocti.crystaldefense.pet.Species
import com.kocti.crystaldefense.pet.StepStore
import com.kocti.crystaldefense.pet.compactSteps
import com.kocti.crystaldefense.pet.heightCentimeters
import com.kocti.crystaldefense.pet.monthGrid
import com.kocti.crystaldefense.pet.russianFormIndex
import java.time.LocalDate
import java.time.YearMonth

/** Календарь шагов и надписи о том, как менялись существа. */
class HistoryActivity : Activity() {
    private lateinit var store: StepStore
    private lateinit var content: LinearLayout
    private lateinit var today: LocalDate
    private lateinit var month: YearMonth
    private lateinit var selected: LocalDate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        applySystemBarInsets(findViewById(R.id.screenRoot))
        store = StepStore(this)
        content = findViewById(R.id.screenContent)
        // Чтение учитывает прошедшие часы: уровни и шаги в календаре будут свежими.
        store.load()
        selected = store.currentDate()
        month = YearMonth.from(selected)
    }

    override fun onResume() {
        super.onResume()
        store.load()
        today = store.currentDate()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label(getString(R.string.history_title), sizeSp = 24f, bold = true))
        content.addView(monthBar(), matchWidth(dp(8)))
        content.addView(weekHeader(), matchWidth(dp(8)))
        for (week in monthGrid(month).chunked(DAYS_IN_WEEK)) content.addView(weekRow(week), matchWidth(dp(CELL_GAP_DP)))
        content.addView(label(getString(R.string.history_legend, STEPS_PER_GROWTH), sizeSp = 13f), matchWidth(dp(8)))
        content.addView(dayCard(), matchWidth(dp(12)))
        content.addView(monthCard(), matchWidth(dp(12)))
    }

    private fun monthBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            Button(context).apply {
                text = getString(R.string.history_previous)
                contentDescription = getString(R.string.history_previous_month)
                setOnClickListener { showMonth(month.minusMonths(1)) }
            },
        )
        addView(
            label(monthTitle(month), sizeSp = 18f, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Button(context).apply {
                text = getString(R.string.history_next)
                contentDescription = getString(R.string.history_next_month)
                // В будущем шагов быть не может.
                isEnabled = month < YearMonth.from(today)
                setOnClickListener { showMonth(month.plusMonths(1)) }
            },
        )
    }

    private fun showMonth(next: YearMonth) {
        month = next
        selected = if (next == YearMonth.from(today)) today else next.atEndOfMonth()
        render()
    }

    private fun weekHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (name in resources.getStringArray(R.array.week_days)) {
            addView(
                label(name, sizeSp = 13f, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private fun weekRow(week: List<LocalDate?>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (date in week) {
            val cell = if (date == null) View(context) else dayCell(date)
            addView(
                cell,
                LinearLayout.LayoutParams(0, dp(CELL_HEIGHT_DP), 1f).apply {
                    marginStart = dp(CELL_GAP_DP)
                    marginEnd = dp(CELL_GAP_DP)
                },
            )
        }
    }

    private fun dayCell(date: LocalDate): View {
        val steps = store.stepsOn(date)
        val changes = store.levelChangesOn(date)
        val marks = buildString {
            if (changes.any { it.gained > 0 }) append("▲")
            if (changes.any { it.lost > 0 }) append("▼")
        }
        val stepsText = if (steps > 0) compactSteps(steps) else ""
        return TextView(this).apply {
            text = "${date.dayOfMonth}${if (marks.isEmpty()) "" else " $marks"}\n$stepsText"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.screen_text))
            contentDescription = getString(R.string.history_cell, dateTitle(date), steps)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(
                    getColor(
                        when {
                            steps >= STEPS_PER_GROWTH -> R.color.history_goal
                            steps > 0 -> R.color.history_steps
                            else -> R.color.history_empty
                        },
                    ),
                )
                if (date == selected) setStroke(dp(2), getColor(R.color.screen_text))
            }
            // Будущие дни видны, но выбирать в них нечего.
            isEnabled = !date.isAfter(today)
            alpha = if (isEnabled) 1f else FUTURE_ALPHA
            setOnClickListener {
                selected = date
                render()
            }
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.card_normal)
    }

    private fun dayCard() = card().apply {
        addView(label(dateTitle(selected), sizeSp = 18f, bold = true))
        val steps = store.stepsOn(selected)
        addView(label(counted(R.array.history_steps, steps, steps)), matchWidth(dp(4)))
        val changes = store.levelChangesOn(selected)
        if (changes.isEmpty()) {
            addView(label(getString(R.string.history_no_change_day)), matchWidth(dp(4)))
        }
        for (change in changes) {
            for (line in changeLines(change.species, change.gained, change.lost)) addView(label(line), matchWidth(dp(4)))
        }
    }

    private fun monthCard() = card().apply {
        val days = (1..month.lengthOfMonth()).map(month::atDay)
        val total = days.sumOf { store.stepsOn(it).toLong() }
        addView(label(getString(R.string.history_month_title, monthTitle(month)), sizeSp = 18f, bold = true))
        addView(label(getString(R.string.history_month_steps, total)), matchWidth(dp(4)))
        val changes = days.flatMap(store::levelChangesOn)
        for (species in Species.entries) addView(creatureBlock(species, changes.filter { it.species == species }), matchWidth(dp(10)))
    }

    private fun creatureBlock(species: Species, changes: List<LevelChange>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val level = store.load().levelOf(species.name)
        addView(
            label(
                getString(R.string.history_creature, species.title, species.formFor(level).name, level, heightCentimeters(species, level)),
                bold = true,
            ),
        )
        val lines = changeLines(species, changes.sumOf { it.gained }, changes.sumOf { it.lost })
        if (lines.isEmpty()) addView(label(getString(R.string.history_no_change_month), sizeSp = 13f))
        for (line in lines) addView(label(line, sizeSp = 13f))
    }

    private fun changeLines(species: Species, gained: Int, lost: Int): List<String> = buildList {
        if (gained > 0) add(counted(R.array.history_grew, gained, species.title, gained))
        if (lost > 0) add(counted(R.array.history_shrank, lost, species.title, lost))
    }

    private fun counted(forms: Int, count: Int, vararg arguments: Any): String =
        resources.getStringArray(forms)[russianFormIndex(count)].format(*arguments)

    private fun monthTitle(value: YearMonth) =
        "${resources.getStringArray(R.array.month_names)[value.monthValue - 1]} ${value.year}"

    private fun dateTitle(date: LocalDate) =
        "${date.dayOfMonth} ${resources.getStringArray(R.array.month_names_of)[date.monthValue - 1]}"

    private companion object {
        const val DAYS_IN_WEEK = 7
        const val CELL_HEIGHT_DP = 54
        const val CELL_GAP_DP = 2
        const val FUTURE_ALPHA = 0.35f
    }
}
