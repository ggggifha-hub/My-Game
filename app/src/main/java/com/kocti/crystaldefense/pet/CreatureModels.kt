package com.kocti.crystaldefense.pet

/** Готовая модель из файла GLB с названиями её анимаций. */
class ModelSource(
    val asset: String,
    val idleClip: String,
    val walkClip: String,
    val celebrateClip: String,
    val attackClip: String,
    val hitClip: String,
    val deathClip: String = "Death",
)

/** [hover] и [shadow] заданы в долях высоты формы, [scale] выравнивает модели с широкими крыльями. */
class CreatureForm(
    val name: String,
    val model: ModelSource,
    val hover: Float,
    val shadow: Float,
    val scale: Float,
)

/** Уровни, на которых существо принимает следующую форму. */
val FORM_LEVELS = listOf(0, 3, 7, 12, 18)

fun formIndexForLevel(level: Int): Int = FORM_LEVELS.indexOfLast { level >= it }.coerceAtLeast(0)

/** [baseStrength] — сила на нулевом уровне: у каждого вида своя. */
enum class Species(val title: String, val description: String, val baseStrength: Int, val forms: List<CreatureForm>) {
    DRAKO(
        "Драко",
        "Зубастый дракончик. Растёт в дракона и виверну, а затем становится демоном.",
        14,
        listOf(
            flying("Дракончик", "dragon", hover = 0.45f, shadow = 0.4f, scale = 0.8f),
            flying("Дракон", "dragon_evolved", hover = 0.4f, shadow = 0.45f, scale = 0.9f),
            flying("Виверна", "squidle", hover = 0.4f, shadow = 0.45f, scale = 0.9f),
            flying("Крылатый бес", "demon_flying", hover = 0.35f, shadow = 0.45f),
            walking("Демон", "demon", scale = 1.15f),
        ),
    ),
    POGANYSH(
        "Поганыш",
        "Ядовитая плесень. Обрастает шипами, становится грибом и грибным королём.",
        8,
        listOf(
            blob("Плесень", "green_blob", scale = 0.8f),
            blob("Шипастая плесень", "green_spiky_blob", scale = 0.9f),
            blob("Поганыш", "mushnub"),
            blob("Мухомор", "mushnub_evolved"),
            walking("Грибной король", "mushroom_king", scale = 1.15f),
        ),
    ),
    MOROK(
        "Морок",
        "Глаз со щупальцами. Становится тенью, черепом и вихрем, а потом обретает тело.",
        10,
        listOf(
            blob("Глазастик", "alien_blob", scale = 0.85f),
            flying("Морок", "ghost", hover = 0.4f, shadow = 0.35f, scale = 0.9f),
            flying("Костомрак", "ghost_skull", hover = 0.4f, shadow = 0.35f),
            flying("Вихрь", "hywirl", hover = 0.35f, shadow = 0.35f),
            walking("Щупальцеглав", "alien", scale = 1.15f),
        ),
    ),
    GOBLIN(
        "Гоблин",
        "Голова с клыками. Отращивает крылья и корону, становится шаманом, а потом орком.",
        12,
        listOf(
            blob("Клыкастик", "orc_blob", scale = 0.85f),
            flying("Гоблинёнок", "goleling", hover = 0.45f, shadow = 0.35f, scale = 0.8f),
            flying("Король гоблинов", "goleling_evolved", hover = 0.4f, shadow = 0.4f, scale = 0.9f),
            flying("Шаман", "tribal", hover = 0.35f, shadow = 0.4f),
            walking("Орк", "orc", scale = 1.15f),
        ),
    );

    fun formFor(level: Int): CreatureForm = forms[formIndexForLevel(level)]

    companion object {
        fun fromName(name: String?): Species = entries.firstOrNull { it.name == name } ?: DRAKO
    }
}

private const val CELEBRATE_CLIP = "Yes"

private fun assetPath(file: String) = "models/$file.glb"

private fun flying(name: String, file: String, hover: Float, shadow: Float, scale: Float = 1f) = CreatureForm(
    name,
    ModelSource(assetPath(file), "Flying_Idle", "Fast_Flying", CELEBRATE_CLIP, attackClip = "Headbutt", hitClip = "HitReact"),
    hover,
    shadow,
    scale,
)

private fun walking(name: String, file: String, scale: Float = 1f) = CreatureForm(
    name,
    ModelSource(assetPath(file), "Idle", "Walk", CELEBRATE_CLIP, attackClip = "Punch", hitClip = "HitReact"),
    hover = 0f,
    shadow = 0.4f,
    scale = scale,
)

/** Круглые существа без рук: кусаются, а не бьют. */
private fun blob(name: String, file: String, scale: Float = 1f) = CreatureForm(
    name,
    ModelSource(assetPath(file), "Idle", "Walk", CELEBRATE_CLIP, attackClip = "Bite_Front", hitClip = "HitRecieve"),
    hover = 0f,
    shadow = 0.4f,
    scale = scale,
)
