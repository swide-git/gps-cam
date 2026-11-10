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
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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

data class CameraStatus(
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoom: Float = 1f,
    /** What was actually bound, e.g. "1920×1080 · 60 fps". */
    val description: String = "",
    /** Wishes the camera could not meet, e.g. "60 fps not available". */
    val note: String? = null,
)

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
    @Volatile private var overlayErrorLogged = false
    private var focusOverridden = false

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val _camera = MutableStateFlow(CameraStatus())
    /** Zoom range and the configuration that was actually bound, for the camera screen. */
    val camera: StateFlow<CameraStatus> = _camera
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null

    /** (Re)binds with the current settings. Ignored while recording: rebinding would end the video. */
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        if (recording != null) return
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // Any failure here must show up on screen, not take the app down.
            try {
                bindNow(future.get(), owner, previewView)
            } catch (e: Throwable) {
                Log.e(TAG, "camera setup failed", e)
                _state.value = RecordingState.Failed(describe(e))
            }
        }, context.mainExecutor)
    }

    private fun bindNow(p: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView) {
        run {
            provider = p
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val settings = AppSettings.state.value
            val info = runCatching { p.getCameraInfo(selector) }.getOrNull()
            val capabilities = info?.let { runCatching { Recorder.getVideoCapabilities(it) }.getOrNull() }
            val quality = when (settings.videoQuality) {
                VideoQuality.HD -> Quality.HD
                VideoQuality.FHD -> Quality.FHD
                VideoQuality.UHD -> Quality.UHD
            }
            val notes = mutableListOf<String>()
            if (capabilities != null && quality !in capabilities.getSupportedQualities(DynamicRange.SDR)) {
                notes += "${settings.videoQuality.label} not supported"
            }
            val sixtyPossible = info?.supportedFrameRateRanges?.any { it.upper >= 60 } ?: false
            val wantedFps = if (settings.frameRate >= 60 && sixtyPossible) 60 else 30
            val stabilization = capabilities?.isStabilizationSupported ?: false
            // Not every camera can do 60 fps or stabilisation at every quality: try the wish, then fall back.
            val attempts = listOf(wantedFps to stabilization, wantedFps to false, 30 to stabilization, 30 to false).distinct()
            var bound: Triple<Camera, VideoCapture<Recorder>, OverlayEffect>? = null
            var lastError: Exception? = null
            var fpsUsed = 30
            for ((fps, stab) in attempts) {
                val overlay = newOverlay()
                try {
                    val preview = Preview.Builder()
                        // 16:9 like the video, so the preview shows the same framing as the recording.
                        .setResolutionSelector(
                            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
                        )
                        .setTargetFrameRate(Range(fps, fps))
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                        .build()
                    val capture = VideoCapture.Builder(recorder)
                        .setVideoStabilizationEnabled(stab)
                        .setTargetFrameRate(Range(fps, fps))
                        .build()
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addEffect(overlay).build()
                    p.unbindAll()
                    bound = Triple(p.bindToLifecycle(owner, selector, group), capture, overlay)
                    fpsUsed = fps
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "bind failed at $fps fps, stabilisation $stab", e)
                    overlay.close()
                    lastError = e
                }
            }
            if (bound == null) {
                _state.value = RecordingState.Failed(lastError?.let { describe(it) } ?: "camera unavailable")
                return
            }
            val (cam, capture, overlay) = bound
            effect?.close()
            effect = overlay
            videoCapture = capture
            boundCamera?.cameraInfo?.zoomState?.removeObservers(owner)
            boundCamera = cam
            // A new camera session starts with default autofocus.
            focusOverridden = false
            if (settings.frameRate >= 60 && fpsUsed < 60) notes += "60 fps not available"
            val resolution = capture.resolutionInfo?.resolution
            val description = listOfNotNull(
                resolution?.let { "${maxOf(it.width, it.height)}×${minOf(it.width, it.height)}" },
                "$fpsUsed fps",
            ).joinToString(" · ")
            _camera.value = _camera.value.copy(description = description, note = notes.joinToString(", ").ifEmpty { null })
            cam.cameraInfo.zoomState.observe(owner) { z ->
                _camera.value = _camera.value.copy(minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio, zoom = z.zoomRatio)
            }
            applyFocus(settings.landscapeFocus)
        }
    }

    private fun newOverlay(): OverlayEffect {
        val overlay = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, overlayHandler) {
            Log.e(TAG, "overlay effect failed", it)
        }
        overlay.setOnDrawListener { frame ->
            // Runs on the overlay thread for every frame: an exception here would kill the app.
            try {
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                renderer.draw(canvas, frame.size.width, frame.size.height, frame.rotationDegrees, GpsTracker.state.value, AppSettings.state.value)
            } catch (e: Throwable) {
                if (!overlayErrorLogged) Log.e(TAG, "overlay drawing failed", e)
                overlayErrorLogged = true
            }
            true
        }
        return overlay
    }

    fun setZoom(ratio: Float) {
        val c = _camera.value
        guarded("zoom") { boundCamera?.cameraControl?.setZoomRatio(ratio.coerceIn(c.minZoom, c.maxZoom)) }
    }

    /** Camera controls are optional extras: a failure is reported as a note, never a crash. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "$what failed", e)
            _camera.value = _camera.value.copy(note = "$what unavailable: ${describe(e)}")
        }
    }

    private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    /** Fixed focus at infinity (landscape through a window), or back to continuous autofocus. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun applyFocus(landscape: Boolean) = guarded("focus") {
        val cam = boundCamera ?: return@guarded
        if (!landscape && !focusOverridden) return@guarded
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (landscape) {
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    .build()
            )
            focusOverridden = true
        } else if (focusOverridden) {
            // Default autofocus needs no Camera2 call at all; only undo what landscape focus set.
            control.clearCaptureRequestOptions()
            focusOverridden = false
        }
    }

    /** Tap to focus: autofocus and metering on the tapped point of the preview. */
    fun focusAt(x: Float, y: Float) = guarded("tap to focus") {
        val cam = boundCamera ?: return@guarded
        val view = previewView ?: return@guarded
        val point = view.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
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
        boundCamera = null
        previewView = null
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
