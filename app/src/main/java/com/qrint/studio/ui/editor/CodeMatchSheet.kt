package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.CodeTemplateMatch
import com.qrint.studio.data.ProductRecord
import com.qrint.studio.render.DecodedBarcode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeMatchSheet(
    decoded: DecodedBarcode,
    product: ProductRecord?,
    matches: List<CodeTemplateMatch>,
    onUseTemplate: (CodeTemplateMatch) -> Unit,
    onInsertProduct: ((ProductRecord) -> Unit)?,
    onInsertCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("本地匹配结果", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${decoded.type.label} · ${decoded.content}", maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            product?.let { item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inventory2, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifBlank { "已匹配本地商品" }, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(item.spec, item.price, item.sku).filter(String::isNotBlank).joinToString(" · ").ifBlank { item.barcode },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                onInsertProduct?.let { insert ->
                    FilledTonalButton(onClick = { insert(item) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Inventory2, null); Spacer(Modifier.width(7.dp)); Text("插入可编辑商品标签")
                    }
                }
            }
            if (matches.isNotEmpty()) {
                Text("匹配到 ${matches.size} 个本地模板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                matches.forEach { match ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ViewInAr, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(match.document.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${match.document.category} · ${match.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { onUseTemplate(match) }) { Text("套用") }
                        }
                    }
                }
            } else if (product == null) {
                Text("没有匹配到本地商品或模板；仍可把识别结果作为普通编码插入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onInsertCode, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(7.dp)); Text("只插入识别到的编码")
            }
        }
    }
}
