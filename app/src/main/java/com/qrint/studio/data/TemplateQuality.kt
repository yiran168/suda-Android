package com.qrint.studio.data

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.TemplateSummary
import kotlin.math.max
import kotlin.math.min

enum class TemplateIssueSeverity { ERROR, WARNING }

data class TemplateQualityIssue(
    val templateId: String,
    val severity: TemplateIssueSeverity,
    val code: String,
    val detail: String,
)

data class TemplateQualityReport(
    val templateCount: Int,
    val issues: List<TemplateQualityIssue>,
) {
    val errors: List<TemplateQualityIssue> get() = issues.filter { it.severity == TemplateIssueSeverity.ERROR }
    val warnings: List<TemplateQualityIssue> get() = issues.filter { it.severity == TemplateIssueSeverity.WARNING }
}

/** Pure structural audit; safe to run in unit tests and at catalog generation time. */
object TemplateQuality {
    private val categories = IndustryCatalog.categories.map { it.name }.toSet()

    fun audit(templates: List<TemplateSummary>): TemplateQualityReport {
        val issues = buildList {
            val duplicateIds = templates.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            duplicateIds.forEach { add(TemplateQualityIssue(it, TemplateIssueSeverity.ERROR, "duplicate-id", "模板 id 重复")) }
            templates.forEach { template -> addAll(audit(template)) }
        }
        return TemplateQualityReport(templates.size, issues)
    }

    fun audit(template: TemplateSummary): List<TemplateQualityIssue> = buildList {
        val document = template.document
        val paper = document.paper
        val start = paper.printableStartX()
        val end = paper.printableEndX()
        val height = paper.fixedHeightDots()
        if (template.category !in categories) {
            add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "category", "未知分类 ${template.category}"))
        }
        if (template.widthMm !in 10f..57f || template.heightMm <= 0f) {
            add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "paper-size", "纸张尺寸 ${template.widthMm}×${template.heightMm} mm"))
        }
        if (document.elements.map { it.id }.distinct().size != document.elements.size) {
            add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "element-id", "元素 id 重复"))
        }
        val decor = document.elements.filter { it.kind == ElementKind.IMAGE }
        if (decor.size > 1) {
            add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "decor-count", "装饰层数量 ${decor.size}"))
        } else if (decor.size == 1) {
            val image = decor.first()
            if (image.locked) {
                add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "decor-locked", "源图层必须可移动、缩放和替换"))
            }
            if (image.x > start || image.right() < end || image.y > 0 || image.bottom() < height) {
                add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "decor-cover", "装饰层没有覆盖完整可打印画布"))
            }
        }
        val editable = document.elements.filterNot { it.locked }
        if (editable.isEmpty()) {
            add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "editable", "没有可编辑层"))
        }
        editable.forEach { element ->
            if (element.x < start || element.right() > end || element.y < 0 || element.bottom() > height) {
                add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "bounds", "元素 ${element.id} 越过纸张边界"))
            }
            val lineShape = element.kind == ElementKind.SHAPE && element.shapeKind in setOf(
                com.qrint.studio.model.ShapeKind.LINE,
                com.qrint.studio.model.ShapeKind.VERTICAL_LINE,
                com.qrint.studio.model.ShapeKind.DASHED_LINE,
                com.qrint.studio.model.ShapeKind.DASHED_VERTICAL_LINE,
            )
            val minimum = if (lineShape) 4 else 16
            if (element.width < minimum || element.height < minimum) {
                add(TemplateQualityIssue(template.id, TemplateIssueSeverity.ERROR, "minimum-size", "元素 ${element.id} 小于可操作尺寸"))
            }
        }
        for (firstIndex in editable.indices) for (secondIndex in firstIndex + 1 until editable.size) {
            val first = editable[firstIndex]
            val second = editable[secondIndex]
            val ratio = overlapAgainstSmaller(first, second)
            if (ratio > 0.86f && first.kind == second.kind) {
                add(
                    TemplateQualityIssue(
                        template.id,
                        TemplateIssueSeverity.WARNING,
                        "severe-overlap",
                        "${first.id} 与 ${second.id} 重叠 ${(ratio * 100).toInt()}%",
                    ),
                )
            }
        }
        if (editable.isNotEmpty()) {
            val layoutLeft = editable.minOf { it.x }
            val layoutRight = editable.maxOf { it.right() }
            val layoutTop = editable.minOf { it.y }
            val layoutBottom = editable.maxOf { it.bottom() }
            val widthFill = (layoutRight - layoutLeft).toFloat() / (end - start).coerceAtLeast(1)
            val heightFill = (layoutBottom - layoutTop).toFloat() / height.coerceAtLeast(1)
            if (editable.size >= 3 && widthFill < 0.46f && heightFill < 0.46f) {
                add(
                    TemplateQualityIssue(
                        template.id,
                        TemplateIssueSeverity.WARNING,
                        "collapsed-layout",
                        "可编辑内容只覆盖画布 ${(widthFill * 100).toInt()}%×${(heightFill * 100).toInt()}%",
                    ),
                )
            }
        }
    }

    private fun overlapAgainstSmaller(first: LabelElement, second: LabelElement): Float {
        val width = max(0, min(first.right(), second.right()) - max(first.x, second.x))
        val height = max(0, min(first.bottom(), second.bottom()) - max(first.y, second.y))
        val smaller = min(first.width * first.height, second.width * second.height).coerceAtLeast(1)
        return width * height / smaller.toFloat()
    }
}
