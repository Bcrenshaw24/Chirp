package com.example.chirp.spatial

import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** Overlays never resize or replace the GL view, including while the object sheet is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpatialMemoryScreen(
    surface: GLSurfaceView,
    snapshot: SpatialSnapshot,
    ready: Boolean,
    speechReady: Boolean,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onTag: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onSelect: (Int?) -> Unit,
    onDelete: (Int) -> Unit,
    onSpeak: (SavedLocation) -> Unit,
    onLeave: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var showObjects by remember { mutableStateOf(false) }
    val chosen = snapshot.objects.firstOrNull { it.id == selected }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusMaxHeight = maxHeight * 0.42f
        val controlsMaxHeight = maxHeight * 0.30f
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize().semantics {
            contentDescription = "Live AR camera. Aim at an object's supporting surface and use Tag Object, or tap its position in the preview."
        })
        // The reticle is at the GL view center, the exact point used by center hit tests.
        Canvas(Modifier.align(Alignment.Center).size(32.dp).onGloballyPositioned { coordinates ->
            if (Log.isLoggable("ChirpSpatial", Log.DEBUG)) {
                val origin = coordinates.positionInWindow()
                Log.d("ChirpSpatial", "reticleCenterWindow=(${origin.x + coordinates.size.width / 2f}," +
                    "${origin.y + coordinates.size.height / 2f})")
            }
        }.clearAndSetSemantics { }) {
            val radius = size.minDimension * 0.3f
            drawCircle(Color.Black, radius, style = Stroke(5.dp.toPx()))
            drawCircle(Color.White, radius, style = Stroke(2.dp.toPx()))
            drawLine(Color.White, Offset(center.x - 5.dp.toPx(), center.y),
                Offset(center.x + 5.dp.toPx(), center.y), 2.dp.toPx())
            drawLine(Color.White, Offset(center.x, center.y - 5.dp.toPx()),
                Offset(center.x, center.y + 5.dp.toPx()), 2.dp.toPx())
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = statusMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Spatial Memory", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onLeave) { Text("Leave") }
                    }
                    Text(snapshot.status, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    chosen?.let { item ->
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.guidance, style = MaterialTheme.typography.bodyLarge)
                        TextButton(enabled = ready && speechReady, onClick = { onSpeak(item) }) {
                            Text("Speak Location")
                        }
                    }
                    if (!ready) {
                        Button(onClick = onStart) { Text("Start / retry camera and AR") }
                        TextButton(onClick = onSettings) { Text("App permission settings") }
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTag, enabled = ready && !snapshot.naming,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Tag Object") }
                        OutlinedButton(onClick = { showObjects = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Saved Objects (${snapshot.objects.size})")
                        }
                    }
                    Text("Aim the center ring at the object's surface. Pulses indicate proximity, not a clear route.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showObjects) ModalBottomSheet(
        onDismissRequest = { showObjects = false },
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()
            .padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Saved Objects", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showObjects = false }) { Text("Close") }
            }
            Text("This session only. Leaving or restarting clears locations. Positions can drift.",
                style = MaterialTheme.typography.bodyMedium)
            Text(if (SHOW_SPATIAL_MARKERS) "Cyan markers show saved locations. The selected marker is larger and yellow."
                else "AR markers are hidden for this demo. Use distance, speech, and proximity pulses.",
                style = MaterialTheme.typography.bodyMedium)
            if (snapshot.objects.isEmpty()) Text("No saved objects. Close this panel and use Tag Object.")
            snapshot.objects.forEach { item ->
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (item.id == selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (item.id == selected) "${item.name} • Selected" else item.name,
                            style = MaterialTheme.typography.titleMedium)
                        Text(item.guidance)
                        Button(onClick = {
                            selected = item.id; onSelect(item.id); showObjects = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Find ${item.name}") }
                        OutlinedButton(enabled = ready && speechReady, onClick = { onSpeak(item) },
                            modifier = Modifier.fillMaxWidth()) { Text("Speak Location for ${item.name}") }
                        TextButton(onClick = {
                            onDelete(item.id)
                            if (selected == item.id) { selected = null; onSelect(null) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Delete ${item.name}") }
                    }
                }
            }
            if (!speechReady) Text("Spoken feedback unavailable or initializing.")
            TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text("Leave Spatial Memory and clear objects")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (snapshot.naming) AlertDialog(
        onDismissRequest = { onCancel(); name = "" },
        title = { Text("Name this location") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
            label = { Text("Object name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(name.trim()); name = ""
        }) { Text("Save object") } },
        dismissButton = { TextButton(onClick = { onCancel(); name = "" }) { Text("Cancel") } },
    )
}
