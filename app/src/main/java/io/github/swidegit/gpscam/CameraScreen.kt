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
import android.view.Surface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
    val photo by session.photo.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Quality and frame rate need a new camera session; focus mode applies to the running one.
    LaunchedEffect(previewView, settings.videoQuality, settings.frameRate) {
        previewView?.let { session.bind(owner, it) }
    }
    LaunchedEffect(settings.landscapeFocus) { session.applyFocus(settings.landscapeFocus) }

    // The activity is not recreated on rotation: hand the new display rotation to the camera.
    val rotation = rememberDisplayRotation()
    LaunchedEffect(rotation) { session.setRotation(rotation) }

    fun toggleRecording() {
        if (session.state.value is RecordingState.Recording) session.stopRecording() else session.startRecording()
    }
    DisposableEffect(session) {
        VolumeKeys.handler = { up -> if (up) toggleRecording() else session.takePhoto() }
        onDispose { VolumeKeys.handler = null }
    }

    // The latest event wins the status line: a saved video, a photo, or an error.
    var message by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val current = state
    val savedText = (current as? RecordingState.Saved)?.let {
        stringResource(R.string.camera_saved, it.name) + if (it.subtitles) "\n" + stringResource(R.string.camera_saved_srt) else ""
    }
    val failedText = (current as? RecordingState.Failed)?.let { stringResource(R.string.camera_failed, it.message) }
    LaunchedEffect(current) {
        when {
            savedText != null -> message = savedText to Color.White
            failedText != null -> message = failedText to Palette.overlayWarn
        }
    }
    val photoSaved = photo?.takeUnless { it.startsWith("error") }?.let { stringResource(R.string.photo_saved, it) }
    val photoFailed = photo?.takeIf { it.startsWith("error") }?.let { stringResource(R.string.photo_failed, it.removePrefix("error: ")) }
    LaunchedEffect(photo) {
        when {
            photoSaved != null -> message = photoSaved to Color.White
            photoFailed != null -> message = photoFailed to Palette.overlayWarn
        }
    }
    val recording = current is RecordingState.Recording
    val (status, statusColor) = when {
        current is RecordingState.Recording -> "● " + Format.duration(now - current.startedAtMs) to Palette.record
        message != null -> message!!
        else -> listOfNotNull(camera.description, camera.note).filter { it.isNotEmpty() }.joinToString(" · ") to Palette.overlayMuted
    }

    val zoomChips: @Composable () -> Unit = {
        zoomPresets(camera.minZoom, camera.maxZoom).forEach { z ->
            LensChip(
                label = if (z < 1f) "${"%.1f".format(Locale.US, z)}×" else "${z.toInt()}×",
                selected = kotlin.math.abs(camera.zoom - z) < 0.05f,
                description = stringResource(R.string.zoom_to, z),
            ) { session.setZoom(z) }
        }
    }
    val recordButton: @Composable () -> Unit = {
        val description = stringResource(if (recording) R.string.stop_recording else R.string.start_recording)
        Box(
            Modifier.size(64.dp).border(3.dp, Color.White, CircleShape).padding(6.dp)
                .background(Palette.record, if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .clickable(onClickLabel = description, role = Role.Button) { toggleRecording() }
                .semantics { contentDescription = description },
        )
    }
    val actions: @Composable () -> Unit = {
        RoundButton(onClick = { session.takePhoto() }) {
            Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.take_photo), tint = Color.White, modifier = Modifier.size(22.dp))
        }
        LensChip(label = "∞", selected = settings.landscapeFocus, description = stringResource(R.string.settings_landscape_focus)) {
            AppSettings.update { it.copy(landscapeFocus = !it.landscapeFocus) }
        }
        RoundButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
    val statusText: @Composable (Modifier) -> Unit = { m ->
        Text(
            status,
            color = statusColor,
            fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = m,
        )
    }

    // The preview shows exactly the recorded frame, so nothing is drawn on top of it: the overlay
    // can sit in any corner without being hidden by buttons. Controls live beside or below it.
    BoxWithConstraints(modifier.background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val preview: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
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
        }
        if (landscape) {
            // The control panel sits on the same edge as the menu (the portrait bottom edge).
            val panelAtEnd = rotation != Surface.ROTATION_270
            Row(Modifier.fillMaxSize()) {
                if (panelAtEnd) preview(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(132.dp).fillMaxHeight().padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    statusText(Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { zoomChips() }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            recordButton()
                            actions()
                        }
                    }
                }
                if (!panelAtEnd) preview(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxWidth())
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    statusText(Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        zoomChips()
                        Spacer(Modifier.width(4.dp))
                        recordButton()
                        Spacer(Modifier.width(4.dp))
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).background(Color(0xFF2A2A2A), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 0.5× (or the widest the camera has) when an ultra-wide lens exists, then 1×, 2×, 5× within range. */
private fun zoomPresets(min: Float, max: Float): List<Float> =
    (listOfNotNull(if (min < 0.95f) maxOf(min, 0.5f) else null) + listOf(1f, 2f, 5f).filter { it <= max + 0.01f }).distinct()

private fun Modifier.chip() = background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)

@Composable
private fun LensChip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .background(if (selected) Palette.overlayAccent else Color(0xFF2A2A2A), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
