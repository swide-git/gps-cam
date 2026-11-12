<div align="center">
  <h1>GPS Cam</h1>
  <p><strong>Real speed straight from the GPS receiver, the satellites behind it, and video with the speed burned in.</strong></p>
  <p>Speedometer, satellite view and speed-overlay camera for Android 10+, built for fast trains.</p>
  <p><a href="https://github.com/swide-git/gps-cam/releases/latest"><strong>⬇ Download the latest release (APK)</strong></a> · <a href="https://github.com/swide-git/gps-cam/releases">All releases</a> · <a href="https://swide.pl/gps-cam/">Privacy policy</a></p>
</div>

---

Android app that shows your real speed straight from the GPS receiver, the satellites it uses and
their signal strength, and records video with the speed burned into the picture.

Built for fast trains (Shinkansen, TGV, ICE), where Google Maps and Waze fall short: they use the
fused location provider, snap the position to roads and smooth the speed, so on a train at
300 km/h they freeze, jump or show nothing. GPS Cam reads the raw `GPS_PROVIDER`, whose Doppler
speed stays accurate at any speed the receiver can track.

## Features

- **Speed**: km/h, mph, m/s or knots (tap the unit to switch), speed accuracy, fix type (2D/3D),
  position accuracy, last-fix age (tunnels), altitude, heading.
- **Trip statistics and chart**: maximum and average moving speed, distance, moving time, trip
  time, and a speed-over-time chart (10 min, 1 h, whole trip). Jitter at a station does not add
  distance, and the maximum ignores glitches: it needs a good position, a confident Doppler speed
  and an acceleration a vehicle can reach (at most about 1.1 g).
- **Acceleration**: 0-100 and 100-200 km/h (0-60 and 60-130 mph with the mph unit) and the
  quarter mile with trap speed, timed from the GPS Doppler speed with crossings interpolated
  between fixes. Last and best times on the Speed screen; the running timer and the result also
  appear in the video overlay.
- **Trips**: "End trip" saves the trip (track and summary) in the app; tap a trip for its speed
  and altitude charts. Any trip can be exported
  as GPX or CSV to `Documents/GPSCam` and shared, e.g. to view the route in Google Earth or Strava. GPS runs only while the app is on screen: no background work, no notification.
- **Satellites**: sky plot, per-satellite C/N0 signal bars, constellation (GPS, GLONASS, Galileo,
  BeiDou, QZSS, NavIC, SBAS), band (L1/E1/B1, L5/E5a/B2a, ...), used-in-fix, time to first fix,
  PDOP/HDOP/VDOP from NMEA. Four views:
  - *Now*: sky plot, signal bars, and a table per system and band with a dual-frequency (L1 + L5)
    indicator.
  - *Sky map*: average signal per direction of the sky since the last reset, which shows the side
    and height the signal gets in through a train window.
  - *History*: satellites, top-4 signal and accuracy over the trip, a quality summary (time with a
    3D fix, longest gap, averages) and the time-to-first-fix history.
  - *Receiver*: receiver gain (AGC) per band from raw GNSS measurements with an interference
    warning, multipath-flagged signals, the phone clock offset from GPS time, GPS time uncertainty
    and leap seconds.
- **Camera**: back camera, 720p / 1080p / 4K at 30 or 60 fps (falling back when the phone cannot),
  stabilisation where supported, zoom presets (0.5x on phones with an ultra-wide lens, 1x, 2x, 5x)
  and pinch to zoom, tap to focus, and "landscape focus" fixed at infinity so autofocus does not
  lock onto a train window. The telemetry box (speed, max, satellites, accuracy, time, optionally
  coordinates; optionally heading, trip distance and a one-minute speed chart) is drawn into each
  frame with a CameraX `OverlayEffect`, so the preview shows exactly what the video contains; its
  corner, size and background opacity are configurable. Controls sit beside or below the preview,
  never on it. Photos (up to 4K, 16:9) get the same box and go to `Pictures/GPSCam`. Volume up
  starts or stops recording, volume down takes a photo.
- **Landscape**: a navigation rail replaces the bars; the Speed screen shows the big number beside
  the tiles, and the camera puts its controls on the side. Videos go to
  `Movies/GPSCam`; a per-second telemetry subtitle file (`.srt`) goes to `Documents/GPSCam`.

Nothing leaves the phone: no internet permission, no accounts, no analytics.

The version in the top bar opens the latest release on GitHub in the browser. The app has no
automatic update check, because it has no internet permission.

## Requirements

Android 10 (API 29) or newer with a GPS receiver. Dual-frequency phones (L1 + L5) give noticeably
better results next to tall buildings and inside trains.

## Tips for trains

- Hold the phone against the window. Coated glass and the metal body of the carriage block most
  of the signal; in the middle of the carriage there is often no fix at all.
- The first fix can take up to a minute. After that, short tunnels are bridged: the speed turns
  grey and the "last fix" counter shows how old it is.

## Build

```
scripts/build.sh            # unit tests + debug APK -> dist/gps-cam.apk
```

Needs JDK 17 and the Android SDK (platform 37). The script picks the Homebrew paths by default.

## Releases

Every merge to `main` publishes a signed APK (sideloading) and App Bundle (`gps-cam.aab`, for
Google Play) on GitHub Releases (`.github/workflows/release.yml`):
the patch version is bumped automatically, `#minor` / `#major` in the merge message bump those,
`[skip release]` builds and tests only. Publishing requires four repository secrets:
`GPS_CAM_KEYSTORE_BASE64`, `GPS_CAM_KEYSTORE_PASSWORD`, `GPS_CAM_KEY_ALIAS`, `GPS_CAM_KEY_PASSWORD`.

## Google Play

Listing texts, policy form answers and graphics: [docs/play-store.md](docs/play-store.md).

## Layout

```
app/src/main/java/io/github/swidegit/gpscam/
    GpsTracker.kt        raw GPS: location, GnssStatus, NMEA (GSA) -> StateFlow<GpsState>
    TripStats.kt         distance, max (spike filter), average, moving time (pure, unit-tested)
    Track.kt             track points, GPX / CSV / storage formats (pure, unit-tested)
    TripStore.kt         trip history in private storage, exports to Documents/GPSCam
    Performance.kt       acceleration timing (pure, unit-tested); PerformanceStore.kt keeps the times
    SpeedChart.kt, TripsScreen.kt, AccelerationCard.kt
    Gnss.kt              constellations, bands, NMEA GSA parser
    Format.kt            units and number formatting
    CameraSession.kt     CameraX preview + VideoCapture + OverlayEffect, .srt writer
    OverlayRenderer.kt   the telemetry box drawn into camera frames
    MainActivity.kt      tabs, permissions, settings dialog
    SpeedScreen.kt, SatellitesScreen.kt, CameraScreen.kt
```
