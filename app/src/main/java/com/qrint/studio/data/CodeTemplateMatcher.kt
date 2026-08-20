package com.qrint.studio.data

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.render.DecodedBarcode
import java.util.UUID

data class CodeTemplateMatch(
    val document: LabelDocument,
    val score: Int,
    val reason: String,
)

/** Deterministic local matching; it never sends scanned codes or product data off the device. */
object CodeTemplateMatcher {
    private val codeFields = setOf("条码", "商品条码", "barcode", "code", "编码", "ean", "upc")

    fun match(
        decoded: DecodedBarcode,
        templates: List<LabelDocument>,
        product: ProductRecord?,
        limit: Int = 12,
    ): List<CodeTemplateMatch> {
        val code = decoded.content.trim()
        if (code.isBlank()) return emptyList()
        val productFields = product?.variables()?.keys.orEmpty()
        return templates.asSequence().distinctBy(LabelDocument::id).mapNotNull { document ->
            val exact = document.elements.any { element ->
                element.kind == ElementKind.BARCODE && element.barcodeContent.trim().equals(code, ignoreCase = true)
            }
            val fields = document.variableFields()
            val codeBinding = fields.any { it.lowercase() in codeFields }
            val productBindings = product != null && fields.any { field -> productFields.any { it.equals(field, true) } }
            when {
                exact -> CodeTemplateMatch(document, 100, "模板中已有相同码值")
                productBindings && codeBinding -> CodeTemplateMatch(document, 95, "可同时填充商品资料与条码")
                productBindings -> CodeTemplateMatch(document, 90, "可填充本地商品字段")
                codeBinding -> CodeTemplateMatch(document, 85, "可填充扫描条码")
                else -> null
            }
        }.sortedWith(
            compareByDescending<CodeTemplateMatch>(CodeTemplateMatch::score)
                .thenBy { if (it.document.builtIn) 1 else 0 }
                .thenBy { it.document.title },
        ).take(limit.coerceIn(1, 50)).toList()
    }

    fun apply(
        match: LabelDocument,
        decoded: DecodedBarcode,
        product: ProductRecord?,
    ): LabelDocument {
        val values = buildMap {
            product?.variables()?.let(::putAll)
            codeFields.forEach { key -> put(key, decoded.content) }
        }
        val resolved = match.resolveVariables(values)
        val elements = resolved.elements.mapIndexed { index, element ->
            val original = match.elements.getOrNull(index)
            if (
                element.kind == ElementKind.BARCODE &&
                original != null && hasCodeBinding(original.barcodeContent)
            ) {
                element.copy(
                    barcodeType = decoded.type,
                    barcodeContent = decoded.content,
                    barcodeCaption = !decoded.type.twoDimensional,
                )
            } else element
        }
        return resolved.copy(
            id = UUID.randomUUID().toString(),
            title = product?.name?.takeIf(String::isNotBlank)?.let { "$it · ${match.title}" } ?: match.title,
            elements = elements,
            builtIn = false,
            updatedAt = System.currentTimeMillis(),
        ).normalized()
    }

    private fun hasCodeBinding(value: String): Boolean = value.variableFields()
        .any { it.lowercase() in codeFields }
}
