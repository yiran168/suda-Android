package com.qrint.studio.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.LocalStore
import com.qrint.studio.data.TemplateCatalog
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperMode
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.render.RenderedLabel
import com.qrint.studio.ui.components.PageHeader
import com.qrint.studio.ui.editor.PhysicalPaperPreview
import com.qrint.studio.ui.editor.TemplatePaperSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
fun TemplateScreen(
    padding: PaddingValues,
    catalog: TemplateCatalog,
    store: LocalStore,
    initialCategory: String = "全部",
    onOpenEditor: (LabelDocument) -> Unit,
) {
    val userTemplates by store.templates.collectAsState()
    val favoriteIds by store.templateUsage.favorites.collectAsState()
    val recentIds by store.templateUsage.recent.collectAsState()
    var category by remember(initialCategory) { mutableStateOf(initialCategory.takeIf { it in catalog.categories } ?: "全部") }
    var search by remember { mutableStateOf("") }
    var paperFilter by remember { mutableStateOf("全部纸型") }
    var pendingTemplate by remember { mutableStateOf<LabelDocument?>(null) }
    val categories = remember { listOf("我的", "收藏", "最近") + catalog.categories }
    val documents = remember(category, search, paperFilter, userTemplates, favoriteIds, recentIds) {
        val all = catalog.all.map { it.document } + userTemplates
        val byId = all.associateBy(LabelDocument::id)
        val base = when (category) {
            "我的" -> userTemplates
            "收藏" -> all.filter { it.id in favoriteIds }
            "最近" -> recentIds.mapNotNull(byId::get)
            "全部" -> all
            else -> catalog.inCategory(category).map { it.document } + userTemplates.filter { it.category == category }
        }
        val searched = if (search.isBlank()) base else base.filter {
            it.title.contains(search, true) || it.category.contains(search, true) ||
                it.elements.any { element -> element.text.contains(search, true) }
        }
        when (paperFilter) {
            "连续纸" -> searched.filter { it.paper.mode == PaperMode.CONTINUOUS }
            "标签纸" -> searched.filter { it.paper.mode == PaperMode.LABEL }
            else -> searched
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.statusBars),
    ) {
        PageHeader("${catalog.all.size} 款可编辑模板", "行业模板", "源图内容已分层，文字、编码与图案均可调整") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ViewInAr, null) }
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("搜索模板名称、分类或文字") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(listOf("全部纸型", "标签纸", "连续纸")) { item ->
                FilterChip(selected = paperFilter == item, onClick = { paperFilter = item }, label = { Text(item) })
            }
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("${documents.size} 款可编辑", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LazyColumn(
                        modifier = Modifier.width(154.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(categories) { item ->
                            FilterChip(
                                selected = category == item,
                                onClick = { category = item },
                                label = { Text(item) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    TemplateGrid(
                        documents,
                        store,
                        favoriteIds = favoriteIds,
                        onOpen = { document ->
                            store.templateUsage.recordOpened(document.id)
                            if (document.builtIn) pendingTemplate = document else onOpenEditor(document)
                        },
                        modifier = Modifier.weight(1f),
                        columns = 3,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(categories) { item ->
                            FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) })
                        }
                    }
                    TemplateGrid(
                        documents,
                        store,
                        favoriteIds = favoriteIds,
                        onOpen = { document ->
                            store.templateUsage.recordOpened(document.id)
                            if (document.builtIn) pendingTemplate = document else onOpenEditor(document)
                        },
                        modifier = Modifier.weight(1f),
                        columns = 2,
                    )
                }
            }
        }
    }

    pendingTemplate?.let { template ->
        TemplatePaperSheet(
            template = template,
            onDismiss = { pendingTemplate = null },
            onConfirm = { fitted ->
                pendingTemplate = null
                onOpenEditor(fitted)
            },
        )
    }
}

@Composable
private fun TemplateGrid(
    documents: List<LabelDocument>,
    store: LocalStore,
    favoriteIds: Set<String>,
    onOpen: (LabelDocument) -> Unit,
    modifier: Modifier,
    columns: Int,
) {
    if (documents.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有匹配的模板", style = MaterialTheme.typography.titleMedium)
                Text("换个关键词或分类试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            TemplateCard(
                document,
                favorite = document.id in favoriteIds,
                onToggleFavorite = { store.templateUsage.toggleFavorite(document.id) },
                onOpen = { onOpen(document) },
                onDelete = if (document.builtIn) null else { { store.deleteTemplate(document.id) } },
            )
        }
    }
}

@Composable
private fun TemplateCard(
    document: LabelDocument,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var rendered by remember(document.id, document.updatedAt) { mutableStateOf<RenderedLabel?>(null) }
    LaunchedEffect(document.id, document.updatedAt) {
        delay(20)
        var created: RenderedLabel? = null
        try {
            created = withContext(Dispatchers.Default) { runCatching { LabelRenderer.render(context, document) }.getOrNull() }
            coroutineContext.ensureActive()
            rendered = created
            created = null
        } finally {
            created?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }
    DisposableEffect(rendered?.bitmap) {
        val bitmap = rendered?.bitmap
        onDispose { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.12f).background(Color(0xFFEDEEF4)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap: Bitmap? = rendered?.bitmap
                if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else PhysicalPaperPreview(
                    document = document,
                    rendered = rendered!!,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 7.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(document.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${document.category} · ${document.paper.contentWidthMm.toInt()}×${document.paper.labelHeightMm.toInt()} mm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (favorite) "取消收藏" else "收藏",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onDelete?.let { IconButton(onClick = it) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}
