package com.qrint.studio.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.qrint.studio.model.PrintSoundPreset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object PrintSoundEngine {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qrint-print-sound").apply { isDaemon = true }
    }
    private val active = AtomicReference<AudioTrack?>(null)

    fun play(preset: PrintSoundPreset, seed: Long = System.nanoTime()) {
        if (preset == PrintSoundPreset.SILENT) return
        executor.execute {
            val pcm = PrintSoundSynth.synthesize(preset, seed)
            if (pcm.isEmpty()) return@execute
            runCatching {
                active.getAndSet(null)?.let { previous -> runCatching { previous.stop() }; previous.release() }
                @Suppress("DEPRECATION")
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    PrintSoundSynth.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                )
                active.set(track)
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / PrintSoundSynth.SAMPLE_RATE) + 40L)
                if (active.compareAndSet(track, null)) track.release()
            }
        }
    }
}
