package com.qrint.studio.audio

import com.qrint.studio.model.PrintSoundPreset
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

object PrintSoundSynth {
    const val SAMPLE_RATE = 22_050

    private enum class Wave { SINE, TRIANGLE, SQUARE, NOISE }
    private data class Note(
        val frequency: Double,
        val durationMs: Int,
        val volume: Double = 0.55,
        val wave: Wave = Wave.SINE,
        val endFrequency: Double = frequency,
        val gapMs: Int = 12,
    )

    val builtIns: List<PrintSoundPreset> = listOf(
        PrintSoundPreset.PAPER_TICK,
        PrintSoundPreset.CLEAN_CHIME,
        PrintSoundPreset.BUBBLE_POP,
        PrintSoundPreset.LASER_PULSE,
        PrintSoundPreset.WOOD_BLOCK,
        PrintSoundPreset.RECEIPT_RUN,
        PrintSoundPreset.SPARKLE,
        PrintSoundPreset.WATER_DROP,
        PrintSoundPreset.SUCCESS_FANFARE,
        PrintSoundPreset.RETRO_BEEP,
        PrintSoundPreset.MECHANICAL,
        PrintSoundPreset.BELL,
    )

    fun synthesize(
        requested: PrintSoundPreset,
        seed: Long = System.nanoTime(),
        sampleRate: Int = SAMPLE_RATE,
    ): ShortArray {
        if (requested == PrintSoundPreset.SILENT) return ShortArray(0)
        val random = Random(seed)
        val preset = if (requested == PrintSoundPreset.RANDOM) builtIns[random.nextInt(builtIns.size)] else requested
        val notes = if (preset == PrintSoundPreset.GENERATIVE) generative(random) else notesFor(preset)
        val totalSamples = notes.sumOf { ((it.durationMs + it.gapMs) * sampleRate / 1000.0).roundToInt() }
            .coerceIn(1, sampleRate * 2)
        val output = ShortArray(totalSamples)
        var cursor = 0
        notes.forEachIndexed { noteIndex, note ->
            val count = (note.durationMs * sampleRate / 1000.0).roundToInt().coerceAtLeast(1)
            val attack = (count * 0.08).roundToInt().coerceAtLeast(1)
            val release = (count * 0.28).roundToInt().coerceAtLeast(1)
            for (index in 0 until count) {
                if (cursor + index >= output.size) break
                val progress = index.toDouble() / count
                val frequency = note.frequency + (note.endFrequency - note.frequency) * progress
                val phase = 2.0 * PI * frequency * index / sampleRate
                val oscillator = when (note.wave) {
                    Wave.SINE -> sin(phase)
                    Wave.TRIANGLE -> 2.0 / PI * kotlin.math.asin(sin(phase))
                    Wave.SQUARE -> if (sin(phase) >= 0.0) 1.0 else -1.0
                    Wave.NOISE -> random.nextDouble() * 2.0 - 1.0
                }
                val envelope = when {
                    index < attack -> index.toDouble() / attack
                    index > count - release -> (count - index).toDouble() / release
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val softened = if (note.wave == Wave.SQUARE) oscillator * 0.62 + sin(phase) * 0.38 else oscillator
                output[cursor + index] = (softened * envelope * note.volume * Short.MAX_VALUE)
                    .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            cursor += count + (note.gapMs * sampleRate / 1000.0).roundToInt()
            if (noteIndex == notes.lastIndex) cursor = output.size
        }
        return output
    }

    private fun generative(random: Random): List<Note> {
        val scale = doubleArrayOf(392.0, 440.0, 493.88, 523.25, 587.33, 659.25, 783.99)
        val count = 3 + random.nextInt(3)
        return List(count) { index ->
            val base = scale[random.nextInt(scale.size)] * if (index == count - 1) 1.25 else 1.0
            Note(
                frequency = base,
                durationMs = 55 + random.nextInt(85),
                volume = 0.34 + random.nextDouble() * 0.25,
                wave = if (random.nextBoolean()) Wave.SINE else Wave.TRIANGLE,
                endFrequency = base * (0.97 + random.nextDouble() * 0.08),
                gapMs = 8 + random.nextInt(18),
            )
        }
    }

    private fun notesFor(preset: PrintSoundPreset): List<Note> = when (preset) {
        PrintSoundPreset.PAPER_TICK -> listOf(Note(1_250.0, 46, 0.32, Wave.NOISE, gapMs = 0))
        PrintSoundPreset.CLEAN_CHIME -> listOf(Note(659.25, 110, 0.48), Note(987.77, 170, 0.42, gapMs = 0))
        PrintSoundPreset.BUBBLE_POP -> listOf(Note(330.0, 150, 0.5, Wave.SINE, 880.0, 0))
        PrintSoundPreset.LASER_PULSE -> listOf(Note(1_600.0, 170, 0.34, Wave.SQUARE, 240.0, 0))
        PrintSoundPreset.WOOD_BLOCK -> listOf(Note(230.0, 68, 0.55, Wave.TRIANGLE, 170.0, 0))
        PrintSoundPreset.RECEIPT_RUN -> listOf(
            Note(150.0, 45, 0.26, Wave.SQUARE, gapMs = 18),
            Note(180.0, 45, 0.28, Wave.SQUARE, gapMs = 18),
            Note(210.0, 45, 0.3, Wave.SQUARE, gapMs = 8),
            Note(920.0, 62, 0.3, Wave.SINE, gapMs = 0),
        )
        PrintSoundPreset.SPARKLE -> listOf(Note(784.0, 70, 0.34), Note(1_046.5, 80, 0.36), Note(1_568.0, 130, 0.3, gapMs = 0))
        PrintSoundPreset.WATER_DROP -> listOf(Note(1_180.0, 190, 0.42, Wave.SINE, 420.0, 0))
        PrintSoundPreset.SUCCESS_FANFARE -> listOf(Note(523.25, 90, 0.4), Note(659.25, 90, 0.4), Note(783.99, 190, 0.44, gapMs = 0))
        PrintSoundPreset.RETRO_BEEP -> listOf(Note(440.0, 75, 0.3, Wave.SQUARE), Note(880.0, 105, 0.28, Wave.SQUARE, gapMs = 0))
        PrintSoundPreset.MECHANICAL -> listOf(
            Note(105.0, 38, 0.42, Wave.NOISE, gapMs = 22),
            Note(160.0, 45, 0.38, Wave.SQUARE, gapMs = 20),
            Note(95.0, 55, 0.4, Wave.NOISE, gapMs = 0),
        )
        PrintSoundPreset.BELL -> listOf(Note(880.0, 330, 0.4, Wave.SINE, 872.0, 0))
        PrintSoundPreset.SILENT, PrintSoundPreset.RANDOM, PrintSoundPreset.GENERATIVE -> emptyList()
    }
}
