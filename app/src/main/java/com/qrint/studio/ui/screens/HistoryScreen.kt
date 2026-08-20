package com.qrint.studio.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PrintHistoryItem
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.render.RenderedLabel
import com.qrint.studio.ui.components.PageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    store: LocalStore,
    onOpenEditor: (LabelDocument) -> Unit,
) {
    val history by store.history.collectAsState()
    Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.statusBars)) {
        PageHeader("local records", "打印历史", "保存在设备本地，可重新编辑打印") {
            if (history.isNotEmpty()) FilledTonalButton(onClick = store::clearHistory) { Text("清空") }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(82.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, modifier = Modifier.size(38.dp)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("还没有打印记录", style = MaterialTheme.typography.titleLarge)
                    Text("成功或失败的任务都会留在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history, key = { it.id }) { item ->
                    HistoryCard(item, onOpen = { onOpenEditor(item.document) }, onDelete = { store.deleteHistory(item.id) })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: PrintHistoryItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var rendered by remember(item.id) { mutableStateOf<RenderedLabel?>(null) }
    LaunchedEffect(item.id) {
        var created: RenderedLabel? = null
        try {
            created = withContext(Dispatchers.Default) { runCatching { LabelRenderer.render(context, item.document) }.getOrNull() }
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
        shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 86.dp, height = 76.dp).background(Color.White, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = rendered?.bitmap
                if (bitmap == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp)) + " · ${item.copies} 份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onOpen) { Icon(Icons.Rounded.Restore, "重新编辑", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
