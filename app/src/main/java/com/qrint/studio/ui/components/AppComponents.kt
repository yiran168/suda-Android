package com.qrint.studio.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.qrint.studio.printer.BluetoothPermissions
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.printer.ConnectionPhase
import com.qrint.studio.ui.theme.LocalQrintVisuals
import com.qrint.studio.ui.theme.SurfaceTreatment

@Composable
fun PageHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action?.invoke()
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, onTrailingClick: (() -> Unit)? = null) {
    val visuals = LocalQrintVisuals.current
    val displayedTitle = when (visuals.treatment) {
        SurfaceTreatment.PAPER -> "✦  $title"
        SurfaceTreatment.EDITORIAL -> "0${sectionNumber(title)}  /  $title"
        SurfaceTreatment.GRID -> "[$title]"
        SurfaceTreatment.NEON, SurfaceTreatment.SMOKE -> "> $title"
        else -> title
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visuals.treatment == SurfaceTreatment.EDITORIAL) {
            Box(Modifier.width(5.dp).height(28.dp).background(MaterialTheme.colorScheme.secondary))
            Spacer(Modifier.width(10.dp))
        }
        Text(displayedTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(enabled = onTrailingClick != null) { onTrailingClick?.invoke() }.padding(8.dp),
            )
        }
    }
}

@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    compact: Boolean = false,
    index: String? = null,
) {
    val visuals = LocalQrintVisuals.current
    val glass = visuals.treatment in glassTreatments
    val border = when {
        glass -> BorderStroke(
            1.dp,
            if (visuals.treatment == SurfaceTreatment.SMOKE) Color.White.copy(alpha = 0.16f)
            else Color.White.copy(alpha = 0.64f),
        )
        visuals.outlinedTiles -> BorderStroke(
            if (visuals.treatment == SurfaceTreatment.EDITORIAL) 2.dp else 1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
        )
        else -> null
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.035f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "action-tile-long-press",
    )
    Card(
        onClick = onClick,
        modifier = modifier.height(height).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = MaterialTheme.shapes.large,
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = visuals.tileElevation.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (glass) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            when (visuals.treatment) {
                                SurfaceTreatment.FROST -> listOf(Color.White.copy(alpha = 0.34f), Color.Transparent, accent.copy(alpha = 0.05f))
                                SurfaceTreatment.LIQUID -> listOf(Color.White.copy(alpha = 0.22f), accent.copy(alpha = 0.09f), Color.Transparent)
                                SurfaceTreatment.SMOKE -> listOf(Color.White.copy(alpha = 0.07f), Color.Transparent, accent.copy(alpha = 0.08f))
                                else -> listOf(Color.White.copy(alpha = 0.28f), accent.copy(alpha = 0.08f), Color.White.copy(alpha = 0.06f))
                            },
                        ),
                    ),
                )
            }
            when (visuals.treatment) {
                SurfaceTreatment.PAPER -> Row(
                    Modifier.fillMaxSize().padding(if (compact) 13.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TileIcon(icon, accent, compact)
                    Spacer(Modifier.width(14.dp))
                    TileText(title, subtitle, compact, Modifier.weight(1f))
                    Text(index.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                SurfaceTreatment.EDITORIAL -> Column(
                    Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(index ?: "•", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = accent)
                        Spacer(Modifier.weight(1f))
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (compact) 22.dp else 28.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
                    TileText(title, subtitle, compact)
                }
                SurfaceTreatment.GRID -> Column(
                    Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${index ?: "00"} / MODULE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    TileIcon(icon, accent, compact)
                    TileText(title, subtitle, compact)
                }
                SurfaceTreatment.POP -> Box(Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.18f),
                        modifier = Modifier.size(if (compact) 58.dp else 78.dp).align(Alignment.TopEnd),
                    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(if (compact) 25.dp else 32.dp)) } }
                    TileText(title, subtitle, compact, Modifier.align(Alignment.BottomStart).fillMaxWidth(0.82f))
                }
                SurfaceTreatment.NEON, SurfaceTreatment.SMOKE -> Column(
                    Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("> ${index ?: "RUN"}", style = MaterialTheme.typography.labelLarge, color = accent)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = accent, modifier = Modifier.size(if (compact) 22.dp else 27.dp))
                        Spacer(Modifier.width(12.dp))
                        TileText(title, subtitle, compact, Modifier.weight(1f))
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(if (compact) 13.dp else 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    TileIcon(icon, accent, compact)
                    TileText(title, subtitle, compact)
                }
            }
        }
    }
}

@Composable
private fun TileIcon(icon: ImageVector, accent: Color, compact: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier.size(if (compact) 39.dp else 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(if (compact) 21.dp else 25.dp))
        }
    }
}

@Composable
private fun TileText(title: String, subtitle: String, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
        if (!compact || subtitle.length <= 20) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sectionNumber(title: String): Int = when (title) {
    "开始创作" -> 1
    "行业场景" -> 2
    else -> 3
}

private val glassTreatments = setOf(
    SurfaceTreatment.FROST,
    SurfaceTreatment.LIQUID,
    SurfaceTreatment.SMOKE,
    SurfaceTreatment.PRISM,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(printer: BluetoothPrinterManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by printer.state.collectAsState()
    val devices by printer.devices.collectAsState()
    var scanAfterPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    // Runtime permission changes are external to Compose. Keep an explicit revision so returning
    // from either the system dialog or App Settings immediately re-evaluates the permission gate.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val connectionPermissionGranted = remember(permissionRevision) {
        BluetoothPermissions.has(context, false)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val shouldStartScan = scanAfterPermission
        scanAfterPermission = false
        permissionRevision += 1
        permissionDenied = !BluetoothPermissions.has(context, false)
        printer.onPermissionsChanged()
        if (shouldStartScan) {
            // startDiscovery also records a useful state when permission was denied.
            printer.startDiscovery()
        }
    }
    LifecycleResumeEffect(Unit) {
        permissionRevision += 1
        if (BluetoothPermissions.has(context, false)) permissionDenied = false
        printer.onPermissionsChanged()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!connectionPermissionGranted) {
            permissionLauncher.launch(BluetoothPermissions.required(scan = false))
        } else printer.refreshDevices()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("连接打印机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("经典蓝牙 SPP · 优先显示 Qring / BeePrt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭") }
            }
            Spacer(Modifier.height(12.dp))
            if (!connectionPermissionGranted) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("需要附近设备权限", fontWeight = FontWeight.Bold)
                        Text(
                            if (permissionDenied) {
                                "系统仍未授予“附近设备”权限，请在应用权限中允许后返回。"
                            } else {
                                "权限仅用于在本机连接打印机，不上传设备信息。"
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        if (permissionDenied) {
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                            ) { Text("打开应用权限") }
                            OutlinedButton(
                                onClick = { permissionLauncher.launch(BluetoothPermissions.required(false)) },
                            ) { Text("重新请求授权") }
                        } else {
                            Button(onClick = { permissionLauncher.launch(BluetoothPermissions.required(false)) }) {
                                Text("授权")
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (state.phase == ConnectionPhase.SCANNING) {
                            printer.stopDiscovery()
                        } else if (BluetoothPermissions.has(context, true)) {
                            printer.startDiscovery()
                        } else {
                            scanAfterPermission = true
                            permissionLauncher.launch(BluetoothPermissions.required(true))
                        }
                    }) {
                        if (state.phase == ConnectionPhase.SCANNING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, null)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.phase == ConnectionPhase.SCANNING) "停止扫描" else "扫描附近设备")
                    }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Text("系统配对")
                    }
                }
                val statusMessage = state.lastError.ifBlank { state.progressText }
                if (statusMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.lastError.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (devices.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Bluetooth, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("暂未发现设备", fontWeight = FontWeight.SemiBold)
                            Text("先在系统蓝牙中配对，或点击扫描", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(Modifier.height((devices.size.coerceAtMost(5) * 72).dp)) {
                        items(devices, key = { it.address }) { device ->
                            Row(
                                Modifier.fillMaxWidth().clickable { printer.connect(device.address); onDismiss() }.padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (device.likelyQring) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(46.dp),
                                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.BluetoothConnected, null) } }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (device.likelyQring) AssistChip(onClick = {}, label = { Text("推荐") })
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
