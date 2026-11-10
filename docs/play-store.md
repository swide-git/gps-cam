# Google Play listing

Everything Play Console asks for, in one place. Graphics are in `docs/images/` and are rendered
with `scripts/render-store-graphics.sh`.

## App details

| Field | Value |
|---|---|
| Package name | `io.github.swidegit.gpscam` |
| App name (max 30) | GPS Cam |
| Default language | English (United States), en-US |
| App or game | App |
| Free or paid | Free |
| Category | Travel & Local |
| Tags | Speedometer, GPS, Travel |
| Contact email | (your developer account email) |
| Website | https://swide.pl/gps-cam/ |
| Privacy policy | https://swide.pl/gps-cam/ |

### Short description (max 80)

```
Real GPS speedometer, live satellites and video with your speed burned in.
```

### Full description (max 4000)

```
GPS Cam shows your real speed straight from the phone's GPS receiver, the satellites it uses, and records video with the speed burned into the picture.

Built for fast trains such as the Shinkansen, TGV or ICE, where navigation apps freeze or jump: they snap your position to roads and smooth the speed. GPS Cam reads the raw GPS receiver, whose Doppler speed stays accurate at 300 km/h and beyond. It works just as well in a car, on a bike or on foot.

SPEED
• km/h, mph, m/s or knots, tap the unit to switch
• Speed accuracy, fix type (2D/3D), position accuracy, altitude and heading
• Age of the last fix, so you know when a tunnel has frozen the reading
• Speed chart for the last 10 minutes, the last hour or the whole trip

ACCELERATION
• 0-100 and 100-200 km/h (0-60 and 60-130 mph) and the quarter mile with trap speed
• Timed from the GPS speed, last and best times, and the result shown in your video

TRIPS
• Maximum and average moving speed, distance, moving time and trip time
• GPS glitches are ignored: the maximum needs a good fix, a confident speed and an acceleration a vehicle can actually reach
• Save trips to the history, see each trip's speed and altitude charts, and export them as GPX or CSV, for example to see the route in Google Earth

SATELLITES
• Sky plot and signal strength of every satellite
• GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC and SBAS
• Frequency bands (L1, L5 and others), satellites used in the fix, time to first fix, dilution of precision

CAMERA
• Video with speed, maximum speed, satellites, time and optionally coordinates drawn into the picture; the preview shows exactly what you record
• 720p, 1080p or 4K at 30 or 60 fps, stabilisation where available
• Zoom, including the ultra-wide lens, pinch to zoom and tap to focus
• Landscape focus: focus fixed at infinity, so autofocus does not lock onto a train window
• Choose the corner, size and opacity of the overlay
• A telemetry subtitle file (.srt) is saved with every video

PRIVACY
• No internet access, no account, no ads, no analytics
• Location is processed only on your phone, and GPS runs only while the app is open

Tip for trains: hold the phone against the window. Coated glass and the metal body of the carriage block most of the GPS signal.
```

### What's new (max 500), current release

```
<en-US>
- Acceleration timer: 0-100 and 100-200 km/h (0-60 and 60-130 mph) and the quarter mile, with last and best times and the result in your video
- Trip details: speed and altitude charts for every saved trip
- Camera buttons moved below the preview, so they no longer hide the overlay
- Fixed: the camera could close the app on some phones
</en-US>
```

## Graphics

| Asset | File | Requirement |
|---|---|---|
| App icon | `docs/images/gps_cam_icon.png` | 512 x 512 PNG |
| Feature graphic | `docs/images/play_feature_graphic.png` | 1024 x 500 PNG |
| Phone screenshots | taken on a phone, see below | 2 to 8, 16:9 or 9:16, min 320 px |

Screenshots are best taken on a real phone outdoors (real speed and satellites; an emulator has
neither): power + volume down. Suggested set, in this order:

1. Speed screen while moving (hero card, tiles, speed chart)
2. A video frame with the overlay (from the gallery, or the Camera tab while recording)
3. Satellites screen (sky plot and signal bars)
4. Trips screen with a saved trip
5. Camera tab with the zoom and landscape focus buttons
6. Settings

## App content (policy forms)

| Form | Answer |
|---|---|
| Privacy policy | https://swide.pl/gps-cam/ |
| Ads | No, the app does not contain ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Questionnaire category "All other app types"; answer No to everything (no violence, sexual content, profanity, drugs, gambling, user interaction or sharing of location with other users, no purchases) |
| Target audience | 18 and over |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data safety: collects or shares data | No. Location, camera and microphone are processed only on the device; nothing is transmitted (no internet permission). Play counts only data sent off the device as "collected". |
| Data safety: encryption in transit | Not applicable |
| Data safety: deletion | Not applicable (no data leaves the device); in-app trip deletion and uninstall remove local data |
| Location permission declaration | Foreground only (no ACCESS_BACKGROUND_LOCATION), so no background location declaration is needed |
| Foreground service declaration | Not needed: the app declares no foreground service |

## Known Play Console notices

- **"The App Bundle contains native code, but no debug symbols were uploaded."** Safe to ignore.
  GPS Cam has no native code of its own; the `.so` files come from CameraX and AndroidX graphics
  (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`, `libandroidx.graphics.path.so`)
  and ship already stripped, so there are no symbols to upload (`debugSymbolLevel` produces an
  empty set for them).
