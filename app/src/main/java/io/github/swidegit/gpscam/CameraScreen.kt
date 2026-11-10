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
    val now = rememberNow()

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    session.bind(owner, this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
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
            val recording = current is RecordingState.Recording
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .background(Palette.bad, if (recording) RoundedCornerShape(8.dp) else CircleShape)
                    .clickable { if (recording) session.stopRecording() else session.startRecording() },
            )
        }
    }
}
