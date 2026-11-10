package io.github.swidegit.gpscam

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    fun granted() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasCamera = granted() }

    if (!hasCamera) {
        Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.camera_permission_body), textAlign = TextAlign.Center)
            Button(onClick = { request.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.permission_grant))
            }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val session = remember { CameraSession(context.applicationContext) }
    DisposableEffect(session) { onDispose { session.release() } }
    val state by session.state.collectAsStateWithLifecycle()
    val camera by session.camera.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Quality and frame rate need a new camera session; focus mode applies to the running one.
    LaunchedEffect(previewView, settings.videoQuality, settings.frameRate) {
        previewView?.let { session.bind(owner, it) }
    }
    LaunchedEffect(settings.landscapeFocus) { session.applyFocus(settings.landscapeFocus) }

    // The preview shows exactly the recorded frame, so nothing is drawn on top of it: the overlay
    // can sit in any corner without being hidden by buttons. All controls live in the panel below.
    Column(modifier.background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Gestures on the preview: pinch to zoom, tap to focus (leaves landscape focus).
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> session.setZoom(session.camera.value.zoom * zoom) }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (AppSettings.state.value.landscapeFocus) AppSettings.update { it.copy(landscapeFocus = false) }
                            session.focusAt(offset.x, offset.y)
                        }
                    },
            )
        }

        val current = state
        val recording = current is RecordingState.Recording
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status line: recording time, or the last result, or what the camera is set to.
            val (status, statusColor) = when (current) {
                is RecordingState.Recording -> "● " + Format.duration(now - current.startedAtMs) to Palette.record
                is RecordingState.Saved -> stringResource(R.string.camera_saved, current.name) +
                    (if (current.subtitles) "\n" + stringResource(R.string.camera_saved_srt) else "") to Color.White
                is RecordingState.Failed -> stringResource(R.string.camera_failed, current.message) to Palette.overlayWarn
                RecordingState.Idle -> listOfNotNull(camera.description, camera.note).filter { it.isNotEmpty() }.joinToString(" · ") to Palette.overlayMuted
            }
            Text(
                status,
                color = statusColor,
                fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                zoomPresets(camera.minZoom, camera.maxZoom).forEach { z ->
                    LensChip(
                        label = if (z < 1f) "${"%.1f".format(Locale.US, z)}×" else "${z.toInt()}×",
                        selected = kotlin.math.abs(camera.zoom - z) < 0.05f,
                    ) { session.setZoom(z) }
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(64.dp).border(3.dp, Color.White, CircleShape).padding(6.dp)
                        .background(Palette.record, if (recording) RoundedCornerShape(8.dp) else CircleShape)
                        .clickable { if (recording) session.stopRecording() else session.startRecording() },
                )
                Spacer(Modifier.width(4.dp))
                LensChip(label = "∞", selected = settings.landscapeFocus) {
                    AppSettings.update { it.copy(landscapeFocus = !it.landscapeFocus) }
                }
                Box(
                    Modifier.size(40.dp).background(Color(0xFF2A2A2A), CircleShape).clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** 0.5× (or the widest the camera has) when an ultra-wide lens exists, then 1×, 2×, 5× within range. */
private fun zoomPresets(min: Float, max: Float): List<Float> =
    (listOfNotNull(if (min < 0.95f) maxOf(min, 0.5f) else null) + listOf(1f, 2f, 5f).filter { it <= max + 0.01f }).distinct()

private fun Modifier.chip() = background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .background(if (selected) Palette.overlayAccent else Color(0xFF2A2A2A), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
