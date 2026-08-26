package com.qrint.studio.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedMultilineTextFieldTest {
    @Test
    fun pathologicalLineRequestsAreClampedToComposeSafeViewport() {
        assertEquals(1..MAX_MULTILINE_VISIBLE_LINES, normalizedEditorTextFieldLines(-20, Int.MAX_VALUE))
    }

    @Test
    fun requestedEditorViewportIsPreservedInsideSafeBounds() {
        assertEquals(2..6, normalizedEditorTextFieldLines(2, 6))
    }

    @Test
    fun maximumCannotFallBelowMinimum() {
        assertEquals(5..5, normalizedEditorTextFieldLines(5, 2))
    }
}
