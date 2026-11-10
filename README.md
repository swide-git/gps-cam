# GPS Cam

Android app that shows your real speed straight from the GPS receiver, the satellites it uses and
their signal strength, and records video with the speed burned into the picture.

Built for fast trains (Shinkansen, TGV, ICE), where Google Maps and Waze fall short: they use the
fused location provider, snap the position to roads and smooth the speed, so on a train at
300 km/h they freeze, jump or show nothing. GPS Cam reads the raw `GPS_PROVIDER`, whose Doppler
speed stays accurate at any speed the receiver can track.

## Features

- **Speed**: km/h, mph, m/s or knots (tap the unit to switch), speed accuracy, fix type (2D/3D),
  position accuracy, last-fix age (tunnels), altitude, heading.
- **Trip statistics**: maximum and average moving speed, distance, moving time, trip time. Only
  accurate fixes count, so jitter at a station or a single bad fix in a tunnel does not inflate
  them. Tracking continues with the screen off (foreground service, can be turned off in settings).
- **Satellites**: sky plot, per-satellite C/N0 signal bars, constellation (GPS, GLONASS, Galileo,
  BeiDou, QZSS, NavIC, SBAS), band (L1/E1/B1, L5/E5a/B2a, ...), used-in-fix, time to first fix,
  PDOP/HDOP/VDOP from NMEA.
- **Camera**: back camera, Full HD, stabilisation where supported. The telemetry box (speed, max,
  satellites, accuracy, time, optionally coordinates) is drawn into each frame with a CameraX
  `OverlayEffect`, so the preview shows exactly what the video contains. Videos go to
  `Movies/GPSCam`; a per-second telemetry subtitle file (`.srt`) goes to `Documents/GPSCam`.

Nothing leaves the phone: no internet permission, no accounts, no analytics.

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

Every merge to `main` publishes a signed APK on GitHub Releases (`.github/workflows/release.yml`):
the patch version is bumped automatically, `#minor` / `#major` in the merge message bump those,
`[skip release]` builds and tests only. Publishing requires four repository secrets:
`GPS_CAM_KEYSTORE_BASE64`, `GPS_CAM_KEYSTORE_PASSWORD`, `GPS_CAM_KEY_ALIAS`, `GPS_CAM_KEY_PASSWORD`.

## Layout

```
app/src/main/java/io/github/swidegit/gpscam/
    GpsTracker.kt        raw GPS: location, GnssStatus, NMEA (GSA) -> StateFlow<GpsState>
    GpsService.kt        foreground service (type location) owning the tracker
    TripStats.kt         distance, max, average, moving time (pure, unit-tested)
    Gnss.kt              constellations, bands, NMEA GSA parser
    Format.kt            units and number formatting
    CameraSession.kt     CameraX preview + VideoCapture + OverlayEffect, .srt writer
    OverlayRenderer.kt   the telemetry box drawn into camera frames
    MainActivity.kt      tabs, permissions, settings dialog
    SpeedScreen.kt, SatellitesScreen.kt, CameraScreen.kt
```
