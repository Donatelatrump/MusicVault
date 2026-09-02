package com.musicvault.app.player

/**
 * Curated equalizer presets.
 *
 * Band levels are stored normalized in [-1..1] and projected onto the actual
 * Android Equalizer range at apply time. Most devices expose ±15 dB
 * (1500 centésimas). The order of [normalizedBands] must match the order the
 * Android effect reports (ascending frequency).
 *
 * Each preset also declares the auxiliary effect intensities it wants active.
 * "Volume boost" presets (e.g. Volume Boost) deliberately leave Loudness at 0
 * so they stack additively with the dedicated loudness slider; the bass/clarity
 * presets lean on EQ bands only.
 */
enum class EqualizerPreset(
    val displayName: String,
    val description: String,
    val normalizedBands: FloatArray,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0
) {
    Flat(
        "Plano",
        "Sin realces ni recortes.",
        floatArrayOf(0f, 0f, 0f, 0f, 0f)
    ),
    BassBoost(
        "Bass Boost",
        "Refuerza graves y sub-bajos.",
        floatArrayOf(0.65f, 0.55f, 0.20f, 0f, 0f),
        bassBoost = 600
    ),
    VolumeBoost(
        "Volume Boost",
        "Más ganancia general sin distorsión.",
        floatArrayOf(0.35f, 0.20f, 0.10f, 0.10f, 0.15f)
    ),
    VocalClarity(
        "Claridad de voz",
        "Atenúa graves y agudos, realza medios para voces y podcasts.",
        floatArrayOf(-0.30f, -0.10f, 0.45f, 0.30f, -0.10f),
        virtualizer = 350
    ),
    Rock(
        "Rock",
        "Medios presentes, graves con punch, agudos cortantes.",
        floatArrayOf(0.45f, 0.25f, -0.10f, 0.30f, 0.40f),
        bassBoost = 250,
        virtualizer = 200
    ),
    Pop(
        "Pop",
        "Ligeramente brillante y cálido.",
        floatArrayOf(0.15f, 0.30f, 0.40f, 0.25f, 0.20f)
    ),
    Jazz(
        "Jazz",
        "Cálido, con medios suaves.",
        floatArrayOf(0.30f, 0.25f, 0.10f, 0.20f, 0.30f),
        virtualizer = 250
    ),
    Electronic(
        "Electrónica",
        "Sub-bajos fuertes, mids recortados, agudos filosos.",
        floatArrayOf(0.70f, 0.35f, -0.20f, 0.10f, 0.55f),
        bassBoost = 500,
        virtualizer = 300
    ),
    Custom(
        "Personalizado",
        "Tus propios ajustes.",
        floatArrayOf(0f, 0f, 0f, 0f, 0f)
    );

    /**
     * Maps the normalized band levels to the Android EQ band range.
     * The clip ceiling of 1.0 protects against presets that want extreme
     * boosts; the band range used here is the common ±15 dB (1500 centésimas).
     */
    fun project(minLevel: Short, maxLevel: Short): ShortArray {
        val ceiling = maxOf(kotlin.math.abs(minLevel.toInt()), kotlin.math.abs(maxLevel.toInt()))
        return ShortArray(normalizedBands.size) { index ->
            val n = normalizedBands.getOrElse(index) { 0f }.coerceIn(-1f, 1f)
            (n * ceiling).toInt().toShort()
        }
    }

    companion object {
        fun fromName(name: String?): EqualizerPreset =
            entries.firstOrNull { it.name == name } ?: Flat
    }
}