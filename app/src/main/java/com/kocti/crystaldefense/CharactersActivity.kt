package com.kocti.crystaldefense

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.kocti.crystaldefense.pet.CreatureForm
import com.kocti.crystaldefense.pet.FORM_LEVELS
import com.kocti.crystaldefense.pet.PetProgress
import com.kocti.crystaldefense.pet.Species
import com.kocti.crystaldefense.pet.StepStore
import com.kocti.crystaldefense.pet.battle.beats
import com.kocti.crystaldefense.pet.battle.maxHealth
import com.kocti.crystaldefense.pet.formIndexForLevel
import com.kocti.crystaldefense.pet.glb.ModelThumbnail
import com.kocti.crystaldefense.pet.strengthOf

/** Экран персонажей: состояние каждого существа и выбор питомца. */
class CharactersActivity : Activity() {
    private lateinit var store: StepStore
    private lateinit var list: LinearLayout

    /** Формы, для которых уже запустили отрисовку фона: чтобы не делать её повторно. */
    private val requested = HashSet<String>()
    private var thumbnailWorker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        applySystemBarInsets(findViewById(R.id.screenRoot))
        store = StepStore(this)
        list = findViewById(R.id.screenContent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val state = store.load()
        val selected = store.selectedSpecies()
        list.removeAllViews()
        list.addView(screenTitle(getString(R.string.characters_title)))
        list.addView(label(getString(R.string.characters_rules), color = getColor(R.color.text_muted)), matchWidth(dp(4)))
        val missing = mutableListOf<CreatureForm>()
        for (species in Species.entries) {
            val progress = state.progressOf(species.name)
            val form = species.formFor(progress.level)
            if (ModelThumbnail.cached(form.model) == null && requested.add(form.model.asset)) {
                missing += form
            }
            list.addView(card(species, progress, form, species == selected), matchWidth(dp(12)))
        }
        if (missing.isNotEmpty()) renderThumbnails(missing)
    }

    /** Рисует недостающие модели в фоновом потоке и обновляет список, когда они готовы. */
    private fun renderThumbnails(forms: List<CreatureForm>) {
        if (thumbnailWorker?.isAlive == true) return
        thumbnailWorker = Thread {
            for (form in forms) {
                val bytes = runCatching { assets.open(form.model.asset).use { it.readBytes() } }.getOrNull() ?: continue
                ModelThumbnail.render(form.model, bytes, THUMBNAIL_PX)
            }
            runOnUiThread { if (!isFinishing) refresh() }
        }.apply { start() }
    }

    private fun card(species: Species, progress: PetProgress, form: CreatureForm, selected: Boolean): FrameLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))

            val nextForm = FORM_LEVELS.getOrNull(formIndexForLevel(progress.level) + 1)
            addView(label(species.title, sizeSp = 21f, bold = true))
            addView(label(getString(R.string.character_form, form.name, progress.level), bold = true, color = getColor(R.color.accent)))
            addView(label(species.description, sizeSp = 13f, color = getColor(R.color.text_muted)), matchWidth(dp(2)))
            addView(label(getString(R.string.character_chain, species.forms.joinToString(" → ") { it.name }), sizeSp = 13f, color = getColor(R.color.text_muted)), matchWidth(dp(2)))
            addView(
                label(
                    if (nextForm != null) {
                        getString(R.string.character_next_form, nextForm)
                    } else {
                        getString(R.string.character_final_form)
                    },
                    sizeSp = 13f,
                    color = getColor(R.color.text_muted),
                ),
                matchWidth(dp(2)),
            )
            addView(label(getString(R.string.character_beats, species.beats().title), sizeSp = 13f, color = getColor(R.color.text_muted)), matchWidth(dp(2)))

            val vitals = progress.vitals
            addView(statRow(getString(R.string.stat_health), maxHealth(progress.level), max = MAX_HEALTH_BAR, tint = getColor(R.color.stat_health_c)), matchWidth(dp(8)))
            addView(statRow(getString(R.string.stat_sleep), vitals.sleep, tint = getColor(R.color.stat_sleep_c)), matchWidth())
            addView(statRow(getString(R.string.stat_hunger), vitals.hunger, tint = getColor(R.color.stat_hunger_c)), matchWidth())
            addView(statRow(getString(R.string.stat_sadness), vitals.sadness, tint = getColor(R.color.stat_sadness_c)), matchWidth())
            addView(statRow(getString(R.string.stat_joy), vitals.joy, tint = getColor(R.color.stat_joy_c)), matchWidth())
            addView(statRow(getString(R.string.stat_strength), strengthOf(species, progress.level, progress.vitals), max = MAX_STRENGTH_BAR, tint = getColor(R.color.stat_strength_c)), matchWidth())

            addView(
                Button(context).apply {
                    setText(if (selected) R.string.character_selected else R.string.character_select)
                    isEnabled = !selected
                    setOnClickListener {
                        store.selectSpecies(species)
                        refresh()
                    }
                },
                matchWidth(dp(8)),
            )
        }

        return FrameLayout(this).apply {
            setBackgroundResource(if (selected) R.drawable.card_selected else R.drawable.card_normal)
            // Модель текущей формы — полупрозрачным фоном у правого края, за названием и показателями.
            ModelThumbnail.cached(form.model)?.let { bitmap ->
                addView(
                    ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        alpha = 0.4f
                        setPadding(0, dp(8), dp(4), dp(8))
                    },
                    FrameLayout.LayoutParams(dp(132), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END),
                )
            }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        /** Сила последней формы с запасом: дальше полоса просто заполнена, а число продолжает расти. */
        const val MAX_STRENGTH_BAR = 120

        /** ХП на уровне последней формы: дальше полоса просто заполнена, а число продолжает расти. */
        const val MAX_HEALTH_BAR = 280

        /** Сторона картинки модели в пикселях: с запасом для крупных экранов. */
        const val THUMBNAIL_PX = 320
    }
}
