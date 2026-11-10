package io.github.swidegit.gpscam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private var hasLocation by mutableStateOf(false)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
        if (hasLocation) GpsTracker.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        enableEdgeToEdge()
        refreshPermission()
        if (!hasLocation && savedInstanceState == null) requestLocation()
        setContent { GpsCamTheme { App(hasLocation, ::requestLocation) } }
    }

    override fun onStart() {
        super.onStart()
        refreshPermission()
        if (hasLocation) GpsTracker.start(this)
    }

    // GPS runs only while the app is on screen: no background work, no notification.
    override fun onStop() {
        super.onStop()
        GpsTracker.stop()
    }

    private fun refreshPermission() {
        hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocation() {
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private const val RELEASES_URL = "https://github.com/swide-git/gps-cam/releases/latest"

private enum class Tab(val label: Int, val icon: ImageVector) {
    SPEED(R.string.tab_speed, AppIcons.Speed),
    SATELLITES(R.string.tab_satellites, AppIcons.GpsFixed),
    CAMERA(R.string.tab_camera, AppIcons.Videocam),
    TRIPS(R.string.tab_trips, AppIcons.History),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(hasLocation: Boolean, requestLocation: () -> Unit) {
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val gps by GpsTracker.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.SPEED) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val view = LocalView.current
    SideEffect { view.keepScreenOn = settings.keepScreenOn }
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Scaffold(
        topBar = {
            if (tab != Tab.CAMERA) {
                TopAppBar(
                    title = { Text(stringResource(tab.label)) },
                    actions = {
                        // No update check: the app has no internet permission. The browser shows what is new.
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
                        }) { Text("v$appVersion") }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            !hasLocation -> LocationPermissionScreen(modifier, requestLocation)
            tab == Tab.SPEED -> SpeedScreen(gps, settings, modifier)
            tab == Tab.SATELLITES -> SatellitesScreen(gps, modifier)
            tab == Tab.CAMERA -> CameraScreen(modifier, onOpenSettings = { showSettings = true })
            tab == Tab.TRIPS -> TripsScreen(gps, settings, modifier)
        }
    }

    if (showSettings) SettingsDialog(settings) { showSettings = false }
}

@Composable
private fun LocationPermissionScreen(modifier: Modifier, requestLocation: () -> Unit) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.permission_location_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.permission_location_body), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = requestLocation) { Text(stringResource(R.string.permission_grant)) }
    }
}

@Composable
fun GpsDisabledNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.gps_disabled), color = Palette.bad, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            context.startActivity(Intent(SystemSettings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(stringResource(R.string.open_settings)) }
    }
}

@Composable
private fun SettingsDialog(settings: Settings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_unit), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(SpeedUnit.entries, settings.unit, { it.label }) { u -> AppSettings.update { it.copy(unit = u) } }

                SectionTitle(R.string.settings_video)
                ChoiceRow(VideoQuality.entries, settings.videoQuality, { it.label }) { q -> AppSettings.update { it.copy(videoQuality = q) } }
                ChoiceRow(listOf(30, 60), settings.frameRate, { "$it fps" }) { f -> AppSettings.update { it.copy(frameRate = f) } }
                SettingSwitch(R.string.settings_landscape_focus, R.string.settings_landscape_focus_hint, settings.landscapeFocus) { v ->
                    AppSettings.update { it.copy(landscapeFocus = v) }
                }
                SettingSwitch(R.string.settings_audio, null, settings.recordAudio) { v ->
                    AppSettings.update { it.copy(recordAudio = v) }
                }

                SectionTitle(R.string.settings_overlay)
                Text(stringResource(R.string.settings_overlay_position), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                ChoiceRow(OverlayCorner.entries, settings.overlayCorner, { it.label }) { c -> AppSettings.update { it.copy(overlayCorner = c) } }
                Text(stringResource(R.string.settings_overlay_size), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                ChoiceRow(OverlaySize.entries, settings.overlaySize, { it.label }) { z -> AppSettings.update { it.copy(overlaySize = z) } }
                Text(stringResource(R.string.settings_overlay_opacity), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                ChoiceRow(listOf(0, 30, 60, 85), settings.overlayOpacity, { "$it%" }) { o -> AppSettings.update { it.copy(overlayOpacity = o) } }
                SettingSwitch(R.string.settings_overlay_sats, null, settings.overlaySatellites) { v ->
                    AppSettings.update { it.copy(overlaySatellites = v) }
                }
                SettingSwitch(R.string.settings_overlay_coords, R.string.settings_overlay_coords_hint, settings.overlayCoordinates) { v ->
                    AppSettings.update { it.copy(overlayCoordinates = v) }
                }

                SectionTitle(R.string.settings_general)
                SettingSwitch(R.string.settings_screen_on, null, settings.keepScreenOn) { v ->
                    AppSettings.update { it.copy(keepScreenOn = v) }
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = Palette.accent, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SettingSwitch(title: Int, hint: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title))
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
