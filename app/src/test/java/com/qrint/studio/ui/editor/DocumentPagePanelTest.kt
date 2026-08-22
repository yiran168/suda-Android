package com.qrint.studio.ui.editor

import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.PaperSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentPagePanelTest {
    private val paper = PaperSettings()

    @Test fun activePageIsReplacedWithoutReorderingOtherPages() {
        val pages = listOf("一", "二", "三").map { EditorFactories.blankDocument(it, paper) }
        val edited = pages[1].copy(title = "第二页已编辑")

        val merged = mergeActiveDocumentPage(pages, 1, edited)

        assertEquals(listOf("一", "第二页已编辑", "三"), merged.map { it.title })
    }

    @Test fun selectedPagesKeepOriginalDocumentOrder() {
        val pages = listOf("一", "二", "三", "四").map { EditorFactories.blankDocument(it, paper) }

        val selected = selectedDocumentPages(pages, setOf(3, 1))

        assertEquals(listOf("二", "四"), selected.map { it.title })
    }
}
