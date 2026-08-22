package com.qrint.studio.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.data.RuntimeLogCategory
import com.qrint.studio.data.RuntimeLogStore
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ResizeEdges(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    val active: Boolean get() = left || top || right || bottom
}

data class SnapGuides(
    val verticalDot: Float? = null,
    val horizontalDot: Float? = null,
)

enum class SelectionAlignment {
    LEFT, HORIZONTAL_CENTER, RIGHT, TOP, VERTICAL_CENTER, BOTTOM,
}

class EditorSession(
    initial: LabelDocument,
    private val logs: RuntimeLogStore? = null,
) {
    var document by mutableStateOf(initial.normalized())
        private set
    var selectedId by mutableStateOf(initial.elements.lastOrNull()?.id)
        private set
    var selectedIds by mutableStateOf(initial.elements.lastOrNull()?.let { setOf(it.id) }.orEmpty())
        private set
    var snapGuides by mutableStateOf(SnapGuides())
        private set

    private val undo = ArrayDeque<LabelDocument>()
    private val redo = ArrayDeque<LabelDocument>()
    private var activeTransform: ActiveTransform? = null
    private var lastContentLogAt = 0L
    private var lastContentLogSignature = ""
    private val horizontalSnap = MagneticSnapController()
    private val verticalSnap = MagneticSnapController()
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val selected: LabelElement? get() = document.elements.firstOrNull { it.id == selectedId }
    val selectedElements: List<LabelElement> get() = document.elements.filter { it.id in selectedIds }

    /** Normal tap selects one persistent group; an ungrouped element stays a one-item selection. */
    fun select(id: String?) {
        if (id != selectedId || (id == null && selectedIds.isNotEmpty())) endTransform()
        selectedId = id
        selectedIds = id?.let(::groupSelection).orEmpty()
    }

    /** Long press adds/removes an element (or its persistent group) from the current selection. */
    fun toggleSelection(id: String) {
        endTransform()
        val toggled = groupSelection(id)
        selectedIds = if (toggled.all { it in selectedIds }) selectedIds - toggled else selectedIds + toggled
        selectedId = when {
            id in selectedIds -> id
            selectedIds.isNotEmpty() -> selectedIds.last()
            else -> null
        }
    }

    fun selectAll() {
        endTransform()
        selectedIds = document.elements.mapTo(linkedSetOf()) { it.id }
        selectedId = selectedIds.lastOrNull()
    }

    fun add(element: LabelElement) {
        commit(document.copy(elements = document.elements + element))
        logContentEdit("添加标签元素", listOf(element))
        selectedId = element.id
        selectedIds = setOf(element.id)
    }

    fun addAll(elements: List<LabelElement>) {
        if (elements.isEmpty()) return
        commit(document.copy(elements = document.elements + elements))
        logContentEdit("添加标签元素", elements)
        selectedIds = elements.mapTo(linkedSetOf()) { it.id }
        selectedId = elements.last().id
    }

    fun replaceElements(elements: List<LabelElement>) {
        commit(document.copy(elements = elements))
        logContentEdit("替换标签内容", elements)
        selectedIds = elements.lastOrNull()?.let { setOf(it.id) }.orEmpty()
        selectedId = elements.lastOrNull()?.id
    }

    fun update(element: LabelElement, recordUndo: Boolean = true) {
        val next = document.elements.map { if (it.id == element.id) element else it }
        commit(document.copy(elements = next), recordUndo)
        logContentEdit("编辑标签内容", listOf(element))
    }

    /** Proportional size control shared by image and typography property panels. */
    fun scaleSelectedToWidth(targetWidthDots: Int) {
        val element = selected?.takeIf { selectedIds.size == 1 && !it.locked } ?: return
        if (activeTransform == null) beginTransform(element.id)
        val scaled = ElementSizingPolicy.scaleToWidth(
            element = element,
            targetWidthDots = targetWidthDots,
            contentStart = document.paper.printableStartX(),
            contentEnd = document.paper.printableEndX(),
            heightLimit = heightLimit(),
        )
        replaceById(listOf(scaled), recordUndo = false)
    }

    fun scaleSelectedTypographyToFontSize(targetFontSizeDots: Float) {
        val element = selected?.takeIf { selectedIds.size == 1 && it.kind in TYPOGRAPHY_KINDS && !it.locked } ?: return
        val safeTarget = targetFontSizeDots.coerceIn(8f, 240f)
        val targetWidth = (element.width * safeTarget / element.fontSizeDots.coerceAtLeast(1f)).roundToInt()
        scaleSelectedToWidth(targetWidth)
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        val deleted = selectedElements
        commit(document.copy(elements = document.elements.filterNot { it.id in selectedIds }))
        logContentEdit("删除标签元素", deleted)
        selectedId = document.elements.lastOrNull()?.id
        selectedIds = selectedId?.let(::groupSelection).orEmpty()
    }

    fun duplicateSelected() {
        val originals = selectedElements
        if (originals.isEmpty()) return
        val groupMap = mutableMapOf<String, String>()
        val copies = originals.map { element ->
            element.copy(
                id = UUID.randomUUID().toString(),
                groupId = element.groupId.takeIf { it.isNotBlank() }?.let { groupMap.getOrPut(it) { UUID.randomUUID().toString() } }.orEmpty(),
                x = element.x + 10,
                y = element.y + 10,
                locked = false,
            )
        }
        commit(document.copy(elements = document.elements + copies))
        logContentEdit("复制标签元素", copies)
        selectedIds = copies.mapTo(linkedSetOf()) { it.id }
        selectedId = copies.last().id
    }

    fun groupSelected() {
        if (selectedIds.size < 2) return
        val group = UUID.randomUUID().toString()
        commit(document.copy(elements = document.elements.map { if (it.id in selectedIds) it.copy(groupId = group) else it }))
    }

    fun ungroupSelected() {
        if (selectedIds.isEmpty()) return
        commit(document.copy(elements = document.elements.map { if (it.id in selectedIds) it.copy(groupId = "") else it }))
    }

    fun bringForward() = reorder(1)
    fun sendBackward() = reorder(-1)

    private fun reorder(direction: Int) {
        val ids = selectedIds.ifEmpty { selectedId?.let(::setOf).orEmpty() }
        if (ids.isEmpty()) return
        val list = document.elements.toMutableList()
        if (direction > 0) {
            for (index in list.lastIndex - 1 downTo 0) {
                if (list[index].id in ids && list[index + 1].id !in ids) {
                    val value = list[index]
                    list[index] = list[index + 1]
                    list[index + 1] = value
                }
            }
        } else {
            for (index in 1..list.lastIndex) {
                if (list[index].id in ids && list[index - 1].id !in ids) {
                    val value = list[index]
                    list[index] = list[index - 1]
                    list[index - 1] = value
                }
            }
        }
        commit(document.copy(elements = list))
    }

    fun beginTransform(id: String? = selectedId) {
        if (id != null && id !in selectedIds) select(id)
        val ids = selectedIds.filterTo(linkedSetOf()) { candidate ->
            document.elements.firstOrNull { it.id == candidate }?.locked == false
        }
        if (ids.isEmpty() || activeTransform?.ids == ids) return
        endTransform()
        horizontalSnap.reset()
        verticalSnap.reset()
        activeTransform = ActiveTransform(ids, document)
    }

    fun transformSelected(deltaX: Float, deltaY: Float, zoom: Float) {
        if (activeTransform == null) beginTransform()
        val transform = activeTransform ?: return
        val current = document.elements.filter { it.id in transform.ids }
        if (current.isEmpty()) return
        // A rotated element's printable footprint is its visual (rotated) bounds, not the
        // unrotated x/y/width/height rectangle.  Using the latter lets the rectangle appear
        // inside the paper while its corners are already clipped, which is especially visible
        // when a user drags a rotated frame to an edge.
        val bounds = current.visibleBounds()
        val contentStart = document.paper.printableStartX().toFloat()
        val contentEnd = document.paper.printableEndX().toFloat()
        val heightLimit = heightLimit().toFloat()
        val minimumScale = current.maxOf { max(MIN_ELEMENT_DOTS / it.width.toFloat(), MIN_ELEMENT_DOTS / it.height.toFloat()) }
        val maximumScale = min((contentEnd - contentStart) / bounds.width, heightLimit / bounds.height).coerceAtLeast(minimumScale)
        val scale = zoom.coerceIn(minimumScale, maximumScale)
        val proposedCenterX = bounds.centerX + deltaX
        val proposedCenterY = bounds.centerY + deltaY
        val scaled = current.map { element ->
            val centerX = proposedCenterX + (element.x + element.width / 2f - bounds.centerX) * scale
            val centerY = proposedCenterY + (element.y + element.height / 2f - bounds.centerY) * scale
            val width = (element.width * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS)
            val height = (element.height * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS)
            element.copy(
                x = (centerX - width / 2f).roundToInt(),
                y = (centerY - height / 2f).roundToInt(),
                width = width,
                height = height,
            ).scaleTypography(scale)
        }
        val bounded = shiftIntoPrintablePaper(scaled, contentStart, contentEnd, heightLimit)
        val snap = snapFor(bounded.visibleBounds(), transform.ids, deltaX, deltaY)
        val snapped = bounded.map { it.copy(x = (it.x + snap.dx).roundToInt(), y = (it.y + snap.dy).roundToInt()) }
        // Snapping is allowed to move the group, so apply the same visual-boundary protection
        // once more after the snap correction.
        val moved = shiftIntoPrintablePaper(snapped, contentStart, contentEnd, heightLimit)
        snapGuides = SnapGuides(snap.vertical, snap.horizontal)
        replaceById(moved, recordUndo = false)
    }

    /** Resize one item or the whole multi-selection from any edge while keeping the opposite edge fixed. */
    fun resizeSelected(deltaX: Float, deltaY: Float, edges: ResizeEdges) {
        if (!edges.active) return
        if (activeTransform == null) beginTransform()
        val transform = activeTransform ?: return
        val current = document.elements.filter { it.id in transform.ids }
        if (current.isEmpty()) return
        // Multi-selection handles are drawn around the rotated visual footprint.  Resize that
        // footprint as a group; a single rotated item still uses its local (unrotated) box so
        // dragging a corner remains intuitive in the item's own coordinate system.
        val singleSelection = current.size == 1
        val old = if (singleSelection) current.bounds() else current.visibleBounds()
        val contentStart = document.paper.printableStartX().toFloat()
        val contentEnd = document.paper.printableEndX().toFloat()
        val heightLimit = heightLimit().toFloat()
        val rough = clampResizeBounds(
            candidate = Bounds(
                left = old.left + if (edges.left) deltaX else 0f,
                top = old.top + if (edges.top) deltaY else 0f,
                right = old.right + if (edges.right) deltaX else 0f,
                bottom = old.bottom + if (edges.bottom) deltaY else 0f,
            ),
            edges = edges,
            contentStart = contentStart,
            contentEnd = contentEnd,
            heightLimit = heightLimit,
        )
        val snap = snapFor(
            rough,
            transform.ids,
            deltaX,
            deltaY,
            horizontalEnabled = edges.left || edges.right,
            verticalEnabled = edges.top || edges.bottom,
            horizontalSources = buildList {
                if (edges.left) add(rough.left)
                if (edges.right) add(rough.right)
            },
            verticalSources = buildList {
                if (edges.top) add(rough.top)
                if (edges.bottom) add(rough.bottom)
            },
        )
        val target = clampResizeBounds(
            candidate = rough.copy(
                left = rough.left + if (edges.left) snap.dx else 0f,
                top = rough.top + if (edges.top) snap.dy else 0f,
                right = rough.right + if (edges.right) snap.dx else 0f,
                bottom = rough.bottom + if (edges.bottom) snap.dy else 0f,
            ),
            edges = edges,
            contentStart = contentStart,
            contentEnd = contentEnd,
            heightLimit = heightLimit,
        )
        val typographyScale = resizeTypographyScale(old, target, edges)
        val resized = current.map { element ->
            val relativeLeft = (element.x - old.left) / old.width
            val relativeTop = (element.y - old.top) / old.height
            val relativeRight = (element.right() - old.left) / old.width
            val relativeBottom = (element.bottom() - old.top) / old.height
            val elementLeft = target.left + relativeLeft * target.width
            val elementTop = target.top + relativeTop * target.height
            val elementRight = target.left + relativeRight * target.width
            val elementBottom = target.top + relativeBottom * target.height
            element.copy(
                x = elementLeft.roundToInt(),
                y = elementTop.roundToInt(),
                width = max(MIN_ELEMENT_DOTS, (elementRight - elementLeft).roundToInt()),
                height = max(MIN_ELEMENT_DOTS, (elementBottom - elementTop).roundToInt()),
            ).scaleTypography(typographyScale)
        }
        snapGuides = SnapGuides(snap.vertical, snap.horizontal)
        // The local resize candidate can extend beyond the paper after rotation.  Translate the
        // whole result by the smallest common correction so all four visible edges remain usable
        // and can be placed exactly on the printable boundaries.
        val fitted = fitVisualSelectionToPaper(resized, contentStart, contentEnd, heightLimit)
        replaceById(
            shiftIntoPrintablePaper(fitted, contentStart, contentEnd, heightLimit),
            recordUndo = false,
        )
    }

    fun alignSelected(alignment: SelectionAlignment) {
        val items = selectedElements.filterNot { it.locked }
        if (items.isEmpty()) return
        // Alignment moves the selection as one group relative to the printable paper. Internal
        // spacing and layout remain untouched, which is what users expect after Select all.
        val bounds = items.visibleBounds()
        val paperLeft = document.paper.printableStartX().toFloat()
        val paperRight = document.paper.printableEndX().toFloat()
        val paperBottom = heightLimit().toFloat()
        val dx = when (alignment) {
            SelectionAlignment.LEFT -> paperLeft - bounds.left
            SelectionAlignment.HORIZONTAL_CENTER -> (paperLeft + paperRight) / 2f - bounds.centerX
            SelectionAlignment.RIGHT -> paperRight - bounds.right
            else -> 0f
        }
        val dy = when (alignment) {
            SelectionAlignment.TOP -> -bounds.top
            SelectionAlignment.VERTICAL_CENTER -> paperBottom / 2f - bounds.centerY
            SelectionAlignment.BOTTOM -> paperBottom - bounds.bottom
            else -> 0f
        }
        val updated = items.map { element ->
            element.copy(x = (element.x + dx).roundToInt(), y = (element.y + dy).roundToInt())
        }
        replaceById(updated)
    }

    /**
     * Rotates the whole selection around the centre captured at gesture start.
     * Each element keeps its own size and receives the same delta, so multi-select
     * behaves like one rigid group instead of independently rotating around each
     * element or collapsing into one bounding box.
     */
    fun rotateSelectedBy(deltaDegrees: Float) {
        if (!deltaDegrees.isFinite() || deltaDegrees == 0f) return
        if (activeTransform == null) beginTransform()
        val transform = activeTransform ?: return
        val items = transform.startDocument.elements.filter { it.id in transform.ids }
        if (items.isEmpty()) return
        transform.rotationDegrees += deltaDegrees
        val center = items.visibleBounds()
        val radians = Math.toRadians(transform.rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val rotated = items.map { element ->
            val elementCenterX = element.x + element.width / 2f
            val elementCenterY = element.y + element.height / 2f
            val dx = elementCenterX - center.centerX
            val dy = elementCenterY - center.centerY
            val nextCenterX = center.centerX + dx * cosine - dy * sine
            val nextCenterY = center.centerY + dx * sine + dy * cosine
            element.copy(
                x = (nextCenterX - element.width / 2f).roundToInt(),
                y = (nextCenterY - element.height / 2f).roundToInt(),
                rotation = normalizeDegrees(element.rotation + transform.rotationDegrees),
            )
        }
        val paperLeft = document.paper.printableStartX().toFloat()
        val paperRight = document.paper.printableEndX().toFloat()
        val paperBottom = heightLimit().toFloat()
        replaceById(
            // Use the same visual-boundary policy as drag/resize so rotation cannot leave a
            // different edge-clipping rule behind.
            shiftIntoPrintablePaper(rotated, paperLeft, paperRight, paperBottom),
            recordUndo = false,
        )
    }

    fun distributeSelected(horizontal: Boolean) {
        val items = selectedElements.filterNot { it.locked }
        if (items.size < 3) return
        val sorted = if (horizontal) items.sortedBy { it.x } else items.sortedBy { it.y }
        val first = sorted.first()
        val last = sorted.last()
        val totalSize = if (horizontal) sorted.sumOf { it.width } else sorted.sumOf { it.height }
        val span = if (horizontal) last.right() - first.x else last.bottom() - first.y
        val gap = (span - totalSize).toFloat() / (sorted.size - 1)
        var cursor = if (horizontal) first.x.toFloat() else first.y.toFloat()
        val updated = sorted.map { element ->
            val result = if (horizontal) element.copy(x = cursor.roundToInt()) else element.copy(y = cursor.roundToInt())
            cursor += (if (horizontal) element.width else element.height) + gap
            result
        }
        replaceById(updated)
    }

    fun endTransform() {
        val transform = activeTransform ?: return
        activeTransform = null
        horizontalSnap.reset()
        verticalSnap.reset()
        snapGuides = SnapGuides()
        if (transform.startDocument != document) pushUndo(transform.startDocument)
    }

    fun rename(title: String) { commit(document.copy(title = title.ifBlank { "未命名标签" })) }

    fun setHorizontalAnchor(anchor: HorizontalAnchor) {
        if (document.paper.horizontalAnchor == anchor) return
        endTransform()
        val oldStart = document.paper.printableStartX()
        val paper = document.paper.copy(horizontalAnchor = anchor)
        val shift = paper.printableStartX() - oldStart
        commit(document.copy(paper = paper, elements = document.elements.map { it.copy(x = it.x + shift) }))
    }

    fun setDocument(next: LabelDocument, recordUndo: Boolean = true) = commit(next, recordUndo)

    /** Opens another independent document/page without allowing undo to cross page boundaries. */
    fun openDocument(next: LabelDocument) {
        endTransform()
        undo.clear()
        redo.clear()
        document = next.normalized()
        selectedId = document.elements.lastOrNull()?.id
        selectedIds = selectedId?.let(::groupSelection).orEmpty()
        snapGuides = SnapGuides()
    }

    fun undo() {
        endTransform()
        if (undo.isEmpty()) return
        redo.addLast(document)
        document = undo.removeLast()
        sanitizeSelection()
    }

    fun redo() {
        endTransform()
        if (redo.isEmpty()) return
        undo.addLast(document)
        document = redo.removeLast()
        sanitizeSelection()
    }

    private fun commit(next: LabelDocument, recordUndo: Boolean = true) {
        if (next == document) return
        if (recordUndo) {
            if (activeTransform != null) endTransform()
            pushUndo(document)
        }
        document = next.copy(updatedAt = System.currentTimeMillis()).normalized()
        sanitizeSelection()
    }

    private fun replaceById(updated: List<LabelElement>, recordUndo: Boolean = true) {
        if (updated.isEmpty()) return
        val replacements = updated.associateBy { it.id }
        commit(document.copy(elements = document.elements.map { replacements[it.id] ?: it }), recordUndo)
    }

    private fun pushUndo(snapshot: LabelDocument) {
        if (undo.lastOrNull() == snapshot) return
        undo.addLast(snapshot)
        while (undo.size > 50) undo.removeFirst()
        redo.clear()
    }

    private fun sanitizeSelection() {
        val valid = document.elements.mapTo(hashSetOf()) { it.id }
        selectedIds = selectedIds.filterTo(linkedSetOf()) { it in valid }
        selectedId = selectedId?.takeIf { it in valid } ?: selectedIds.lastOrNull()
    }

    private fun groupSelection(id: String): Set<String> {
        val element = document.elements.firstOrNull { it.id == id } ?: return emptySet()
        return if (element.groupId.isBlank()) setOf(id)
        else document.elements.filterTo(linkedSetOf()) { it.groupId == element.groupId }.mapTo(linkedSetOf()) { it.id }
    }

    private fun heightLimit(): Int = if (document.paper.mode == PaperMode.LABEL) {
        document.paper.fixedHeightDots()
    } else MAX_DOCUMENT_HEIGHT_DOTS

    private fun snapFor(
        bounds: Bounds,
        excludedIds: Set<String>,
        movementX: Float,
        movementY: Float,
        horizontalEnabled: Boolean = true,
        verticalEnabled: Boolean = true,
        horizontalSources: List<Float>? = null,
        verticalSources: List<Float>? = null,
    ): SnapResult {
        val xTargets = mutableListOf(
            document.paper.printableStartX().toFloat(),
            (document.paper.printableStartX() + document.paper.printableEndX()) / 2f,
            document.paper.printableEndX().toFloat(),
        )
        val yTargets = mutableListOf(0f)
        if (document.paper.mode == PaperMode.LABEL) {
            yTargets += document.paper.fixedHeightDots() / 2f
            yTargets += document.paper.fixedHeightDots().toFloat()
        }
        document.elements.filterNot { it.id in excludedIds }.forEach { element ->
            // Snap against what is actually visible on paper, including rotated corners.
            val visible = listOf(element).visibleBounds()
            xTargets += visible.left
            xTargets += visible.centerX
            xTargets += visible.right
            yTargets += visible.top
            yTargets += visible.centerY
            yTargets += visible.bottom
        }
        val xMatch = if (horizontalEnabled) {
            horizontalSnap.apply(horizontalSources ?: listOf(bounds.left, bounds.centerX, bounds.right), xTargets, movementX)
        } else AxisSnapResult(0f, null).also { horizontalSnap.reset() }
        val yMatch = if (verticalEnabled) {
            verticalSnap.apply(verticalSources ?: listOf(bounds.top, bounds.centerY, bounds.bottom), yTargets, movementY)
        } else AxisSnapResult(0f, null).also { verticalSnap.reset() }
        return SnapResult(
            dx = xMatch.correction,
            dy = yMatch.correction,
            vertical = xMatch.guide,
            horizontal = yMatch.guide,
        )
    }

    /** Content logs intentionally contain metadata only, never raw text or image bytes. */
    private fun logContentEdit(event: String, elements: List<LabelElement>) {
        val kindSummary = elements
            .groupingBy { it.kind.name }
            .eachCount()
            .entries
            .joinToString("、") { (kind, count) -> "$kind×$count" }
            .ifBlank { "空画布" }
        val sizeSummary = elements.take(6).joinToString("、") { element ->
            "${element.kind.name} ${element.width}×${element.height}点"
        }
        val remaining = (elements.size - 6).coerceAtLeast(0)
        val sizeSuffix = when {
            sizeSummary.isBlank() -> ""
            remaining > 0 -> " · 尺寸 $sizeSummary 等，另 $remaining 个"
            else -> " · 尺寸 $sizeSummary"
        }
        val variableCount = elements.count { element ->
            element.kind == ElementKind.TEXT && "{{" in element.text && "}}" in element.text
        }
        val variableSuffix = if (variableCount > 0) " · 变量字段元素 $variableCount 个" else ""
        val signature = "$event|$kindSummary|$sizeSummary|$variableCount"
        val now = System.currentTimeMillis()
        if (signature == lastContentLogSignature && now - lastContentLogAt < 700L) return
        lastContentLogSignature = signature
        lastContentLogAt = now
        logs?.info(
            event,
            "$kindSummary$sizeSuffix$variableSuffix（不保存标签原文、图片内容或变量单元格值）",
            RuntimeLogCategory.CONTENT,
        )
    }

    private data class ActiveTransform(
        val ids: Set<String>,
        val startDocument: LabelDocument,
        var rotationDegrees: Float = 0f,
    )
}

private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

private fun resizeTypographyScale(old: Bounds, target: Bounds, edges: ResizeEdges): Float {
    val widthScale = (target.width / old.width).takeIf(Float::isFinite)?.coerceAtLeast(0.01f) ?: 1f
    val heightScale = (target.height / old.height).takeIf(Float::isFinite)?.coerceAtLeast(0.01f) ?: 1f
    val horizontal = edges.left || edges.right
    val vertical = edges.top || edges.bottom
    return when {
        horizontal && vertical -> sqrt(widthScale * heightScale)
        horizontal -> widthScale
        vertical -> heightScale
        else -> 1f
    }
}

private fun clampResizeBounds(
    candidate: Bounds,
    edges: ResizeEdges,
    contentStart: Float,
    contentEnd: Float,
    heightLimit: Float,
): Bounds {
    val horizontal = clampResizeAxis(
        start = candidate.left,
        end = candidate.right,
        limitStart = contentStart,
        limitEnd = contentEnd,
        movesStart = edges.left,
        movesEnd = edges.right,
    )
    val vertical = clampResizeAxis(
        start = candidate.top,
        end = candidate.bottom,
        limitStart = 0f,
        limitEnd = heightLimit,
        movesStart = edges.top,
        movesEnd = edges.bottom,
    )
    return Bounds(horizontal.start, vertical.start, horizontal.end, vertical.end)
}

internal data class ResizeAxis(val start: Float, val end: Float)

/** Clamp without ever constructing an empty `coerceIn` range, even during snap release. */
internal fun clampResizeAxis(
    start: Float,
    end: Float,
    limitStart: Float,
    limitEnd: Float,
    movesStart: Boolean,
    movesEnd: Boolean,
    minimumSize: Float = MIN_ELEMENT_DOTS.toFloat(),
): ResizeAxis {
    val safeLimitStart = limitStart.takeIf(Float::isFinite) ?: 0f
    val safeLimitEnd = (limitEnd.takeIf(Float::isFinite) ?: safeLimitStart)
        .coerceAtLeast(safeLimitStart + 1f)
    val size = minimumSize.takeIf(Float::isFinite)?.coerceIn(1f, safeLimitEnd - safeLimitStart) ?: 1f
    val rawStart = start.takeIf(Float::isFinite) ?: safeLimitStart
    val rawEnd = end.takeIf(Float::isFinite) ?: safeLimitEnd
    return when {
        movesStart && !movesEnd -> {
            val fixedEnd = rawEnd.coerceIn(safeLimitStart + size, safeLimitEnd)
            ResizeAxis(rawStart.coerceIn(safeLimitStart, fixedEnd - size), fixedEnd)
        }
        movesEnd && !movesStart -> {
            val fixedStart = rawStart.coerceIn(safeLimitStart, safeLimitEnd - size)
            ResizeAxis(fixedStart, rawEnd.coerceIn(fixedStart + size, safeLimitEnd))
        }
        else -> {
            val safeStart = rawStart.coerceIn(safeLimitStart, safeLimitEnd - size)
            ResizeAxis(safeStart, rawEnd.coerceIn(safeStart + size, safeLimitEnd))
        }
    }
}

private data class SnapResult(
    val dx: Float,
    val dy: Float,
    val vertical: Float?,
    val horizontal: Float?,
)

private fun List<LabelElement>.bounds(): Bounds = Bounds(
    left = minOf { it.x }.toFloat(),
    top = minOf { it.y }.toFloat(),
    right = maxOf { it.right() }.toFloat(),
    bottom = maxOf { it.bottom() }.toFloat(),
)

private fun List<LabelElement>.visibleBounds(): Bounds {
    val corners = flatMap { element ->
        val centerX = element.x + element.width / 2f
        val centerY = element.y + element.height / 2f
        val radians = Math.toRadians(element.rotation.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        listOf(
            element.x.toFloat() to element.y.toFloat(),
            element.right().toFloat() to element.y.toFloat(),
            element.right().toFloat() to element.bottom().toFloat(),
            element.x.toFloat() to element.bottom().toFloat(),
        ).map { (x, y) ->
            val dx = x - centerX
            val dy = y - centerY
            (centerX + dx * cosine - dy * sine) to (centerY + dx * sine + dy * cosine)
        }
    }
    return Bounds(
        left = corners.minOf { it.first },
        top = corners.minOf { it.second },
        right = corners.maxOf { it.first },
        bottom = corners.maxOf { it.second },
    )
}

/**
 * Moves a selection as one rigid group until its rotated visual footprint fits the printable
 * paper.  A group that is wider/taller than the paper cannot fit in either direction; in that
 * case it is centred so both sides are clipped symmetrically rather than making one edge drift.
 */
private fun shiftIntoPrintablePaper(
    elements: List<LabelElement>,
    contentStart: Float,
    contentEnd: Float,
    heightLimit: Float,
): List<LabelElement> {
    if (elements.isEmpty()) return elements
    val bounds = elements.visibleBounds()
    val availableWidth = (contentEnd - contentStart).coerceAtLeast(1f)
    val shiftX = when {
        bounds.width > availableWidth -> (contentStart + contentEnd) / 2f - bounds.centerX
        bounds.left < contentStart -> contentStart - bounds.left
        bounds.right > contentEnd -> contentEnd - bounds.right
        else -> 0f
    }
    val shiftY = when {
        bounds.height > heightLimit -> heightLimit / 2f - bounds.centerY
        bounds.top < 0f -> -bounds.top
        bounds.bottom > heightLimit -> heightLimit - bounds.bottom
        else -> 0f
    }
    if (abs(shiftX) < 0.01f && abs(shiftY) < 0.01f) return elements
    return elements.map { element ->
        element.copy(
            x = (element.x + shiftX).roundToInt(),
            y = (element.y + shiftY).roundToInt(),
        )
    }
}

/**
 * Uniformly reduces a resized selection when its rotated footprint is larger than the paper.
 * Resize handles operate in local coordinates, so independently clamping width and height is not
 * sufficient at 30/45/90-degree angles: the diagonal footprint can still exceed both limits.
 */
private fun fitVisualSelectionToPaper(
    elements: List<LabelElement>,
    contentStart: Float,
    contentEnd: Float,
    heightLimit: Float,
): List<LabelElement> {
    if (elements.isEmpty()) return elements
    val bounds = elements.visibleBounds()
    val availableWidth = (contentEnd - contentStart).coerceAtLeast(1f)
    val scale = min(
        1f,
        min(availableWidth / bounds.width, heightLimit / bounds.height),
    )
    if (!scale.isFinite() || scale >= 0.9999f) return elements
    val centerX = bounds.centerX
    val centerY = bounds.centerY
    return elements.map { element ->
        val elementCenterX = element.x + element.width / 2f
        val elementCenterY = element.y + element.height / 2f
        val width = (element.width * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS)
        val height = (element.height * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS)
        element.copy(
            x = (centerX + (elementCenterX - centerX) * scale - width / 2f).roundToInt(),
            y = (centerY + (elementCenterY - centerY) * scale - height / 2f).roundToInt(),
            width = width,
            height = height,
        ).scaleTypography(scale)
    }
}

private val TYPOGRAPHY_KINDS = setOf(ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE)

private fun normalizeDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized > 180f) normalized - 360f else if (normalized <= -180f) normalized + 360f else normalized
}
