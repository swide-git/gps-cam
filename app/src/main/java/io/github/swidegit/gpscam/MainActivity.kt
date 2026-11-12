package io.github.swidegit.gpscam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.KeyEvent
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.platform.LocalConfiguration
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

    // Both directions: consuming only the press would still let the release change the volume.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = VolumeKeys.onKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = VolumeKeys.onKey(event) || super.onKeyUp(keyCode, event)

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

    if (showSettings) {
        BackHandler { showSettings = false }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                        }
                    },
                )
            },
        ) { padding -> SettingsScreen(settings, Modifier.padding(padding).fillMaxSize()) }
        return
    }

    // Landscape: a navigation rail on the side instead of the top and bottom bars, so the speed
    // and the camera preview get the full height.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        topBar = {
            if (tab != Tab.CAMERA && !landscape) {
                TopAppBar(
                    title = { Text("GPS Cam", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        // As in Second Display, but no update check: the app has no internet permission.
                        FilledTonalButton(onClick = {
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
            if (!landscape) {
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
            }
        },
    ) { padding ->
        // Landscape: the menu stays at the edge of the phone where it was in portrait (the bottom
        // edge turns into the right edge when the phone is turned to the left), as a rail whose
        // labels read horizontally under the icons.
        val railAtEnd = rememberMenuAtEnd()
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (landscape && !railAtEnd) MenuRail(tab, { tab = it }, { showSettings = true })
            val modifier = Modifier.weight(1f).fillMaxHeight()
            when {
                !hasLocation -> LocationPermissionScreen(modifier, requestLocation)
                tab == Tab.SPEED -> SpeedScreen(gps, settings, modifier)
                tab == Tab.SATELLITES -> SatellitesScreen(gps, modifier)
                tab == Tab.CAMERA -> CameraScreen(modifier, onOpenSettings = { showSettings = true })
                tab == Tab.TRIPS -> TripsScreen(gps, settings, modifier)
            }
            if (landscape && railAtEnd) MenuRail(tab, { tab = it }, { showSettings = true })
        }
    }
}

/** True when the portrait bottom edge is on the right: phone turned to the left (ROTATION_90). */
@Composable
fun rememberMenuAtEnd(): Boolean = rememberDisplayRotation() != Surface.ROTATION_270

@Composable
private fun MenuRail(tab: Tab, onSelect: (Tab) -> Unit, onSettings: () -> Unit) {
    NavigationRail(
        header = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        Tab.entries.forEach { t ->
            NavigationRailItem(
                selected = tab == t,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(stringResource(t.label)) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
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

/** Settings page in the Second Display style: one rounded card per section. */
@Composable
private fun SettingsScreen(settings: Settings, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard(R.string.settings_unit) {
            ChoiceRow(SpeedUnit.entries, settings.unit, { it.label }) { u -> AppSettings.update { it.copy(unit = u) } }
        }
        SettingsCard(R.string.settings_video) {
            ChoiceRow(VideoQuality.entries, settings.videoQuality, { it.label }) { q -> AppSettings.update { it.copy(videoQuality = q) } }
            ChoiceRow(listOf(30, 60), settings.frameRate, { "$it fps" }) { f -> AppSettings.update { it.copy(frameRate = f) } }
            SettingSwitch(R.string.settings_landscape_focus, R.string.settings_landscape_focus_hint, settings.landscapeFocus) { v ->
                AppSettings.update { it.copy(landscapeFocus = v) }
            }
            SettingSwitch(R.string.settings_audio, null, settings.recordAudio) { v -> AppSettings.update { it.copy(recordAudio = v) } }
            SettingSwitch(R.string.settings_volume_keys, R.string.settings_volume_keys_hint, settings.volumeKeys) { v ->
                AppSettings.update { it.copy(volumeKeys = v) }
            }
        }
        SettingsCard(R.string.settings_overlay) {
            SubLabel(R.string.settings_overlay_position)
            ChoiceRow(OverlayCorner.entries, settings.overlayCorner, { it.label }) { c -> AppSettings.update { it.copy(overlayCorner = c) } }
            SubLabel(R.string.settings_overlay_size)
            ChoiceRow(OverlaySize.entries, settings.overlaySize, { it.label }) { z -> AppSettings.update { it.copy(overlaySize = z) } }
            SubLabel(R.string.settings_overlay_opacity)
            ChoiceRow(listOf(0, 30, 60, 85), settings.overlayOpacity, { "$it%" }) { o -> AppSettings.update { it.copy(overlayOpacity = o) } }
            SettingSwitch(R.string.settings_overlay_chart, R.string.settings_overlay_chart_hint, settings.overlayChart) { v ->
                AppSettings.update { it.copy(overlayChart = v) }
            }
            SettingSwitch(R.string.settings_overlay_sats, null, settings.overlaySatellites) { v ->
                AppSettings.update { it.copy(overlaySatellites = v) }
            }
            SettingSwitch(R.string.settings_overlay_altitude, null, settings.overlayAltitude) { v ->
                AppSettings.update { it.copy(overlayAltitude = v) }
            }
            SettingSwitch(R.string.settings_overlay_heading, null, settings.overlayHeading) { v ->
                AppSettings.update { it.copy(overlayHeading = v) }
            }
            SettingSwitch(R.string.settings_overlay_distance, null, settings.overlayDistance) { v ->
                AppSettings.update { it.copy(overlayDistance = v) }
            }
            SettingSwitch(R.string.settings_overlay_coords, R.string.settings_overlay_coords_hint, settings.overlayCoordinates) { v ->
                AppSettings.update { it.copy(overlayCoordinates = v) }
            }
        }
        SettingsCard(R.string.settings_general) {
            SettingSwitch(R.string.settings_auto_trip, R.string.settings_auto_trip_hint, settings.autoTrip) { v ->
                AppSettings.update { it.copy(autoTrip = v) }
            }
            SettingSwitch(R.string.settings_screen_on, null, settings.keepScreenOn) { v -> AppSettings.update { it.copy(keepScreenOn = v) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsCard(title: Int, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun SubLabel(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Palette.accent, modifier = Modifier.padding(bottom = 4.dp))
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
