package com.qrint.studio.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagneticSnapControllerTest {
    @Test fun capturesOnlyInsideTheLightMagnetDistance() {
        val snap = MagneticSnapController()
        assertEquals(1.5f, snap.apply(listOf(98.5f), listOf(100f), 0f).correction, 0.001f)

        snap.reset()
        val outside = snap.apply(listOf(97f), listOf(100f), 0f)
        assertEquals(0f, outside.correction, 0.001f)
        assertNull(outside.guide)
    }

    @Test fun slowPointerMovementEscapesWithoutAThrowGesture() {
        val snap = MagneticSnapController(captureDistanceDots = 2f, releaseDistanceDots = 6f)
        snap.apply(listOf(99f), listOf(100f), 0f)
        repeat(5) {
            val held = snap.apply(listOf(101f), listOf(100f), 1f)
            assertEquals(100f, 101f + held.correction, 0.001f)
        }
        val released = snap.apply(listOf(101f), listOf(100f), 1f)
        assertNull(released.guide)
        assertTrue(released.correction >= 5f)
    }
}
