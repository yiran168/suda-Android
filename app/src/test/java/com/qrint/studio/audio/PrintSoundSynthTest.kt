package com.qrint.studio.audio

import com.qrint.studio.model.PrintSoundPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintSoundSynthTest {
    @Test fun everyBuiltInProducesAudibleBoundedPcm() {
        PrintSoundSynth.builtIns.forEach { preset ->
            val pcm = PrintSoundSynth.synthesize(preset, seed = 42L)
            assertTrue("$preset should produce samples", pcm.isNotEmpty())
            assertTrue("$preset should not exceed two seconds", pcm.size <= PrintSoundSynth.SAMPLE_RATE * 2)
            assertTrue("$preset should contain a non-zero sample", pcm.any { it.toInt() != 0 })
        }
    }

    @Test fun randomIsDeterministicForTestingAndGenerativeChangesWithSeed() {
        assertTrue(PrintSoundSynth.synthesize(PrintSoundPreset.RANDOM, 7L)
            .contentEquals(PrintSoundSynth.synthesize(PrintSoundPreset.RANDOM, 7L)))
        assertTrue(!PrintSoundSynth.synthesize(PrintSoundPreset.GENERATIVE, 7L)
            .contentEquals(PrintSoundSynth.synthesize(PrintSoundPreset.GENERATIVE, 8L)))
        assertEquals(0, PrintSoundSynth.synthesize(PrintSoundPreset.SILENT).size)
    }
}
