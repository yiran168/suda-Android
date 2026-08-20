package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserFontStoreTest {
    @Test
    fun recognizesTrueTypeAndOpenTypeSignatures() {
        assertEquals("ttf", UserFontStore.fontExtension(byteArrayOf(0, 1, 0, 0)))
        assertEquals("ttf", UserFontStore.fontExtension("true".toByteArray()))
        assertEquals("otf", UserFontStore.fontExtension("OTTO".toByteArray()))
        assertNull(UserFontStore.fontExtension("woff".toByteArray()))
    }

    @Test
    fun displayNameIsBoundedAndRemovesExtensionAndControlCharacters() {
        assertEquals("示例 字体", UserFontStore.displayNameFrom("folder/示例\n字体.ttf"))
        assertEquals("本地字体", UserFontStore.displayNameFrom(".otf"))
        assertEquals(80, UserFontStore.displayNameFrom("a".repeat(100) + ".ttf").length)
    }
}
