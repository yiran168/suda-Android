package com.qrint.studio.ui.screens

import com.qrint.studio.ProductIdentity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.data.IndustryCatalog
import com.qrint.studio.data.TemplateCatalog
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.ui.QuickCreateKind
import com.qrint.studio.ui.components.ActionTile
import com.qrint.studio.ui.components.SectionTitle
import com.qrint.studio.ui.editor.CreateLabelSheet
import com.qrint.studio.ui.quickDocument
import com.qrint.studio.ui.theme.LocalQrintVisuals
import com.qrint.studio.ui.theme.HomeLayout
import com.qrint.studio.ui.theme.SurfaceTreatment
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(
    padding: PaddingValues,
    printer: BluetoothPrinterManager,
    paper: StateFlow<PaperSettings>,
    onOpenTemplates: (String) -> Unit,
    onOpenEditor: (LabelDocument) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val printerState by printer.state.collectAsState()
    val defaults by paper.collectAsState()
    val visuals = LocalQrintVisuals.current
    var createKind by remember { mutableStateOf<QuickCreateKind?>(null) }
    var showPaperFeed by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Print, ProductIdentity.NAME, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(29.dp)) } }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(visuals.decorativeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(ProductIdentity.NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(
                            if (printerState.connected) printerState.deviceName else "未连接设备",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Surface(
                        shape = if (visuals.treatment == SurfaceTreatment.EDITORIAL) MaterialTheme.shapes.small else CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.size(44.dp).clickable { createKind = QuickCreateKind.CANVAS },
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "新建") } }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrinterStatusCard(
                        icon = Icons.Rounded.Description,
                        description = "纸张状态",
                        value = when {
                            !printerState.connected -> "--"
                            printerState.hardware == null -> "--"
                            printerState.hardware?.noPaper == true -> "缺纸"
                            else -> "就绪"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrinterStatusCard(
                        icon = Icons.Rounded.BatteryFull,
                        description = "打印机电量",
                        value = printerState.batteryPercent?.takeIf { printerState.connected }?.let { "$it%" } ?: "--",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                PrinterStatusCard(
                    icon = Icons.Rounded.ArrowDownward,
                    description = "单独走纸",
                    value = "走纸",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showPaperFeed = true },
                )
            }
        }
        item { SectionTitle("开始创作", "查看 ${TemplateCatalog.size} 款模板") { onOpenTemplates(IndustryCatalog.ALL) } }
        item {
            ThemedCreationBoard(visuals.homeLayout, quickTiles) { createKind = it }
        }
        item { SectionTitle("行业场景") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(industryScenes) { item ->
                    Card(
                        onClick = { onOpenTemplates(item.category) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.width(168.dp).height(132.dp),
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(item.title, fontWeight = FontWeight.Bold)
                                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BluetoothConnected, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("每个任务独立选择连续纸或标签纸", fontWeight = FontWeight.Bold)
                        Text(
                            "连续纸按内容自动算长；标签纸按创建时填写的宽高精确输出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }

    createKind?.let { kind ->
        CreateLabelSheet(
            kind = kind,
            defaults = defaults,
            onDismiss = { createKind = null },
            onCreate = { title, selectedPaper, noteStyle ->
                createKind = null
                onOpenEditor(quickDocument(kind, selectedPaper, title, noteStyle))
            },
        )
    }
    if (showPaperFeed) {
        PaperFeedSheet(
            paper = defaults,
            printer = printer,
            onOpenSettings = onOpenSettings,
            onDismiss = { showPaperFeed = false },
        )
    }
}

private data class TileSpec(val kind: QuickCreateKind, val icon: ImageVector, val accent: Color)

private val quickTiles = listOf(
    TileSpec(QuickCreateKind.TEXT, Icons.Rounded.TextFields, Color(0xFF315DFF)),
    TileSpec(QuickCreateKind.IMAGE, Icons.Rounded.Image, Color(0xFF00A88A)),
    TileSpec(QuickCreateKind.QR, Icons.Rounded.QrCode2, Color(0xFFFF6B62)),
    TileSpec(QuickCreateKind.BARCODE, Icons.Rounded.ViewWeek, Color(0xFF9A5CF6)),
    TileSpec(QuickCreateKind.NOTE, Icons.AutoMirrored.Rounded.StickyNote2, Color(0xFFE58B19)),
    TileSpec(QuickCreateKind.CANVAS, Icons.Rounded.Draw, Color(0xFF39717C)),
)

private data class IndustryScene(val category: String, val title: String, val subtitle: String, val icon: ImageVector)

private val industryScenes = IndustryCatalog.categories.map { category ->
    IndustryScene(category.name, category.name, category.description, industryIcon(category.name))
}

private fun industryIcon(category: String): ImageVector = when (category) {
    "通用" -> Icons.AutoMirrored.Rounded.StickyNote2
    "商业零售" -> Icons.AutoMirrored.Rounded.ReceiptLong
    "餐饮服务" -> Icons.Rounded.CheckCircle
    "医药行业" -> Icons.Rounded.QrCode2
    "办公管理" -> Icons.Rounded.TextFields
    "通讯电力" -> Icons.Rounded.BluetoothConnected
    "居家生活" -> Icons.Rounded.Image
    "生产制造" -> Icons.Rounded.Draw
    "仓储物流" -> Icons.Rounded.Sensors
    "收款码" -> Icons.Rounded.QrCode2
    "直播带货" -> Icons.Rounded.ViewWeek
    else -> Icons.Rounded.Print
}

@Composable
private fun PrinterStatusCard(
    icon: ImageVector,
    description: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.height(76.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(27.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemedCreationBoard(layout: HomeLayout, tiles: List<TileSpec>, onClick: (QuickCreateKind) -> Unit) {
    when (layout) {
        HomeLayout.BENTO -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEachIndexed { row, pair -> HomeTileRow(pair[0], pair[1], onClick, startIndex = row * 2) }
        }
        HomeLayout.JOURNAL -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEachIndexed { index, tile -> HomeActionTile(tile, onClick, Modifier.fillMaxWidth(), 82.dp, true, index) }
        }
        HomeLayout.EDITORIAL -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeActionTile(tiles[0], onClick, Modifier.fillMaxWidth(), 126.dp, false, 0)
            HomeTileRow(tiles[1], tiles[2], onClick, 112.dp, true, 1)
            HomeTileRow(tiles[3], tiles[4], onClick, 112.dp, true, 3)
            HomeActionTile(tiles[5], onClick, Modifier.fillMaxWidth(), 92.dp, true, 5)
        }
        HomeLayout.LAB -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            tiles.chunked(3).forEachIndexed { row, group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    group.forEachIndexed { column, tile -> HomeActionTile(tile, onClick, Modifier.weight(1f), 136.dp, true, row * 3 + column) }
                }
            }
        }
        HomeLayout.BUBBLES, HomeLayout.LIQUID_RAIL -> LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tiles) { tile ->
                val index = tiles.indexOf(tile)
                HomeActionTile(tile, onClick, Modifier.width(if (layout == HomeLayout.BUBBLES) 196.dp else 216.dp), if (layout == HomeLayout.BUBBLES) 188.dp else 172.dp, false, index)
            }
        }
        HomeLayout.TERMINAL -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            tiles.forEachIndexed { index, tile -> HomeActionTile(tile, onClick, Modifier.fillMaxWidth(), 76.dp, true, index) }
        }
        HomeLayout.FROST_DECK -> Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeActionTile(tiles[0], onClick, Modifier.fillMaxWidth(), 126.dp, false, 0)
            HomeTileRow(tiles[1], tiles[2], onClick, 160.dp, false, 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                tiles.drop(3).forEachIndexed { index, tile -> HomeActionTile(tile, onClick, Modifier.weight(1f), 132.dp, true, index + 3) }
            }
        }
        HomeLayout.SMOKE_CONSOLE -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.take(2).forEachIndexed { index, tile -> HomeActionTile(tile, onClick, Modifier.fillMaxWidth(), 82.dp, true, index) }
            HomeTileRow(tiles[2], tiles[3], onClick, 122.dp, true, 2)
            HomeTileRow(tiles[4], tiles[5], onClick, 122.dp, true, 4)
        }
        HomeLayout.PRISM_MOSAIC -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeActionTile(tiles[0], onClick, Modifier.weight(1.45f), 176.dp, false, 0)
                HomeActionTile(tiles[1], onClick, Modifier.weight(0.8f), 176.dp, true, 1)
            }
            HomeTileRow(tiles[2], tiles[3], onClick, 138.dp, true, 2)
            HomeTileRow(tiles[4], tiles[5], onClick, 138.dp, true, 4)
        }
    }
}

@Composable
private fun HomeActionTile(
    tile: TileSpec,
    onClick: (QuickCreateKind) -> Unit,
    modifier: Modifier,
    height: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    index: Int,
) {
    ActionTile(
        title = tile.kind.title,
        subtitle = tile.kind.subtitle,
        icon = tile.icon,
        accent = tile.accent,
        onClick = { onClick(tile.kind) },
        modifier = modifier,
        height = height,
        compact = compact,
        index = (index + 1).toString().padStart(2, '0'),
    )
}

@Composable
private fun HomeTileRow(
    first: TileSpec,
    second: TileSpec,
    onClick: (QuickCreateKind) -> Unit,
    height: androidx.compose.ui.unit.Dp = 168.dp,
    compact: Boolean = false,
    startIndex: Int = 0,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeActionTile(first, onClick, Modifier.weight(1f), height, compact, startIndex)
        HomeActionTile(second, onClick, Modifier.weight(1f), height, compact, startIndex + 1)
    }
}
