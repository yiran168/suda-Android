package com.qrint.studio.ui.editor

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.TextAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSessionSelectionTest {
    private fun document(): LabelDocument = LabelDocument(
        paper = PaperSettings(
            mode = PaperMode.LABEL,
            contentWidthMm = 40f,
            labelHeightMm = 50f,
            horizontalAnchor = HorizontalAnchor.LEFT,
        ),
        elements = listOf(
            LabelElement(id = "a", kind = ElementKind.TEXT, x = 10, y = 10, width = 50, height = 30, text = "A"),
            LabelElement(id = "b", kind = ElementKind.TEXT, x = 100, y = 60, width = 60, height = 30, text = "B"),
            LabelElement(id = "c", kind = ElementKind.TEXT, x = 220, y = 120, width = 50, height = 30, text = "C"),
        ),
    )

    @Test fun longPressSelectionCanBeGroupedAndRestoredFromJson() {
        val session = EditorSession(document())
        session.select("a")
        session.toggleSelection("b")
        assertEquals(setOf("a", "b"), session.selectedIds)
        session.groupSelected()
        val grouped = session.document.elements.filter { it.id in setOf("a", "b") }
        assertTrue(grouped.first().groupId.isNotBlank())
        assertEquals(grouped.first().groupId, grouped.last().groupId)

        session.select("a")
        assertEquals(setOf("a", "b"), session.selectedIds)
        session.ungroupSelected()
        assertTrue(session.document.elements.filter { it.id in setOf("a", "b") }.all { it.groupId.isBlank() })
    }

    @Test fun alignAndDistributeOperateAsOneUndoableEdit() {
        val session = EditorSession(document())
        session.selectAll()
        session.alignSelected(SelectionAlignment.TOP)
        assertEquals(listOf(0, 50, 110), session.document.elements.map { it.y })

        session.undo()
        assertEquals(listOf(10, 60, 120), session.document.elements.map { it.y })
        session.selectAll()
        session.distributeSelected(horizontal = true)
        val values = session.document.elements.sortedBy { it.x }
        val firstGap = values[1].x - values[0].right()
        val secondGap = values[2].x - values[1].right()
        assertTrue(kotlin.math.abs(firstGap - secondGap) <= 1)
    }

    @Test fun groupRotationPreservesLayoutAndIsOneUndoStep() {
        val session = EditorSession(document())
        session.selectAll()
        val before = session.document.elements
        session.beginTransform()
        session.rotateSelectedBy(45f)
        session.endTransform()

        assertTrue(session.document.elements.all { it.rotation == 45f })
        assertNotEquals(before.map { it.x to it.y }, session.document.elements.map { it.x to it.y })
        session.undo()
        assertEquals(before, session.document.elements)
    }

    @Test fun groupRotationUsesOneFixedOverallCentreAndPreservesElementSizes() {
        val source = document().copy(
            elements = listOf(
                LabelElement(id = "left", kind = ElementKind.TEXT, x = 24, y = 30, width = 40, height = 20, rotation = 12f, text = "L"),
                LabelElement(id = "right", kind = ElementKind.IMAGE, x = 180, y = 90, width = 60, height = 30, rotation = -8f),
            ),
        )
        val session = EditorSession(source)
        session.selectAll()
        val originalBounds = source.elements
            .map { it.x + it.width / 2f to it.y + it.height / 2f }
        val visualCorners = source.elements.flatMap { element ->
            val cx = element.x + element.width / 2f
            val cy = element.y + element.height / 2f
            val radians = Math.toRadians(element.rotation.toDouble())
            val c = kotlin.math.cos(radians).toFloat()
            val s = kotlin.math.sin(radians).toFloat()
            listOf(
                element.x.toFloat() to element.y.toFloat(),
                element.right().toFloat() to element.y.toFloat(),
                element.right().toFloat() to element.bottom().toFloat(),
                element.x.toFloat() to element.bottom().toFloat(),
            ).map { (x, y) ->
                (cx + (x - cx) * c - (y - cy) * s) to (cy + (x - cx) * s + (y - cy) * c)
            }
        }
        val groupCenterX = (visualCorners.minOf { it.first } + visualCorners.maxOf { it.first }) / 2f
        val groupCenterY = (visualCorners.minOf { it.second } + visualCorners.maxOf { it.second }) / 2f

        session.beginTransform()
        session.rotateSelectedBy(30f)
        session.rotateSelectedBy(15f)
        session.endTransform()

        val rotated = session.document.elements.associateBy { it.id }
        assertEquals(40, rotated.getValue("left").width)
        assertEquals(30, rotated.getValue("right").height)
        assertEquals(57f, rotated.getValue("left").rotation, 0.01f)
        assertEquals(37f, rotated.getValue("right").rotation, 0.01f)
        // A second incremental callback must equal one 45° rotation from the same centre,
        // not a 30° rotation followed by a new centre calculation.
        val angle = Math.toRadians(45.0)
        val centerX = groupCenterX.toDouble()
        val centerY = groupCenterY.toDouble()
        val sourceX = originalBounds[0].first.toDouble()
        val sourceY = originalBounds[0].second.toDouble()
        val expectedLeftX = centerX + (sourceX - centerX) * kotlin.math.cos(angle) -
            (sourceY - centerY) * kotlin.math.sin(angle)
        val expectedLeftY = centerY + (sourceX - centerX) * kotlin.math.sin(angle) +
            (sourceY - centerY) * kotlin.math.cos(angle)
        val sourceRightX = originalBounds[1].first.toDouble()
        val sourceRightY = originalBounds[1].second.toDouble()
        val expectedRightX = centerX + (sourceRightX - centerX) * kotlin.math.cos(angle) -
            (sourceRightY - centerY) * kotlin.math.sin(angle)
        val expectedRightY = centerY + (sourceRightX - centerX) * kotlin.math.sin(angle) +
            (sourceRightY - centerY) * kotlin.math.cos(angle)
        val actualLeftX = rotated.getValue("left").x + rotated.getValue("left").width / 2.0
        val actualLeftY = rotated.getValue("left").y + rotated.getValue("left").height / 2.0
        val actualRightX = rotated.getValue("right").x + rotated.getValue("right").width / 2.0
        val actualRightY = rotated.getValue("right").y + rotated.getValue("right").height / 2.0
        // Paper-boundary protection may translate the entire group, but it must not change
        // the relative vector produced by the one fixed-centre rotation.
        assertEquals(expectedLeftX - expectedRightX, actualLeftX - actualRightX, 1.5)
        assertEquals(expectedLeftY - expectedRightY, actualLeftY - actualRightY, 1.5)
        assertEquals(actualLeftX - expectedLeftX, actualRightX - expectedRightX, 1.5)
        assertEquals(actualLeftY - expectedLeftY, actualRightY - expectedRightY, 1.5)
        val finalBounds = visualBounds(rotated.values.toList())
        assertTrue(finalBounds.top >= -1.1f)
        assertTrue(finalBounds.bottom <= session.document.paper.fixedHeightDots() + 1.1f)
    }

    @Test fun canvasAlignmentMovesTheWholeVisibleFrameAndDoesNotChangeInternalTextAlignment() {
        val source = document().copy(
            elements = listOf(
                LabelElement(
                    id = "text",
                    kind = ElementKind.TEXT,
                    x = 70,
                    y = 35,
                    width = 80,
                    height = 30,
                    rotation = 18f,
                    text = "框内仍靠右",
                    textAlignment = TextAlignment.RIGHT,
                ),
                LabelElement(
                    id = "image",
                    kind = ElementKind.IMAGE,
                    x = 170,
                    y = 90,
                    width = 70,
                    height = 45,
                    rotation = -12f,
                ),
            ),
        )
        val session = EditorSession(source)
        session.selectAll()
        val originalDelta = session.document.elements[1].x - session.document.elements[0].x

        session.alignSelected(SelectionAlignment.LEFT)
        var bounds = visualBounds(session.document.elements)
        assertEquals(session.document.paper.printableStartX().toFloat(), bounds.left, 1.1f)
        assertEquals(originalDelta, session.document.elements[1].x - session.document.elements[0].x)
        assertEquals(TextAlignment.RIGHT, session.document.elements.first().textAlignment)

        session.alignSelected(SelectionAlignment.HORIZONTAL_CENTER)
        bounds = visualBounds(session.document.elements)
        val paperCenter = (session.document.paper.printableStartX() + session.document.paper.printableEndX()) / 2f
        assertEquals(paperCenter, bounds.centerX, 1.1f)

        session.alignSelected(SelectionAlignment.RIGHT)
        bounds = visualBounds(session.document.elements)
        assertEquals(session.document.paper.printableEndX().toFloat(), bounds.right, 1.1f)
    }

    @Test fun groupDragAndScaleStayInsideTheConfiguredPaper() {
        val session = EditorSession(document())
        session.select("a")
        session.toggleSelection("b")
        val before = session.document.elements.filter { it.id in session.selectedIds }
        session.beginTransform("a")
        session.transformSelected(deltaX = 31f, deltaY = 27f, zoom = 1.2f)
        session.endTransform()
        val after = session.document.elements.filter { it.id in session.selectedIds }
        assertTrue(after.zip(before).all { (next, old) -> next.width > old.width && next.height > old.height })
        assertTrue(after.all { it.x >= session.document.paper.printableStartX() })
        assertTrue(after.all { it.right() <= session.document.paper.printableEndX() })
        assertTrue(after.all { it.y >= 0 && it.bottom() <= session.document.paper.fixedHeightDots() })

        session.undo()
        assertEquals(before, session.document.elements.filter { it.id in setOf("a", "b") })
    }

    @Test fun rotatedFrameCanBeDraggedFlushToEveryPrintableEdge() {
        val source = document().copy(
            elements = listOf(
                LabelElement(
                    id = "rotated",
                    kind = ElementKind.TEXT,
                    x = 120,
                    y = 120,
                    width = 90,
                    height = 42,
                    rotation = 37f,
                    text = "rotated",
                ),
            ),
        )
        val session = EditorSession(source)
        session.select("rotated")

        session.beginTransform("rotated")
        session.transformSelected(deltaX = -10_000f, deltaY = -10_000f, zoom = 1f)
        session.endTransform()
        var bounds = visualBounds(session.document.elements)
        assertEquals(session.document.paper.printableStartX().toFloat(), bounds.left, 1.2f)
        assertEquals(0f, bounds.top, 1.2f)

        session.beginTransform("rotated")
        session.transformSelected(deltaX = 10_000f, deltaY = 10_000f, zoom = 1f)
        session.endTransform()
        bounds = visualBounds(session.document.elements)
        assertEquals(session.document.paper.printableEndX().toFloat(), bounds.right, 1.2f)
        assertEquals(session.document.paper.fixedHeightDots().toFloat(), bounds.bottom, 1.2f)
    }

    @Test fun rotatedResizeKeepsTheVisibleFrameInsideThePaper() {
        val source = document().copy(
            elements = listOf(
                LabelElement(
                    id = "rotated",
                    kind = ElementKind.IMAGE,
                    x = 100,
                    y = 100,
                    width = 80,
                    height = 40,
                    rotation = 45f,
                ),
            ),
        )
        val session = EditorSession(source)
        session.select("rotated")
        session.beginTransform("rotated")
        session.resizeSelected(deltaX = 500f, deltaY = 500f, edges = ResizeEdges(right = true, bottom = true))
        session.endTransform()

        val bounds = visualBounds(session.document.elements)
        assertTrue(bounds.left >= session.document.paper.printableStartX() - 1.2f)
        assertTrue(bounds.top >= -1.2f)
        assertTrue(bounds.right <= session.document.paper.printableEndX() + 1.2f)
        assertTrue(bounds.bottom <= session.document.paper.fixedHeightDots() + 1.2f)
    }

    @Test fun duplicateSelectionGetsNewIdsAndNewPersistentGroup() {
        val session = EditorSession(document())
        session.select("a")
        session.toggleSelection("b")
        session.groupSelected()
        val originalGroup = session.selectedElements.first().groupId
        session.duplicateSelected()
        assertEquals(5, session.document.elements.size)
        assertEquals(2, session.selectedIds.size)
        val copies = session.selectedElements
        assertTrue(copies.all { it.id !in setOf("a", "b") })
        assertEquals(1, copies.map { it.groupId }.distinct().size)
        assertNotEquals(originalGroup, copies.first().groupId)
    }

    @Test fun minimumHeightResizeCanEscapeSnapWithoutAnEmptyRange() {
        val session = EditorSession(
            document().copy(
                elements = listOf(
                    LabelElement(id = "selected", kind = ElementKind.TEXT, x = 10, y = 0, width = 50, height = 16, text = "A"),
                    // Its top edge is the same target as the selected element's minimum bottom.
                    LabelElement(id = "target", kind = ElementKind.TEXT, x = 100, y = 16, width = 50, height = 16, text = "B"),
                ),
            ),
        )
        session.select("selected")
        session.beginTransform("selected")

        repeat(8) { session.resizeSelected(0f, -1f, ResizeEdges(bottom = true)) }
        session.endTransform()

        val resized = session.document.elements.first { it.id == "selected" }
        assertTrue(resized.height >= 16)
        assertTrue(resized.y >= 0)
    }

    @Test fun resizeAxisRepairsNonFiniteAndCrossedBounds() {
        val vertical = clampResizeAxis(
            start = Float.NaN,
            end = -3.5263157f,
            limitStart = 0f,
            limitEnd = 400f,
            movesStart = false,
            movesEnd = true,
        )

        assertEquals(0f, vertical.start, 0.001f)
        assertTrue(vertical.end - vertical.start >= 16f)
    }

    @Test fun edgeResizeScalesTextDateAndSequenceTypographyWithTheirBounds() {
        listOf(ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE).forEach { kind ->
            val element = LabelElement(
                id = kind.name,
                kind = kind,
                x = 10,
                y = 10,
                width = 80,
                height = 40,
                fontSizeDots = 24f,
                lineSpacingDots = 4f,
            )
            val session = EditorSession(document().copy(elements = listOf(element)))
            session.select(element.id)
            session.beginTransform(element.id)
            session.resizeSelected(40f, 0f, ResizeEdges(right = true))
            session.endTransform()

            val resized = session.document.elements.single()
            assertEquals(120, resized.width)
            assertEquals(36f, resized.fontSizeDots, 0.01f)
            assertEquals(6f, resized.lineSpacingDots, 0.01f)
        }
    }

    @Test fun pinchScaleChangesTextSizeButPlainMovementDoesNot() {
        val session = EditorSession(document().copy(elements = listOf(document().elements.first())))
        val original = session.document.elements.single()
        session.select(original.id)
        session.beginTransform(original.id)
        session.transformSelected(deltaX = 5f, deltaY = 5f, zoom = 1f)
        assertEquals(original.fontSizeDots, session.document.elements.single().fontSizeDots, 0.01f)
        session.transformSelected(deltaX = 0f, deltaY = 0f, zoom = 1.5f)
        session.endTransform()

        assertEquals(original.fontSizeDots * 1.5f, session.document.elements.single().fontSizeDots, 0.01f)
    }

    private fun visualBounds(elements: List<LabelElement>): TestBounds {
        val corners = elements.flatMap { element ->
            val centerX = element.x + element.width / 2f
            val centerY = element.y + element.height / 2f
            val radians = Math.toRadians(element.rotation.toDouble())
            val cosine = kotlin.math.cos(radians).toFloat()
            val sine = kotlin.math.sin(radians).toFloat()
            listOf(
                element.x.toFloat() to element.y.toFloat(),
                element.right().toFloat() to element.y.toFloat(),
                element.right().toFloat() to element.bottom().toFloat(),
                element.x.toFloat() to element.bottom().toFloat(),
            ).map { (x, y) ->
                centerX + (x - centerX) * cosine - (y - centerY) * sine to
                    centerY + (x - centerX) * sine + (y - centerY) * cosine
            }
        }
        return TestBounds(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second },
        )
    }

    private data class TestBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
    }
}
