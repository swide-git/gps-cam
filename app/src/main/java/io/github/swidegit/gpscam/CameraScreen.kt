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

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures on top of the preview: pinch to zoom, tap to focus (leaves landscape focus).
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
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp)) {
            Text(camera.description, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.chip())
            camera.note?.let { Text(it, color = Palette.warn, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp).chip()) }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White)
        }
        val current = state
        if (current is RecordingState.Recording) {
            Text(
                "● " + Format.duration(now - current.startedAtMs),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp)
                    .background(Palette.bad, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val message = when (current) {
                is RecordingState.Saved -> stringResource(R.string.camera_saved, current.name) +
                    if (current.subtitles) "\n" + stringResource(R.string.camera_saved_srt) else ""
                is RecordingState.Failed -> stringResource(R.string.camera_failed, current.message)
                else -> null
            }
            if (message != null) {
                Text(
                    message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                zoomPresets(camera.minZoom, camera.maxZoom).forEach { z ->
                    LensChip(
                        label = if (z < 1f) "${"%.1f".format(Locale.US, z)}×" else "${z.toInt()}×",
                        selected = kotlin.math.abs(camera.zoom - z) < 0.05f,
                    ) { session.setZoom(z) }
                }
                LensChip(label = "∞", selected = settings.landscapeFocus) {
                    AppSettings.update { it.copy(landscapeFocus = !it.landscapeFocus) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val recording = current is RecordingState.Recording
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .background(Palette.bad, if (recording) RoundedCornerShape(8.dp) else CircleShape)
                    .clickable { if (recording) session.stopRecording() else session.startRecording() },
            )
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
        Modifier.size(44.dp)
            .background(if (selected) Palette.accent else Color(0x99000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
