package com.scanip.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanip.app.ScanUiState
import com.scanip.app.topology.NodeKind
import com.scanip.app.topology.Topology
import com.scanip.app.topology.TreeLayout
import com.scanip.app.ui.theme.OnlineGreen

private val MAP_ICONS = listOf(
    Icons.Filled.Router, Icons.Filled.Lan, Icons.Filled.DeviceHub, Icons.Filled.AccountTree, Icons.Filled.Computer,
    Icons.Filled.Devices, Icons.Filled.PhoneAndroid, Icons.Filled.PhoneIphone, Icons.Filled.Print, Icons.Filled.Speaker,
    Icons.Filled.Storage, Icons.Filled.Tv, Icons.Filled.Videocam,
)

private const val VIRTUAL_ROOT = "__network__"

private class MapModel(
    val topology: Topology,
    val tree: Topology.Tree,
    val root: String,
    val children: Map<String, List<String>>,
    val layout: TreeLayout.Result,
    val nodeW: Float,
    val nodeH: Float,
)

@Composable
fun MapScreen(state: ScanUiState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val topology = state.topology
    if (topology == null || topology.nodes.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state.scanning) "The map is drawn when the scan finishes…"
                else "Run a scan to draw the network map.\n\nTurn on SNMP in the Networks tab to see which " +
                    "switch port each device is plugged into.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val model = remember(topology) {
        val tree = topology.tree()
        val children = tree.children.toMutableMap()
        val root = if (tree.unreachable.isEmpty() && tree.root != null) tree.root else {
            children[VIRTUAL_ROOT] = listOfNotNull(tree.root) + tree.unreachable
            VIRTUAL_ROOT
        }
        val layout = TreeLayout(children)
        MapModel(topology, tree, root, children, layout.layout(root), layout.nodeWidth, layout.nodeHeight)
    }

    val painters = MAP_ICONS.associateWith { rememberVectorPainter(it) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val unit = with(density) { 1.dp.toPx() }
    // The canvas is scaled by dp, so text sizes are given in canvas units, not sp.
    fun fs(v: Float) = (v / (density.density * density.fontScale)).sp
    val colors = MaterialTheme.colorScheme

    var zoom by remember(model) { mutableFloatStateOf(0f) } // 0 = not fitted yet
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun fit() {
        if (canvasSize.width == 0) return
        val contentW = model.layout.width * unit
        val s = (canvasSize.width * 0.94f / contentW).coerceIn(0.25f, 1.2f)
        zoom = s
        offset = Offset((canvasSize.width - contentW * s) / 2f, 16f * unit)
    }

    LaunchedEffect(model, canvasSize) { if (zoom == 0f) fit() }

    val currentModel by rememberUpdatedState(model)
    val currentOpen by rememberUpdatedState(onOpen)

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(model) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        if (zoom <= 0f) return@detectTransformGestures
                        val newZoom = (zoom * gestureZoom).coerceIn(0.15f, 3f)
                        offset = centroid - (centroid - offset) * (newZoom / zoom) + pan
                        zoom = newZoom
                    }
                }
                .pointerInput(model) {
                    detectTapGestures { pos ->
                        val m = currentModel
                        if (zoom <= 0f) return@detectTapGestures
                        val world = (pos - offset) / (zoom * unit)
                        val hit = m.layout.boxes.entries.firstOrNull { (_, b) ->
                            world.x in b.x..(b.x + m.nodeW) && world.y in b.y..(b.y + m.nodeH)
                        }?.key
                        m.topology.nodes[hit]?.deviceIp?.let(currentOpen)
                    }
                },
        ) {
            if (zoom == 0f) return@Canvas
            withTransform({
                translate(offset.x, offset.y)
                scale(zoom * unit, zoom * unit, pivot = Offset.Zero)
            }) {
                val w = model.nodeW
                val h = model.nodeH
                val lineColor = colors.outline
                // Edges
                for ((parent, kids) in model.children) {
                    val pb = model.layout.boxes[parent] ?: continue
                    val px = pb.x + w / 2
                    val pBottom = pb.y + h
                    for (child in kids) {
                        val cb = model.layout.boxes[child] ?: continue
                        val cx = cb.x + w / 2
                        val firstRowTop = cb.y
                        val midY = pBottom + 28f
                        val dashed = parent == VIRTUAL_ROOT || model.tree.viaLink[child]?.inferred == true
                        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null
                        drawLine(lineColor, Offset(px, pBottom), Offset(px, midY), 1.5f, pathEffect = effect)
                        drawLine(lineColor, Offset(px, midY), Offset(cx, midY), 1.5f, pathEffect = effect)
                        drawLine(lineColor, Offset(cx, midY), Offset(cx, firstRowTop), 1.5f, pathEffect = effect)
                        model.tree.portLabel(parent, child)?.let { label ->
                            drawText(
                                textMeasurer, label,
                                topLeft = Offset(cx + 3f, cb.y - 14f),
                                style = TextStyle(fontSize = fs(9f), color = colors.primary),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                size = Size(w - 3f, 14f),
                            )
                        }
                    }
                }
                // Nodes
                for ((id, box) in model.layout.boxes) {
                    if (id == VIRTUAL_ROOT) continue
                    val node = model.topology.nodes[id] ?: continue
                    val device = node.deviceIp?.let { state.devices[it] }
                    val accent = when (node.kind) {
                        NodeKind.ROUTER -> colors.tertiary
                        NodeKind.SWITCH -> colors.primary
                        NodeKind.SUBNET -> colors.secondary
                        NodeKind.UNMANAGED -> colors.outline
                        NodeKind.DEVICE -> colors.outlineVariant
                    }
                    translate(box.x, box.y) {
                        drawRoundRect(colors.surfaceContainerHigh, size = Size(w, h), cornerRadius = CornerRadius(10f))
                        drawRoundRect(accent, size = Size(w, h), cornerRadius = CornerRadius(10f), style = Stroke(if (node.kind == NodeKind.DEVICE) 1f else 2f))
                        val icon: ImageVector = when (node.kind) {
                            NodeKind.ROUTER -> Icons.Filled.Router
                            NodeKind.SWITCH -> Icons.Filled.Lan
                            NodeKind.UNMANAGED -> Icons.Filled.DeviceHub
                            NodeKind.SUBNET -> Icons.Filled.AccountTree
                            NodeKind.DEVICE -> device?.let(::deviceIcon) ?: Icons.Filled.Devices
                        }
                        val painter = painters[icon] ?: painters.getValue(Icons.Filled.Devices)
                        translate(8f, (h - 22f) / 2) {
                            with(painter) { draw(Size(22f, 22f), colorFilter = ColorFilter.tint(if (node.kind == NodeKind.DEVICE) colors.onSurfaceVariant else accent)) }
                        }
                        if (device != null) {
                            drawCircle(if (device.isAlive) OnlineGreen else colors.outline, radius = 4f, center = Offset(w - 9f, 9f))
                        }
                        drawText(
                            textMeasurer, node.label,
                            topLeft = Offset(36f, 8f),
                            style = TextStyle(fontSize = fs(11f), fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, size = Size(w - 50f, 18f),
                        )
                        val sub = listOfNotNull(node.sublabel, device?.manufacturer).joinToString(" · ").ifEmpty { null }
                        sub?.let {
                            drawText(
                                textMeasurer, it,
                                topLeft = Offset(36f, 26f),
                                style = TextStyle(fontSize = fs(9f), color = colors.onSurfaceVariant),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, size = Size(w - 42f, 28f),
                            )
                        }
                    }
                }
            }
        }

        val switches = model.topology.nodes.values.count { it.kind == NodeKind.SWITCH }
        val onPorts = model.topology.attachments.size
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$switches managed switch(es) · $onPorts device(s) placed on ports",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (switches == 0) {
                Text(
                    "No switch answered SNMP, so devices are grouped by subnet only. Dashed lines are inferred links.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        SmallFloatingActionButton(
            onClick = { fit() },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        ) { Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Fit map to screen") }
    }
}

