package com.qrint.studio.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperFeedSheetTest {
    @Test fun feedInputKeepsOneDecimalSeparatorAndDigits() {
        assertEquals("5", sanitizeFeedInput("5mm"))
        assertEquals("2.5", sanitizeFeedInput("2,5"))
        assertEquals("0.5", sanitizeFeedInput(".5"))
        assertEquals("12.34", sanitizeFeedInput("12.3.4"))
    }
}
