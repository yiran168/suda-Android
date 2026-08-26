package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.DitherMode
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PrintFontCatalog
import com.qrint.studio.model.PrintFontOption
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TextAlignment
import com.qrint.studio.model.TextEnhancementMode
import com.qrint.studio.render.BarcodeNormalizer
import com.qrint.studio.ui.components.BoundedMultilineTextField
import com.qrint.studio.ui.components.DraftNumberField
import kotlin.math.roundToInt

@Composable
fun ElementPropertiesPanel(
    element: LabelElement,
    paper: PaperSettings,
    onUpdate: (LabelElement) -> Unit,
    onScaleStart: () -> Unit,
    onScaleToWidthDots: (Int) -> Unit,
    onScaleTextToFontSize: (Float) -> Unit,
    onScaleEnd: () -> Unit,
    onFitContent: () -> Unit,
    onAlignOnCanvas: (SelectionAlignment) -> Unit,
    onReplaceImage: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onBringForward: () -> Unit,
    onSendBackward: () -> Unit,
    modifier: Modifier = Modifier,
    fontOptions: List<PrintFontOption> = PrintFontCatalog.options,
    onImportFont: () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kindTitle(element.kind), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "所有尺寸均落到打印点；1 mm = ${"%.2f".format(paper.dpi / 25.4f)} 点",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = element.locked,
                onClick = { onUpdate(element.copy(locked = !element.locked)) },
                label = { Text(if (element.locked) "已锁定" else "未锁定") },
            )
        }

        PropertyCard("位置与尺寸") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactNumber("X", element.x.toString(), { it.toIntOrNull()?.let { value -> onUpdate(element.copy(x = value)) } }, Modifier.weight(1f))
                CompactNumber("Y", element.y.toString(), { it.toIntOrNull()?.let { value -> onUpdate(element.copy(y = value)) } }, Modifier.weight(1f))
                CompactNumber("宽", element.width.toString(), { it.toIntOrNull()?.let { value -> onUpdate(element.copy(width = value.coerceAtLeast(16))) } }, Modifier.weight(1f))
                CompactNumber("高", element.height.toString(), { it.toIntOrNull()?.let { value -> onUpdate(element.copy(height = value.coerceAtLeast(16))) } }, Modifier.weight(1f))
            }
            Text("旋转 ${element.rotation.roundToInt()}°", fontWeight = FontWeight.SemiBold)
            Slider(value = element.rotation, onValueChange = { onUpdate(element.copy(rotation = it)) }, valueRange = -180f..180f)
        }

        CanvasAlignmentProperties(onAlign = onAlignOnCanvas)

        if (element.kind == ElementKind.IMAGE || element.kind == ElementKind.TEXT ||
            element.kind == ElementKind.DATE_TIME || element.kind == ElementKind.SEQUENCE
        ) {
            ProportionalSizeProperties(
                element = element,
                paper = paper,
                onScaleStart = onScaleStart,
                onScaleToWidthDots = onScaleToWidthDots,
                onScaleEnd = onScaleEnd,
                onFitContent = onFitContent,
            )
        }

        when (element.kind) {
            ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE ->
                TextProperties(
                    element = element,
                    onUpdate = onUpdate,
                    fontOptions = fontOptions,
                    onImportFont = onImportFont,
                    onScaleStart = onScaleStart,
                    onScaleToFontSize = onScaleTextToFontSize,
                    onScaleEnd = onScaleEnd,
                )
            ElementKind.IMAGE -> ImageProperties(element, onUpdate, onReplaceImage)
            ElementKind.BARCODE -> BarcodeProperties(element, onUpdate)
            ElementKind.SHAPE -> ShapeProperties(element, onUpdate)
            ElementKind.TABLE -> TableProperties(element, onUpdate)
            ElementKind.DRAWING -> DrawingProperties(element, onUpdate)
        }

        PropertyCard("图层与操作") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("上移", Icons.Rounded.VerticalAlignTop, onBringForward, Modifier.weight(1f))
                SmallAction("下移", Icons.Rounded.VerticalAlignBottom, onSendBackward, Modifier.weight(1f))
                SmallAction("复制", Icons.Rounded.ContentCopy, onDuplicate, Modifier.weight(1f))
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("删除元素", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextProperties(
    element: LabelElement,
    onUpdate: (LabelElement) -> Unit,
    fontOptions: List<PrintFontOption>,
    onImportFont: () -> Unit,
    onScaleStart: () -> Unit,
    onScaleToFontSize: (Float) -> Unit,
    onScaleEnd: () -> Unit,
) {
    var scalingFont by remember(element.id) { mutableStateOf(false) }
    PropertyCard("内容与字体") {
        when (element.kind) {
            ElementKind.DATE_TIME -> BoundedMultilineTextField(
                value = element.datePattern,
                onValueChange = { onUpdate(element.copy(datePattern = it)) },
                label = "日期格式",
                supportingText = "例如 yyyy-MM-dd HH:mm:ss",
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            ElementKind.SEQUENCE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumber("起始", element.sequenceStart.toString(), { it.toLongOrNull()?.let { value -> onUpdate(element.copy(sequenceStart = value)) } }, Modifier.weight(1f))
                    CompactNumber("步长", element.sequenceStep.toString(), { it.toLongOrNull()?.let { value -> onUpdate(element.copy(sequenceStep = value)) } }, Modifier.weight(1f))
                    CompactNumber("位数", element.sequenceDigits.toString(), { it.toIntOrNull()?.let { value -> onUpdate(element.copy(sequenceDigits = value.coerceIn(1, 18))) } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactText("前缀", element.sequencePrefix, { onUpdate(element.copy(sequencePrefix = it)) }, Modifier.weight(1f))
                    CompactText("后缀", element.sequenceSuffix, { onUpdate(element.copy(sequenceSuffix = it)) }, Modifier.weight(1f))
                }
            }
            else -> BoundedMultilineTextField(
                value = element.text,
                onValueChange = { onUpdate(element.copy(text = it)) },
                label = "文字内容",
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EnumDropdown(
            label = "字体",
            value = element.fontFamily,
            options = fontOptions.map { it.key },
            display = { key -> fontOptions.firstOrNull { it.key == key }?.title ?: "字体文件缺失" },
            onSelect = { onUpdate(element.copy(fontFamily = it)) },
        )
        val selectedFont = fontOptions.firstOrNull { it.key == element.fontFamily }
        Text(
            selectedFont?.description ?: "该本地字体文件已不存在，预览和打印会安全回退为系统黑体",
            color = if (selectedFont == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onImportFont, modifier = Modifier.fillMaxWidth()) {
            Text("导入 TTF / OTF 本地字体")
        }
        Text("字号 ${"%.1f".format(element.fontSizeDots)} 点", fontWeight = FontWeight.SemiBold)
        Slider(
            value = element.fontSizeDots.coerceIn(8f, 240f),
            onValueChange = { target ->
                if (!scalingFont) {
                    onScaleStart()
                    scalingFont = true
                }
                onScaleToFontSize(target)
            },
            onValueChangeFinished = {
                if (scalingFont) onScaleEnd()
                scalingFont = false
            },
            valueRange = 8f..240f,
        )
        DraftNumberField(
            label = "直接输入字号（点）",
            value = "%.1f".format(element.fontSizeDots),
            onDraftChange = {},
            inputTransform = ::positiveDecimalDraft,
            onCommit = { draft ->
                draft.toFloatOrNull()?.let { value ->
                    onScaleStart()
                    onScaleToFontSize(value.coerceIn(8f, 240f))
                    onScaleEnd()
                }
            },
        )
        Text("字重 ${element.fontWeight} · 可连续调节", fontWeight = FontWeight.SemiBold)
        Slider(
            value = element.fontWeight.toFloat(),
            onValueChange = { onUpdate(element.copy(fontWeight = PrintFontCatalog.normalizeWeight(it.roundToInt()))) },
            valueRange = PrintFontCatalog.MIN_WEIGHT.toFloat()..PrintFontCatalog.MAX_WEIGHT.toFloat(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PrintFontCatalog.weightPresets.forEach { preset ->
                FilterChip(
                    selected = element.fontWeight == preset.value,
                    onClick = { onUpdate(element.copy(fontWeight = preset.value)) },
                    label = { Text("${preset.title} ${preset.value}") },
                )
            }
        }
        EnumDropdown(
            "文字增强",
            element.textEnhancement,
            TextEnhancementMode.entries,
            { it.title },
        ) { onUpdate(element.copy(textEnhancement = it)) }
        Text(element.textEnhancement.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp), maxItemsInEachRow = 4) {
            ToggleChip("斜体", element.italic, Modifier.width(86.dp)) { onUpdate(element.copy(italic = it)) }
            ToggleChip("下划线", element.underline, Modifier.width(86.dp)) { onUpdate(element.copy(underline = it)) }
            ToggleChip("竖排", element.verticalText, Modifier.width(86.dp)) { onUpdate(element.copy(verticalText = it)) }
            ToggleChip("反色", element.invert, Modifier.width(86.dp)) { onUpdate(element.copy(invert = it)) }
        }
        Text("字间距 ${"%.1f".format(element.letterSpacingDots)} · 行间距 ${"%.1f".format(element.lineSpacingDots)}")
        Slider(value = element.letterSpacingDots, onValueChange = { onUpdate(element.copy(letterSpacingDots = it)) }, valueRange = -1f..20f)
        Slider(value = element.lineSpacingDots, onValueChange = { onUpdate(element.copy(lineSpacingDots = it)) }, valueRange = 0f..30f)
        Text("文字在框内对齐", fontWeight = FontWeight.SemiBold)
        Text("这里只改变文字在自身蓝色选框内的排版；画布中的左/中/右请使用上面的‘画布中的位置’。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TextAlignment.entries.forEach { alignment ->
                FilterChip(
                    selected = element.textAlignment == alignment,
                    onClick = { onUpdate(element.copy(textAlignment = alignment)) },
                    label = { Text(when (alignment) { TextAlignment.LEFT -> "左"; TextAlignment.CENTER -> "中"; TextAlignment.RIGHT -> "右" }) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanvasAlignmentProperties(onAlign: (SelectionAlignment) -> Unit) {
    PropertyCard("画布中的位置") {
        Text(
            "对齐蓝色选框整体，不改变框内文字或图片的排版。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(
                SelectionAlignment.LEFT to "框靠左",
                SelectionAlignment.HORIZONTAL_CENTER to "框水平居中",
                SelectionAlignment.RIGHT to "框靠右",
                SelectionAlignment.TOP to "框靠上",
                SelectionAlignment.VERTICAL_CENTER to "框垂直居中",
                SelectionAlignment.BOTTOM to "框靠下",
            ).forEach { (alignment, label) ->
                FilterChip(
                    selected = false,
                    onClick = { onAlign(alignment) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ProportionalSizeProperties(
    element: LabelElement,
    paper: PaperSettings,
    onScaleStart: () -> Unit,
    onScaleToWidthDots: (Int) -> Unit,
    onScaleEnd: () -> Unit,
    onFitContent: () -> Unit,
) {
    val minimum = MIN_ELEMENT_DOTS.toFloat()
    val maximum = paper.contentWidthDots().coerceAtLeast(MIN_ELEMENT_DOTS).toFloat()
    var sliding by remember(element.id) { mutableStateOf(false) }
    PropertyCard("等比缩放与内容边界") {
        Text(
            "当前 ${"%.1f".format(paper.dotsToMm(element.width))} × ${"%.1f".format(paper.dotsToMm(element.height))} mm",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "滑条和数值输入都会同时缩放内容与蓝色选框，不改变宽高比例。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = element.width.toFloat().coerceIn(minimum, maximum),
            onValueChange = { value ->
                if (!sliding) {
                    onScaleStart()
                    sliding = true
                }
                onScaleToWidthDots(value.roundToInt())
            },
            onValueChangeFinished = {
                if (sliding) onScaleEnd()
                sliding = false
            },
            valueRange = minimum..maximum,
        )
        DraftNumberField(
            label = "目标宽度（mm）",
            value = "%.1f".format(paper.dotsToMm(element.width)),
            onDraftChange = {},
            inputTransform = ::positiveDecimalDraft,
            onCommit = { draft ->
                draft.toFloatOrNull()?.takeIf { it > 0f }?.let { millimetres ->
                    onScaleStart()
                    onScaleToWidthDots(paper.mmToDots(millimetres).coerceIn(MIN_ELEMENT_DOTS, maximum.roundToInt()))
                    onScaleEnd()
                }
            },
        )
        OutlinedButton(onClick = onFitContent, modifier = Modifier.fillMaxWidth()) {
            Text(if (element.kind == ElementKind.IMAGE) "按原图比例贴合图片边缘" else "按实际字形贴合文字边缘")
        }
    }
}

@Composable
private fun ImageProperties(element: LabelElement, onUpdate: (LabelElement) -> Unit, onReplaceImage: () -> Unit) {
    PropertyCard("图片处理") {
        Button(onClick = onReplaceImage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(if (element.imageUri.isBlank()) "选择图片" else "更换图片")
        }
        EnumDropdown("抖动算法", element.ditherMode, DitherMode.entries, { it.title }) { onUpdate(element.copy(ditherMode = it)) }
        Text(element.ditherMode.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("灰度阈值 ${element.threshold}", fontWeight = FontWeight.SemiBold)
        Slider(value = element.threshold.toFloat(), onValueChange = { onUpdate(element.copy(threshold = it.roundToInt())) }, valueRange = 40f..240f)
        Text("亮度 ${"%.2f".format(element.brightness)}")
        Slider(value = element.brightness, onValueChange = { onUpdate(element.copy(brightness = it)) }, valueRange = -1f..1f)
        Text("对比度 ${"%.2f".format(element.contrast)}")
        Slider(value = element.contrast, onValueChange = { onUpdate(element.copy(contrast = it)) }, valueRange = 0.2f..3f)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ImageFit.entries.forEach { fit ->
                FilterChip(
                    selected = element.imageFit == fit,
                    onClick = { onUpdate(element.copy(imageFit = fit)) },
                    label = { Text(when (fit) { ImageFit.FIT -> "适应"; ImageFit.CROP -> "裁切"; ImageFit.STRETCH -> "拉伸" }) },
                )
            }
            ToggleChip("反色", element.invert) { onUpdate(element.copy(invert = it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarcodeProperties(element: LabelElement, onUpdate: (LabelElement) -> Unit) {
    val normalized = remember(element.barcodeType, element.barcodeContent) {
        BarcodeNormalizer.normalize(element.barcodeType, element.barcodeContent)
    }
    val family = BarcodeType.entries.filter { it.twoDimensional == element.barcodeType.twoDimensional }
    PropertyCard(if (element.barcodeType.twoDimensional) "二维码设置" else "一维码设置") {
        if (element.barcodeType.twoDimensional) {
            Text("常用内容类型", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                qrContentPresets.forEach { preset ->
                    FilterChip(
                        selected = element.barcodeContent == preset.value,
                        onClick = { onUpdate(element.copy(barcodeContent = preset.value)) },
                        label = { Text(preset.label) },
                    )
                }
            }
        }
        BoundedMultilineTextField(
            value = element.barcodeContent,
            onValueChange = { onUpdate(element.copy(barcodeContent = it)) },
            label = "编码内容",
            minLines = 2,
            maxLines = 6,
            supportingText = normalized.notice,
            modifier = Modifier.fillMaxWidth(),
        )
        EnumDropdown("码制", element.barcodeType, family, { it.label }) { selected ->
            onUpdate(element.copy(
                barcodeType = selected,
                width = if (selected.twoDimensional) element.height else maxOf(element.width, 260),
                barcodeCaption = !selected.twoDimensional,
            ))
        }
        if (normalized.changed) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("安全编码结果", fontWeight = FontWeight.Bold)
                    Text(normalized.value, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!element.barcodeType.twoDimensional) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("一维码下方显示内容", modifier = Modifier.weight(1f))
                Switch(checked = element.barcodeCaption, onCheckedChange = { onUpdate(element.copy(barcodeCaption = it)) })
            }
        }
        if (element.barcodeType == BarcodeType.QR_CODE || element.barcodeType == BarcodeType.GS1_QR) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("L", "M", "Q", "H").forEach { level ->
                    FilterChip(
                        selected = element.qrErrorCorrection == level,
                        onClick = { onUpdate(element.copy(qrErrorCorrection = level)) },
                        label = { Text("纠错 $level") },
                    )
                }
            }
        }
        ToggleChip("反色", element.invert) { onUpdate(element.copy(invert = it)) }
    }
}

@Composable
private fun TableProperties(element: LabelElement, onUpdate: (LabelElement) -> Unit) {
    PropertyCard("表格与单元格") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactNumber("行数", element.tableRows.toString(), {
                it.toIntOrNull()?.let { value -> onUpdate(element.copy(tableRows = value.coerceIn(1, 12))) }
            }, Modifier.weight(1f))
            CompactNumber("列数", element.tableColumns.toString(), {
                it.toIntOrNull()?.let { value -> onUpdate(element.copy(tableColumns = value.coerceIn(1, 8))) }
            }, Modifier.weight(1f))
        }
        BoundedMultilineTextField(
            value = element.tableData,
            onValueChange = { onUpdate(element.copy(tableData = it)) },
            label = "表格内容",
            supportingText = "每行换行，每个单元格用 | 分隔",
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("首行加粗", modifier = Modifier.weight(1f))
            Switch(checked = element.tableHeader, onCheckedChange = { onUpdate(element.copy(tableHeader = it)) })
        }
        Text("字号 ${element.fontSizeDots.roundToInt()} 点 · 线宽 ${element.strokeWidthDots.roundToInt()} 点")
        Slider(value = element.fontSizeDots, onValueChange = { onUpdate(element.copy(fontSizeDots = it)) }, valueRange = 8f..48f)
        Slider(value = element.strokeWidthDots, onValueChange = { onUpdate(element.copy(strokeWidthDots = it)) }, valueRange = 1f..12f)
        ToggleChip("反色", element.invert) { onUpdate(element.copy(invert = it)) }
    }
}

@Composable
private fun ShapeProperties(element: LabelElement, onUpdate: (LabelElement) -> Unit) {
    PropertyCard("形状样式") {
        EnumDropdown("形状", element.shapeKind, ShapeKind.entries, { shapeLabel(it) }) { onUpdate(element.copy(shapeKind = it)) }
        Text("线宽 ${element.strokeWidthDots.roundToInt()} 点")
        Slider(value = element.strokeWidthDots, onValueChange = { onUpdate(element.copy(strokeWidthDots = it)) }, valueRange = 1f..24f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("实心填充", modifier = Modifier.weight(1f))
            Switch(checked = element.filled, onCheckedChange = { onUpdate(element.copy(filled = it)) })
        }
        ToggleChip("反色", element.invert) { onUpdate(element.copy(invert = it)) }
    }
}

@Composable
private fun DrawingProperties(element: LabelElement, onUpdate: (LabelElement) -> Unit) {
    PropertyCard("手绘样式") {
        Text("笔画宽度 ${element.strokeWidthDots.roundToInt()} 点")
        Slider(value = element.strokeWidthDots, onValueChange = { onUpdate(element.copy(strokeWidthDots = it)) }, valueRange = 1f..24f)
        Text("${element.drawingPoints.size / 2} 个采样点", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleChip("反色", element.invert) { onUpdate(element.copy(invert = it)) }
    }
}

@Composable
private fun PropertyCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(label: String, value: T, options: List<T>, display: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display(value), onValueChange = {}, readOnly = true, label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun CompactNumber(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) =
    DraftNumberField(
        label = label,
        value = value,
        onDraftChange = onChange,
        modifier = modifier,
        inputTransform = ::signedIntegerDraft,
    )

private fun signedIntegerDraft(value: String): String = buildString {
    value.take(20).forEachIndexed { index, character ->
        if (character.isDigit() || (character == '-' && index == 0)) append(character)
    }
}

private fun positiveDecimalDraft(value: String): String = buildString {
    var decimalSeen = false
    value.replace(',', '.').take(12).forEach { character ->
        when {
            character.isDigit() -> append(character)
            character == '.' && !decimalSeen -> {
                decimalSeen = true
                append(character)
            }
        }
    }
}

@Composable
private fun CompactText(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier)

@Composable
private fun ToggleChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    FilterChip(selected = selected, onClick = { onChange(!selected) }, label = { Text(label, maxLines = 1) }, modifier = modifier)

@Composable
private fun SmallAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) =
    OutlinedButton(onClick = onClick, modifier = modifier) { Icon(icon, null); Spacer(Modifier.width(5.dp)); Text(label) }

private fun kindTitle(kind: ElementKind): String = when (kind) {
    ElementKind.TEXT -> "编辑文字"
    ElementKind.IMAGE -> "编辑图片"
    ElementKind.BARCODE -> "编辑条码"
    ElementKind.SHAPE -> "编辑形状"
    ElementKind.TABLE -> "编辑表格"
    ElementKind.DATE_TIME -> "编辑日期时间"
    ElementKind.SEQUENCE -> "编辑流水号"
    ElementKind.DRAWING -> "编辑手绘签名"
}

private fun shapeLabel(kind: ShapeKind): String = when (kind) {
    ShapeKind.RECTANGLE -> "矩形"
    ShapeKind.ROUNDED_RECTANGLE -> "圆角矩形"
    ShapeKind.LINE -> "直线"
    ShapeKind.VERTICAL_LINE -> "竖直线"
    ShapeKind.DASHED_LINE -> "虚线"
    ShapeKind.DASHED_VERTICAL_LINE -> "竖虚线"
    ShapeKind.ELLIPSE -> "椭圆"
    ShapeKind.TRIANGLE -> "三角形"
    ShapeKind.PENTAGON -> "五边形"
    ShapeKind.HEXAGON -> "六边形"
    ShapeKind.DIAMOND -> "菱形"
    ShapeKind.STAR -> "五角星"
    ShapeKind.HEART -> "爱心"
    ShapeKind.PLUS -> "加号"
    ShapeKind.CHECKMARK -> "对勾"
    ShapeKind.ARROW_LEFT -> "左箭头"
    ShapeKind.ARROW_RIGHT -> "右箭头"
    ShapeKind.ARROW_UP -> "上箭头"
    ShapeKind.ARROW_DOWN -> "下箭头"
    ShapeKind.SPEECH_BUBBLE -> "对话气泡"
    ShapeKind.CROSS -> "十字标记"
}

private data class QrContentPreset(val label: String, val value: String)

private val qrContentPresets = listOf(
    QrContentPreset("文本", "在这里输入内容"),
    QrContentPreset("网址", "https://example.com"),
    QrContentPreset("Wi-Fi", "WIFI:T:WPA;S:网络名称;P:密码;;"),
    QrContentPreset("联系人", "BEGIN:VCARD\nVERSION:3.0\nFN:联系人\nTEL:13800000000\nEND:VCARD"),
    QrContentPreset("电话", "tel:13800000000"),
    QrContentPreset("短信", "SMSTO:13800000000:短信内容"),
    QrContentPreset("邮件", "MATMSG:TO:name@example.com;SUB:主题;BODY:正文;;"),
    QrContentPreset("定位", "geo:39.9042,116.4074"),
    QrContentPreset("日历", "BEGIN:VEVENT\nSUMMARY:事项名称\nDTSTART:20260812T090000\nDTEND:20260812T100000\nEND:VEVENT"),
    QrContentPreset("支付宝", "https://qr.alipay.com/在这里粘贴收款链接"),
    QrContentPreset("微信链接", "https://weixin.qq.com/在这里粘贴链接"),
    QrContentPreset("应用链接", "https://example.com/app"),
    QrContentPreset("纯数字", "202608120001"),
    QrContentPreset("GS1 商品", "(01)06901234567890(10)BATCH01(17)280812"),
    QrContentPreset("名片 MECARD", "MECARD:N:张三;TEL:13800000000;EMAIL:name@example.com;;"),
    QrContentPreset("网络配置", "WIFI:T:WPA2;S:网络名称;P:密码;H:false;;"),
)
