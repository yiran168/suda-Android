package com.qrint.studio.data

import com.qrint.studio.model.ElementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCatalogTest {
    @Test fun everySourceAndProvidedDesignBecomesAUniqueTemplate() {
        assertEquals(494, sourceTemplateSpecs.size)
        assertEquals(494, TemplateCatalog.all.size)
        assertEquals(494, TemplateCatalog.all.map { it.id }.toSet().size)
        assertEquals(486, sourceTemplateSpecs.count { it.id.startsWith("source-") })
    }

    @Test fun everyRequiredIndustryIsPresentAndRoutedByOneCatalog() {
        assertEquals(12, IndustryCatalog.categories.size)
        assertEquals(listOf(IndustryCatalog.ALL) + IndustryCatalog.categories.map { it.name }, TemplateCatalog.categories)
        IndustryCatalog.categories.forEach { category ->
            assertTrue("${category.name} should not be empty", TemplateCatalog.inCategory(category.name).isNotEmpty())
            assertTrue(category.description.isNotBlank())
        }
    }

    @Test fun everyDirectorySourceUsesPrintableSizeAndPreservesItsAspectRatio() {
        val source = sourceTemplateSpecs.filter { it.id.startsWith("source-") }
        assertTrue(source.all { it.widthMm in 10f..55f && it.heightMm >= 5f })
        assertTrue(source.any { it.widthMm == 55f })
    }

    @Test fun templatesAreLayeredAndEditableInsteadOfLockedScreenshots() {
        val source = TemplateCatalog.all.filter { it.id.startsWith("source-") }
        source.forEach { summary ->
            val images = summary.document.elements.filter { it.kind == ElementKind.IMAGE }
            assertEquals(summary.id, 1, images.size)
            assertFalse(summary.id, images.single().locked)
        }
        assertTrue(TemplateCatalog.all.sumOf { it.document.elements.count { element -> element.kind == ElementKind.TEXT } } >= 3_000)
        assertTrue(TemplateCatalog.all.sumOf { it.document.elements.count { element -> element.kind == ElementKind.BARCODE } } >= 180)
    }

    @Test fun suppliedExamplesContainTheHumanCheckedEditableContent() {
        fun texts(id: String) = TemplateCatalog.all.first { it.id == id }.document.elements
            .filter { it.kind == ElementKind.TEXT }.joinToString("|") { it.text }
        assertTrue(texts("provided-milk-tea").contains("杨枝甘露"))
        assertTrue(texts("provided-sample-check").contains("610881010200006310"))
        assertTrue(texts("provided-deppon-logistics").contains("德邦物流"))
        assertTrue(texts("provided-cainiao-station").contains("9096"))
        assertTrue(texts("provided-warehouse-box").contains("1888箱"))
        assertTrue(TemplateCatalog.all.first { it.id == "provided-rural-bank-blue" }.document.elements.any {
            it.kind == ElementKind.BARCODE && it.barcodeContent.contains("河南省农村信用社")
        })
    }

    @Test fun allTemplatesPassStructuralQualityGate() {
        val report = TemplateQuality.audit(TemplateCatalog.all)
        assertEquals(
            report.errors.joinToString("\n") { "${it.templateId} ${it.code}: ${it.detail}" },
            emptyList<TemplateQualityIssue>(),
            report.errors,
        )
    }
}
