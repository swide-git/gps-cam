package io.github.swidegit.gpscam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val startedAtMs: Long) : RecordingState
    data class Saved(val name: String, val subtitles: Boolean) : RecordingState
    data class Failed(val message: String) : RecordingState
}

/**
 * Back camera with the telemetry overlay drawn into every frame by a CameraX OverlayEffect, so the
 * preview shows exactly what ends up in the video. A per-second subtitle track (.srt) is written next
 * to it in Documents/GPSCam (MediaStore does not accept non-video files under Movies/).
 */
class CameraSession(private val context: Context) {
    private val overlayThread = HandlerThread("overlay").apply { start() }
    private val overlayHandler = Handler(overlayThread.looper)
    private val renderer = OverlayRenderer()
    private var provider: ProcessCameraProvider? = null
    private var effect: OverlayEffect? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val subtitles = mutableListOf<SrtEntry>()
    private var recordingStartMs = 0L

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                .build()
            val stabilization = runCatching {
                Recorder.getVideoCapabilities(p.getCameraInfo(selector)).isStabilizationSupported
            }.getOrDefault(false)
            val capture = VideoCapture.Builder(recorder).setVideoStabilizationEnabled(stabilization).build()
            val overlay = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, overlayHandler) {
                Log.e(TAG, "overlay effect failed", it)
            }
            overlay.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                renderer.draw(canvas, frame.size.width, frame.size.height, frame.rotationDegrees, GpsTracker.state.value, AppSettings.state.value)
                true
            }
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addEffect(overlay).build()
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, group)
                effect?.close()
                effect = overlay
                videoCapture = capture
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                overlay.close()
                _state.value = RecordingState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }, context.mainExecutor)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked right before withAudioEnabled().
    fun startRecording() {
        val capture = videoCapture ?: return
        if (recording != null) return
        val name = "GPSCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/GPSCam")
        }
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = capture.output.prepareRecording(context, output)
        if (AppSettings.state.value.recordAudio &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        synchronized(subtitles) { subtitles.clear() }
        recording = pending.start(context.mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartMs = SystemClock.elapsedRealtime()
                    _state.value = RecordingState.Recording(recordingStartMs)
                    overlayHandler.post(sampler)
                }
                is VideoRecordEvent.Finalize -> {
                    overlayHandler.removeCallbacks(sampler)
                    recording = null
                    val error = event.error
                    // ERROR_SOURCE_INACTIVE and similar still leave a playable file; only a missing output is fatal.
                    if (error != VideoRecordEvent.Finalize.ERROR_NONE && event.outputResults.outputUri == android.net.Uri.EMPTY) {
                        _state.value = RecordingState.Failed(event.cause?.message ?: "error $error")
                    } else {
                        val srt = writeSubtitles(name)
                        _state.value = RecordingState.Saved(name, srt)
                    }
                }
                else -> Unit
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun release() {
        recording?.stop()
        recording = null
        provider?.unbindAll()
        effect?.close()
        effect = null
        overlayThread.quitSafely()
    }

    /** One subtitle per second with the same values the overlay shows. */
    private val sampler = object : Runnable {
        override fun run() {
            val offset = SystemClock.elapsedRealtime() - recordingStartMs
            val gps = GpsTracker.state.value
            val unit = AppSettings.state.value.unit
            val fix = gps.fix
            val text = buildString {
                append(fix?.speedMs?.let { "${Format.speed(it, unit)} ${unit.label}" } ?: "-- ${unit.label}")
                append("  SAT ${gps.usedInFix}/${gps.satellites.size}")
                fix?.accuracyM?.let { append("  ±${it.toInt()} m") }
                if (fix != null) append('\n').append(Format.coordinates(fix.lat, fix.lon))
            }
            synchronized(subtitles) {
                val start = (offset / 1000) * 1000
                subtitles += SrtEntry(start, start + 1000, text)
            }
            overlayHandler.postDelayed(this, 1000 - offset % 1000)
        }
    }

    private fun writeSubtitles(name: String): Boolean {
        val text = synchronized(subtitles) { Srt.format(subtitles) }
        if (text.isEmpty()) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.srt")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/GPSCam")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        }.onFailure { Log.e(TAG, "subtitles not saved", it) }.isSuccess
    }

    companion object {
        private const val TAG = "CameraSession"
    }
}
