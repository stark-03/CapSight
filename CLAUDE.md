# CLAUDE.md — CapSight

CapSight is an Android app for visually impaired users. A camera on a cap (ESP32-CAM)
streams video over its own Wi-Fi hotspot. The app detects obstacles on the phone,
estimates distance, picks a safe direction and speaks short instructions through the
phone speaker or Bluetooth earphones.

Final-year project, Dept. of CSE, AITM Belagavi (VTU), 2025–26. Guide: Prof. Avanti Patil.
Team: Aishwarya Kumar Telasinge, Anuja Sutar, Aishwarya Channashetti, Chandrika Bannimatti.

Read this whole file before changing code. When a request conflicts with it, ask first.

---

## 1. Decisions already made (do not reopen without the user)

| Decision | Choice | Why |
|---|---|---|
| App technology | **Native Kotlin + Jetpack Compose**. Not React Native, not Flutter. | Frame decoding, inference and the 10 Hz safety loop must run natively and keep running with the screen off. The user ruled out anything that risks smoothness. |
| Where AI runs | **On the phone.** No cloud, no laptop, no internet. | SRS constraint; works offline anywhere. |
| Minimum Android | **Android 7.0 Nougat, API 24** (`minSdk = 24`). | User decision for ~99% device reach. **Supersedes the SRS, which says API 29.** See §6 for what this costs. |
| Target Android | **`targetSdk = 36`** (compileSdk stays 37). Do NOT raise to 37. | Android 17 blocks LAN access for apps targeting SDK 37 unless they hold the runtime `ACCESS_LOCAL_NETWORK` permission (NEARBY_DEVICES group); without it every connection to 192.168.4.1 silently times out. Found 14 Oct 2026 on the Pixel 9a (Android 17): shell user reached the ESP32, app user timed out; targetSdk 36 fixed it. Raising to 37 later requires declaring + requesting that permission first. |
| Camera hardware | **AI-Thinker-style ESP32-CAM (ESP-32S module) with an OV3660 sensor**, confirmed from the user's photos (2 Oct 2026): pin headers already soldered, PSRAM chip present (so firmware runs 640×480), camera ribbon not yet attached. No MB programmer yet. | Firmware already exists and works (§4); its OV3660 vflip fix applies. XIAO ESP32S3 Sense is the fallback board only. |
| Ceiling camera / BLE user-identification | **Removed.** | The old Phase-2 design. Kept only as reference code (§9). |
| BLE | **Kept, as BLE landmark beacons** (decided 18 Oct 2026: the guide requires BLE). ESP32 boards at doors/stairs/rooms advertise `CS:<TYPE>:<label>`; the phone scans and speaks "Room 304 door nearby". Contract §4.6. | Gives the project its BLE part with a real job the camera cannot do (naming places). BLE never carries video (far too slow); video stays on Wi-Fi. |
| Distance method | Apparent object size (ported from `egocentric.py`), fused with ground-plane geometry (camera height + tilt; nearer estimate wins, FR-DIST-03) where a tilt sensor exists: the phone's own sensor in PHONE_CAMERA mode (22 Oct 2026); the cap only once `/imu` exists. Distances are smoothed per object over time (FR-DIST-04). | Works with no calibration rig; proven in Phase 2. |
| Camera is optional | **Landmarks-only mode** (decided 23 Oct 2026, user requirement): using the camera is the user's choice. `SourceKind.NONE` ("No camera - BLE landmarks only") runs BLE landmarks without any camera, and a refused camera permission falls back to it for that session. Details §5.1. | BLE landmarks must keep working when the user does not allow / turn on the camera. |
| Names | **Keep as generated.** Package/applicationId `com.example.capsight`, project name "Cap Sight", hotspot `BLENAV_CAM`. Do not rename. | The app will not be published on the Play Store, so `com.example` is acceptable. User decision. |

Open decisions — **ask the user before acting on these**: none right now.
(Resolved 7 Oct 2026: outdoor classes car, motorcycle, bus, truck, dog added with size priors, §7.3.)

---

## 1A. Project status and phasing — the IoT device is NOT built yet

The cap device (ESP32-CAM + power + MPU6050 in a box on a cap) **does not exist yet**. The
user and Claude will build it later, together. Work happens in three phases, in this order:

| Phase | What | Hardware needed |
|---|---|---|
| **A — Mobile app (current)** | Build the whole app (build order §9) and test it on the user's phone | Phone + laptop only |
| **B — IoT device** | Update the firmware to contract v2 (§4.4), flash it, assemble and mount per `docs/Hardware_Build_Guide.pdf` | ESP32-CAM, parts list in the build guide |
| **C — Integration** | Point the app at the real cap, run acceptance tests AT-01…AT-10, tune thresholds and FOV | Everything |

Rules for Phase A:
- **Never block on the device.** Every feature must be testable without the ESP32-CAM, using:
  1. `PhoneCameraSource` — the phone's own rear camera (CameraX), for detection, distance,
     path, decisions and speech. This is the main Phase-A test source.
  2. `VideoFileSource` — a recorded walk video, for repeatable tests.
  3. **A fake ESP32-CAM on the laptop** — `tools/fake_esp32cam.py` (written in build
     step 1). It must reproduce §4.1–§4.2 byte-for-byte: port 80 `/` HTML containing
     `BLENAV_CAM`, port 81 `/stream` with the same boundary string, the same per-frame headers,
     **no leading boundary on the first frame**, 640×480 JPEG frames from a video file or the
     laptop webcam, one client at a time. The phone reaches it over the laptop's Wi-Fi (or the
     laptop's hotspot), so the camera base URL must be a **setting** (default
     `http://192.168.4.1`, overridable in a developer/debug settings screen). When the real
     device arrives, nothing in the app changes except leaving that setting at its default.
     Port rule (app `CameraContract.endpoints` and the fake's `--port` agree): base URL on port
     80 → stream on 81; any other base port P → stream on P+1, so the fake can run unprivileged
     (`--port 8080` → stream 8081, app address `http://<laptop-ip>:8080`).
- Treat §4 as the contract the fake device and the future firmware both follow. If Phase A
  reveals the contract needs a change (for example a `/status` endpoint), write it into §4.4
  as a v2 item for Phase B rather than assuming the device has it.
- Unit-test fixtures (`sample.mjpeg`) are recorded from the fake device until the real one
  exists; re-record from the real camera in Phase C and re-run the parser tests.
- The source selector (ESP32 / phone camera / video file) stays in the debug settings in the
  final app; it is also useful for the demo if the cap fails.

---

## 2. Repository layout

```
CapSight/
  CLAUDE.md                  this file
  app/                       the Android app (only thing that ships)
  firmware/esp32_cam_ap/     ESP32-CAM firmware — SOURCE OF TRUTH for the device
  firmware/wokwi_imu_sim/    MPU6050 tilt test for the Wokwi simulator (ESP32 DevKit, SDA=IO15,
                             SCL=IO14); prints the planned /imu JSON. Merge into esp32_cam_ap in Phase B.
  ml/yolov8n.pt              YOLOv8n weights, input to the TFLite export (§7.1)
  tools/fake_esp32cam.py     laptop stand-in for the camera during Phase A (§1A) — to be written in step 1
  reference/                 Phase-2 Python code to PORT. Read-only. Never ship, never edit.
    blenav/guidance.py         decide() policy             -> port to nav/
    blenav/egocentric.py       size->distance geometry     -> port to perception/
    blenav/world.py            relative_to_user, in_corridor, free_side -> port to perception/
    blenav/voice.py            single-slot priority speech -> port to voice/
    blenav/detector.py         COCO class ids used         -> port to detect/
    blenav/config/config.yaml  default thresholds (first_person section)
    tests/test_*.py            behaviour to reproduce as JUnit tests
  docs/
    SRS_Camera_Navigation.pdf  requirements (FR-/NFR- ids used below)
    Hardware_Build_Guide.pdf   parts, wiring, mounting, phone setup
    BLENAV_Phase2_Report.pdf, BLENAV_Methodology.pdf   prior work, for the report
    source/build_guide.html    editable source of the build guide
```

`reference/` and `docs/` are not part of the Gradle build. Android Studio may show the
Python files; ignore them.

---

## 3. Build, run, test

JDK: use Android Studio's bundled JBR. From a terminal outside Android Studio, set
`JAVA_HOME` to it first (Android Studio → Settings → Build Tools → Gradle → Gradle JDK
shows the path). `gradle/gradle-daemon-jvm.properties` + the foojay plugin pick the
daemon JVM.

```bash
./gradlew assembleDebug           # build debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest       # JVM unit tests (pure logic — run these constantly)
./gradlew installDebug            # install on the connected phone (USB debugging on)
./gradlew lint                    # Android lint
./gradlew assembleRelease         # signed release APK (needs keystore, §10)
adb logcat -s CapSight            # app logs (use tag "CapSight" everywhere)
```

Toolchain facts (from the generated project — keep them):
- AGP **9.4.1**, Gradle **9.6.0**, Kotlin **2.2.10**, Compose BOM **2026.02.01**, `compileSdk` **37**, `targetSdk` **36** (see §1), `minSdk` **24**, Java 11 bytecode.
- If a terminal build fails with "jlink executable … does not exist", it reused VS Code's Gradle
  daemon (its bundled JRE has no jlink): build with `./gradlew --no-daemon …` and `JAVA_HOME` set to the JBR.
- AGP 9 compiles Kotlin itself. **Do not add** `org.jetbrains.kotlin.android`; only the existing `kotlin.compose` plugin is needed.
- Dependencies go through `gradle/libs.versions.toml` only. No hard-coded versions in `build.gradle.kts`.
- `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS` — repositories go in settings, not in module files.
- When adding a library, look up its current version on Maven Central/Google Maven at that time. Never guess a version number.

Before saying any task is done: `./gradlew testDebugUnitTest assembleDebug lint` passes,
and anything touching the camera, speech or service was run on a real phone.

### 3.1 Testing happens on a real phone only — no emulator
The user does **not** use the Android emulator. Every manual test runs on their own physical
Android phone. So:
- Never suggest or rely on an emulator, AVD or emulator-only features. Test instructions you
  give the user must be steps on a real phone.
- The emulator could not test this app anyway: it cannot join the ESP32-CAM hotspot, use
  Bluetooth earphones or show real camera/TTS behaviour.

Installing the APK on the phone (give the user whichever fits):
1. **USB cable (preferred):** on the phone enable Developer options (tap Build number 7 times)
   → USB debugging. Then `./gradlew installDebug`, or
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Check with `adb devices`.
2. **Copy the APK file:** send `app-debug.apk` to the phone (USB file transfer, Drive, WhatsApp)
   and open it; allow "Install unknown apps" for that app when Android asks.

Watch out:
- **Do not use Wireless debugging for camera tests.** When the phone joins `BLENAV_CAM` it leaves
  the laptop's Wi-Fi, so a wireless adb connection drops. Use the USB cable while testing with
  the cap, and read logs with `adb logcat -s CapSight` over USB.
- The phone may ask to confirm every reinstall or block it via Play Protect — tap
  "Install anyway". A debug APK replaces the previous debug APK; switching between debug and
  release builds needs an uninstall first (different signing keys).
- Instrumented tests (`app/src/androidTest`) run on the connected phone with
  `./gradlew connectedDebugAndroidTest`. Keep most tests as JVM unit tests so they need no phone.
- After each build step, tell the user exactly what to tap and what they should see or hear on
  the phone, so they can confirm it works.

---

## 4. Device contract — the app MUST match the firmware exactly

Source: `firmware/esp32_cam_ap/esp32_cam_ap.ino`. If either side changes, change both
in the same commit and update the table in §4.4.

### 4.1 Network
| Item | Value |
|---|---|
| Wi-Fi mode | ESP32 is the access point (no router, no internet) |
| SSID / password | `BLENAV_CAM` / `blenav1234` (WPA2), channel 6 |
| Camera IP | `192.168.4.1` (phone gets 192.168.4.x by DHCP) |
| Landing page | `GET http://192.168.4.1/` (port 80) → small HTML, title `BLENAV_CAM`. Use this as the **health probe**: HTTP 200 + body contains `BLENAV_CAM` = we are on the camera's network. |
| Video stream | `GET http://192.168.4.1:81/stream` (port 81) |
| Protocol | Plain **HTTP (cleartext)** — the app needs a network security config (§6.2) |

### 4.2 Stream format (write the parser for exactly this)
- Response `Content-Type: multipart/x-mixed-replace;boundary=123456789000000000000987654321`,
  sent with `Transfer-Encoding: chunked` (each `httpd_resp_send_chunk` is one HTTP chunk;
  `HttpURLConnection` and curl remove the chunking, so the parser sees the body below).
- Each frame is sent as:
  `Content-Type: image/jpeg\r\nContent-Length: <N>\r\n\r\n` + N bytes of JPEG + `\r\n--123456789000000000000987654321\r\n`
- **The first frame has NO leading boundary** — the body starts directly with `Content-Type:`.
  Parse by reading headers up to `\r\n\r\n`, then exactly `Content-Length` bytes, then skip
  the boundary line. Do not rely on finding a boundary before the first frame.
- Frames: **320×240 JPEG, quality 12** by default (since 14 Oct 2026, see the antenna note in
  §4.3). Size can be changed via `/control`. Read the size from the decoded JPEG, never hard-code it.
- `CAMERA_GRAB_LATEST`: the device already drops stale frames. Measured **~11 fps** at 320×240
  with the 8 MHz camera clock.
- `/stream?src=copy` (copy frame to internal RAM before sending) and `/stream?src=dummy`
  (fixed 20 KB test frame) exist for diagnostics only; the app uses plain `/stream`.
- OV3660 is flipped vertically in firmware (`set_vflip`). If the board is mounted
  upside down in the cap box, fix it in firmware, not in the app.

### 4.3 Device limitations the app must handle
- **One stream client at a time.** The stream handler loops inside the port-81 server task,
  so a second viewer is blocked. Do not open the browser preview (`http://192.168.4.1/`
  embeds the stream) while the app is running. The app's health probe must only fetch the
  HTML, never the image.
- **Slow release after a dropped client.** If the phone vanishes mid-stream, the device may
  keep the old connection until its send timeout (~5 s). Reconnect with connect/read
  timeouts of ~2 s and retry every 500 ms; expect up to ~5 s before the stream returns.
- **Brownout detector is disabled** in firmware; a weak supply shows up as the stream
  dropping and the board rebooting. The app reports this as "Camera disconnected".
- **Camera settings endpoint (added 6 Oct 2026):** `GET http://192.168.4.1/control` returns
  `{"ok":true,"error":"ESP_OK","xclk_mhz":8,"fb":2,"grab":"latest","size":"qvga","q":12}`;
  `GET /control?xclk=10&fb=1&grab=empty&size=qvga&q=12` restarts the camera with those
  settings (any subset of keys). Active stream clients are disconnected during the restart and
  `/stream` answers 503 meanwhile. Settings reset to the firmware defaults on reboot.
  Used by `tools/camera_tuning.sh`. No `/status` or `/imu` yet.
- **Camera clock MUST stay at 8 MHz (resolved 14 Oct 2026).** At 10–20 MHz the camera clock
  interferes with this board's on-board PCB antenna: small replies get through but any transfer
  over ~2 TCP packets stalls (0 fps). Ruled out by measurement: power supply, the MB's IO0 line,
  PSRAM vs internal RAM, buffer count, grab mode, frame size. Camera off
  (`firmware/wifi_speed_test`) = 64 fps; camera at 8 MHz + 320×240 = 11 fps. Matches
  espressif/arduino-esp32 issue #4655. If frame rate must go higher later: external 2.4 GHz
  antenna on the IPEX connector (move the 0 Ω antenna-select resistor) or foil shielding over
  the camera ribbon. Never raise `xclk` without re-running `tools/stream_check.sh`.

### 4.4 Contract versions
| Contract | Firmware | App | Endpoints |
|---|---|---|---|
| v1 (current) | `esp32_cam_ap.ino` as copied from Phase 2 | first release | `/` (80), `/stream` (81) |
| v2 (planned) | adds IMU + status | reads them **if present** | adds `GET :80/status`, `GET :80/imu` |

Planned v2 additions (firmware work, SRS FR-CAM-04/05/06). The app must feature-detect
them (404 or timeout → feature off) and keep working against v1:
- `GET http://192.168.4.1/status` → `{"fw":"2.0","sensor":"OV3660","frame":"VGA","uptime_s":123,"heap":45000}`
- `GET http://192.168.4.1/imu` → `{"pitch_deg":-28.5,"roll_deg":1.2,"t_ms":123456}` (negative pitch = looking down).
  NOTE: the app's `perception/CameraTilt.Tilt.pitchDownDeg` is the opposite sign (positive = looking
  down), so an `/imu` reader must pass `pitchDownDeg = -pitch_deg`.
- MPU6050 wiring (from the build guide): VCC→3V3, GND→GND, **SCL→IO14, SDA→IO15**, so in firmware `Wire.begin(15, 14)`. No SD card may be inserted (IO14/15 are shared with it).
- While editing firmware for the cap: set `ae_level` to 0 (the +1 bias was for a ceiling mount), and update the header comment from "ceiling camera" to "cap camera".

### 4.5 Flashing the firmware
Arduino IDE 2.x, board package "esp32 by Espressif Systems", Board **AI Thinker ESP32-CAM**,
Partition **Huge APP (3MB No OTA)**, 115200 baud, via the ESP32-CAM-MB programmer. Full steps
with pictures: `docs/Hardware_Build_Guide.pdf`, step 2.

### 4.6 BLE landmarks v1 (`firmware/ble_landmark/ble_landmark.ino`)
- **Hardware:** any plain ESP32 (the team's spare ESP32 Dev Module from Phase 2), USB-powered,
  placed at the landmark. Flash with Board **ESP32 Dev Module**. One beacon per landmark.
- **Advertisement:** legacy advertising every 100 ms at +9 dBm, scannable. **Advertising packet:**
  flags 0x06 + complete 128-bit service UUID **`6c616e64-6d61-726b-4353-000000000001`**
  (`Landmark.SERVICE_UUID` in the app; the app's scan filters on it so it keeps working with the
  screen off). **Scan response:** Complete Local Name `CS:<TYPE>:<LABEL>`, max 26 characters.
  `TYPE` ∈ `DOOR, STAIRS, LIFT, WASHROOM, EXIT, ROOM, OTHER`; `LABEL` free text, may be empty.
  Unknown TYPE → treat as `OTHER`. Names not starting with `CS:` are ignored.
- **Spoken phrases** (app side, short and fixed):

  | TYPE | with label | without label | priority |
  |---|---|---|---|
  | DOOR | "{label} door nearby." | "Door nearby." | info |
  | STAIRS | "Caution. Stairs nearby." | same | **P1** (fall risk, same level as detected stairs) |
  | LIFT / WASHROOM / EXIT | "{Type} nearby." (+ ", {label}" if set) | "{Type} nearby." | info |
  | ROOM / OTHER | "{label} nearby." | (ignored) | info |
- **Proximity (app):** RSSI smoothed per beacon (median of readings in the last 3 s). Announce when
  smoothed RSSI ≥ **-70 dBm** (about 2-4 m, tune on site); re-arm only after it falls below
  **-80 dBm** or the beacon is unseen for 10 s (hysteresis); never the same beacon twice within
  **60 s**. Info announcements never interrupt or replace P0-P2 speech; STAIRS uses P1.
  Thresholds are settings like everything else (§8).
- **Distance (app, 23 Oct 2026; `ble/BeaconDistance.kt`, pure):** log-distance path loss on the smoothed
  RSSI, d = 10^((rssiAt1m - rssi) / (10·n)), clamped to 0.3-30 m. Settings ("BLE landmarks" section):
  "Say distance to beacons" **on**, "Beacon signal at 1 m" **-52 dBm** (valid -90..-20), path-loss
  exponent **2.5** (valid 1.5..4.0; 2 = open space, 3+ = walls/people). Spoken only as steps 1, 2, 3, 5,
  8, 10, 15, 20 m (<1.5→1, <2.5→2, <4→3, <6.5→5, <9→8, <12.5→10, <17.5→15, else 20); a new step must
  stay on the same side (nearer / farther) for 1.5 s before it counts (`StepFilter`).
  Phrases: first announcement = the table phrase + ", about N metres." ("Room 304 door nearby, about
  4 metres.", "Caution. Stairs nearby, about 3 metres."; "1 metre" singular). Until the beacon re-arms:
  an update only when the step DROPS below the last spoken one, at most every 4 s per beacon ("Room
  304 door, about 2 metres." / "Caution. Stairs, about 2 metres."); at step 1 once "Room 304 door, you
  are here." / "Caution. Stairs right in front of you." / "{label}, you are here." (not said if the
  first announcement was already "about 1 metre"); then silent until re-arm. Moving away never speaks.
  Same priority as the type (STAIRS P1, rest INFO), same Speaker path; a refused update is retried on
  the next reading; every accepted one is a `landmark` row in the decision log (reason has kind, metres,
  step, dBm). Setting off = exactly the original behaviour. **Calibration:** debug view "Calibrate at 1 m"
  - hold the phone 1 m from a beacon, tap; the strongest beacon's smoothed RSSI (rounded) is saved as
  "Beacon signal at 1 m" ("Calibrated: -51 dBm at 1 m"; no beacon heard → nothing saved).
  **Accuracy, honestly:** approximate - about ±1 m at 1-3 m, much worse farther or through bodies and
  walls (the user's own body can cost 10 dB ≈ 2.5× the distance); RSSI gives no direction.
- **App implementation notes** (build step 8, 18 Oct 2026): `ble/Landmark.kt` + `ble/LandmarkTracker.kt`
  are pure and JVM-tested; `ble/LandmarkScanner.kt` runs inside `Pipeline.run()` (so inside
  `NavigationService`). Beacons are tracked by Bluetooth address, not name. Info landmarks use the
  existing `Priority.INFO` (5): they never replace anything pending, but a P1/P2 that arrives while a
  landmark phrase is already playing waits for it to finish (~1.5 s; only P0 interrupts). A landmark
  counts as announced only when `Speaker.say` accepts it; it can still be displaced from the slot
  later by a more urgent phrase, and is then not repeated for 60 s.
- **Screen off (18 Oct 2026):** the scan is filtered on the service UUID above, because Android
  8.1+ pauses unfiltered scans while the screen is off. The name is read from the scan response
  (Android scans actively). Still to verify on the phone in Phase C: landmarks announced with the
  screen off.

---

## 5. App architecture

Pipeline (SRS §3): **receive → detect → obstacles + distance → path → decide → speak**.

Package plan (sub-packages of `com.example.capsight`):
```
camera/      M2  FrameSource interface; MjpegFrameSource (ESP32); PhoneCameraSource and
                 VideoFileSource for testing without the cap; CameraNetwork (Wi-Fi binding);
                 PhoneTiltSensor (gravity sensor for ground-plane distance, phone camera only)
detect/      M3  YoloDetector (LiteRT), Letterbox, Nms, CocoLabels
perception/  M4,M5  Obstacle filtering, size-based distance (port of egocentric.py),
                 relative geometry (port of world.py helpers); DistanceTracker + SmoothedPerception
                 (per-object smoothing, FR-DIST-04); CameraTilt (gravity -> pitch/roll) and
                 GroundPlane (floor distance + fusion, FR-DIST-02/03)
path/        M6  ZoneAnalyzer: left / centre / right clearance
depth/       M4,M6  FR-OBS-04 / FR-PATH-03 monocular depth (§7.4): DepthScorer + DepthRule (pure);
                 MidasDepth (LiteRT) + DepthRunner (own thread, ~4 Hz) (Android)
nav/         M7  decide() pure policy + GuidanceLoop (10 Hz ticker)
voice/       M8  Speaker (single pending slot, priority, cooldown), Haptics
service/         NavigationService — foreground service that owns the whole pipeline
settings/        AppSettings (DataStore) with the defaults in §8
ble/             BLE landmarks (§4.6): Landmark + LandmarkTracker (pure), LandmarkScanner (Android)
control/         FR-APP-06 volume-key gestures: MultiPressDetector + GestureRules (pure); VolumeGesture +
                 VolumeKeySession (Android, MediaSession owned by NavigationService); service/GestureControl
                 carries a gesture out (§8.2)
record/          FR-APP-07 decision log: DecisionCsv (pure CSV/ISO time/pruning), DecisionLogger (Android, IO)
ui/          M9  Compose screens (§5.2): blind-first Main, safety statement + spoken welcome / Help,
                 helper area (Settings -> Debug view); theme/ = always-dark high-contrast palette
```

Rules:
- `detect/` post-processing, `perception/`, `path/`, `nav/`, `depth/DepthScorer` + `depth/DepthRule`, `control/MultiPressDetector` + `control/GestureRules`, `record/DecisionCsv`, and `ble/` except `LandmarkScanner` are **pure Kotlin**: no `android.*`
  imports, no clocks (pass time in), no threads. They are JVM-unit-tested like the Python originals.
- The pipeline lives in `NavigationService`, **not** in an Activity or ViewModel, so it keeps
  running with the screen off. The UI only observes `StateFlow`s from the service.
- **Newest-frame-only**: frames flow through a conflated channel / `MutableStateFlow`. Never queue frames.
- Threads: stream reading on `Dispatchers.IO`; inference on one dedicated single-thread
  dispatcher (the interpreter is not thread-safe); the 10 Hz decision ticker on its own coroutine.
- Use `SystemClock.elapsedRealtime()` for timing, not wall-clock time.
- Fail safe (FR-NAV-05): any exception in a tick → say "Stop" and log it. Never go silent while running.

### 5.1 Landmarks-only mode (no camera, 23 Oct 2026)
- `SourceKind.NONE` (stored as `"NONE"`; unknown stored values fall back to PHONE_CAMERA). Chosen with the
  "Use a camera" switch in Settings (off = NONE, on = phone camera, saved at once) or the debug view's source picker.
- Pipeline in this mode: **no** frame receiver, YOLO, depth, tilt sensor, camera-link monitoring or
  "Connecting to camera"; BLE scanner, speaker, decision log and volume keys still run. The guidance loop
  ticks with `cameraOff` = `Policy` returns a silent IDLE decision: never "Camera disconnected", "Stop.",
  obstacle, stairs or "Path clear." (the camera-mode fail-safes are unchanged). Landmark phrases keep
  their priorities (STAIRS P1, rest INFO) and cooldowns. Start phrase: "Navigation started without camera.
  To use the camera, press volume up three times." (volume-button control off: "Navigation started. Camera
  off, landmarks only."). Main screen: "Navigating without camera - landmarks only" (+ "Camera not available, so
  landmarks only." on a fallback) and the BLE scanner status ("Landmarks: permission denied." explains silence);
  debug banner: "Landmarks only - camera off" (+ "(camera not available)").
- Service: connectedDevice type only, no Wi-Fi lock. The mode is fixed for the session by the start intent
  (volume up x3 restarts the session with the camera, §8.2; a verified camera session that cannot open the
  phone camera switches itself to this mode).
- **Effective mode** (`settings/StartPlan.decide`, pure + tested): PHONE_CAMERA + camera permission -> PHONE_CAMERA;
  PHONE_CAMERA without it -> NONE for this session only (saved source unchanged, next Start asks again) +
  toast; ESP32 / VIDEO_FILE -> unchanged; NONE -> NONE. No camera **and** BLE landmarks off -> does not start:
  toast "Camera is off and BLE landmarks are switched off - nothing to guide with" + spoken
  "Camera off and landmarks off. Nothing to guide with." BLE permission refused -> still starts (landmarks silent, reason shown).
- Volume-key starts (§8.2): volume up x2 = landmarks only (`StartWish.NO_CAMERA`), volume up x3 = with a camera
  (`StartWish.CAMERA`: the selected camera source, or the phone camera when "No camera" is selected; saved source
  unchanged). With CapSight on screen a missing permission opens the normal start flow (dialogs) for that wish;
  off screen no dialog ever opens: a missing camera permission starts landmarks only with "Camera is not
  available...", a missing BLE permission is spoken after the start phrase.

### 5.2 User interface (blind user first, sighted helper second; 24 Oct 2026)
- **First run** (`ui/AppStage`, pure): safety statement (NFR-SAFE-01, also spoken: `Phrases.SAFETY_INTRO`, big
  "I understand" at the bottom) -> spoken welcome once (`Phrases.help`: what CapSight does, keep the cane, the four
  volume gestures - or that they are off -, camera optional, screen layout; saved as `welcome_shown`) with "Hear
  again" / "Got it" -> main screen. Help on the main screen replays the same screen. Lines are spoken one at a time
  at INFO priority, each only when the speaker is free (`AppViewModel.readHelp`), so warnings still get through.
- **Main screen** (`MainScreen`, status text from `ui/MainStatus`, pure): status area first (mode in plain words
  "Stopped" / "Navigating with camera" / "Navigating without camera - landmarks only" = heading + polite live
  region; one detail line; "Last said: ..." not live, the app already speaks it). Stopped: two huge buttons "Start
  with camera" (yellow) above "Start without camera" (outlined); running: one huge "Stop navigation" (~45% of the
  height; plus "Use the camera too" while landmarks only); then "Help" and "Repeat status"; at the bottom a small
  "Helper settings". Buttons do exactly what the gestures do: start = `rememberNavigationStarter` with
  `StartWish.CAMERA` / `NO_CAMERA` (same StartPlan, permission dialogs and start phrases as volume up x3 / x2),
  stop = `NavigationService.stop`, Repeat status = `GestureControl.repeatStatus` (the volume down x3 reply); same
  vibrations (`Haptics.gesture*`). They never speak their own label (TalkBack already does). No keep-screen-on:
  guidance runs with the screen off; only the debug view keeps the screen on while running.
- **Helper area:** Settings (sections Camera and obstacle warnings, BLE landmarks + calibration, Voice, Volume
  buttons, Advanced, Phone setup, About; Save fixed at the bottom) -> Debug view. Back from Debug returns to Settings.
- **Rules for every screen:** colours only from `ui/theme/Palette` (always dark, no dynamic colour; text >= 7:1,
  control edges >= 3:1, checked by `PaletteTest`); all text in sp, never a fixed height that clips text (use
  `heightIn(min = ...)` and a scrolling column, check at font scale 2.0); controls the blind user touches >= 72 dp
  (`UserTouchMin`), helper controls >= 48 dp; every control has a text label or contentDescription; headings
  marked `heading()`; status that changes on its own is a polite live region, but never text the app already
  speaks; meaning never by colour alone. Spoken words live in `voice/Phrases.kt`, screen labels in `strings.xml`.

---

## 6. Android 7–17 compatibility (minSdk 24, targetSdk 36)

### 6.1 Manifest
Permissions:
```
INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
WAKE_LOCK, VIBRATE,
FOREGROUND_SERVICE                          (API 28+)
FOREGROUND_SERVICE_CONNECTED_DEVICE         (API 34+, matches the service type below)
POST_NOTIFICATIONS                          (API 33+, runtime request)
CAMERA                                      (runtime; PhoneCameraSource test source only)
```
- `<uses-feature android:name="android.hardware.camera" android:required="false"/>`.
- Service (decided in step 7, 15 Oct 2026): `<service android:name=".service.NavigationService"
  android:exported="false" android:foregroundServiceType="connectedDevice|camera"/>`.
  `ServiceCompat.startForeground` always passes `CONNECTED_DEVICE` (API 29+) and adds `CAMERA`
  (API 30+, permission `FOREGROUND_SERVICE_CAMERA`) only when the phone-camera source is selected
  and the CAMERA permission is granted, so the phone camera also works with the screen off. Camera
  type can only start while the app is in the foreground; a system restart falls back to
  connectedDevice only. The service also holds a partial wake lock and a low-latency Wi-Fi lock while navigating.
  **Volume-key standby (24 Oct 2026, §8.2)** uses the same service with type connectedDevice only (no new
  type, no wake lock or Wi-Fi lock). When the service is already in the foreground, a second
  `startForeground` (e.g. adding the camera type from standby while the phone is locked) can be refused on
  API 31+/34+: the service then keeps its current type and the camera start falls back to landmarks only.
  A small private SharedPreferences file (`navigation_service`) records navigating / standby so a system
  restart (START_STICKY, null intent) resumes the right state.
- TTS on API 30+ needs package visibility, or the engine is invisible:
  `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`
- The camera link needs **no** location permission: we never read the SSID; the HTTP health
  probe identifies the camera (§4.1).
- **Decision log sharing (FR-APP-07):** `androidx.core.content.FileProvider`, authority
  `${applicationId}.fileprovider`, paths `res/xml/file_paths.xml` (only `files/decision_logs/`).
  No storage permission.
- **BLE landmarks (§4.6) need scan permissions** (added 18 Oct 2026):
  `BLUETOOTH` + `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`), **`ACCESS_FINE_LOCATION` +
  `ACCESS_COARSE_LOCATION` on every API level** (runtime; precise location must be granted and
  Location services must be ON), and `BLUETOOTH_SCAN` (API 31+, runtime). Do **not** set
  `neverForLocation` on BLUETOOTH_SCAN: it makes Android filter out beacon advertisements. But
  without it, **Android 12+ delivers zero scan results unless the app also holds fine location**:
  measured 23 Oct 2026 on the Pixel 9a (Android 17) - scan ran, 0 results from any device;
  after granting fine location, 91 results and "Room 304 door nearby." at -42 dBm within 0.3 s.
  `LandmarkScanner.scanPermissions` / `hasScanPermission` encode this; the start flow requests them
  together. Declare
  `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`.
  If permission is denied or Bluetooth is off, landmarks are silently disabled and say so in the
  debug view; navigation itself keeps working.

### 6.2 Cleartext HTTP
`res/xml/network_security_config.xml` permitting cleartext **only** for `192.168.4.1`, referenced
from `<application android:networkSecurityConfig=...>`. Without it, Android 9+ blocks the stream.
**Debug builds only** override it (`app/src/debug/res/xml/network_security_config.xml`) to allow
cleartext to any host, because the fake camera runs on a laptop address. Release builds keep the
192.168.4.1-only rule; to use the fake with a release build, give the laptop hotspot 192.168.4.1.

### 6.3 Staying on the camera Wi-Fi
The camera network has no internet, so Android may prefer mobile data. Approach that works on API 24+:
1. The user joins `BLENAV_CAM` in system Settings once (build guide step 8; tap "Stay connected").
2. The app calls `ConnectivityManager.requestNetwork()` with `TRANSPORT_WIFI` and
   `removeCapability(NET_CAPABILITY_INTERNET)`, takes the `Network` it gets, and opens the stream
   and health probe through **that network only** (`network.openConnection(url)` or an OkHttp
   client with `network.socketFactory`). Prefer per-connection binding over `bindProcessToNetwork`.
3. API 29+ may additionally offer in-app connection with `WifiNetworkSpecifier` (shows a system
   dialog). Optional; the Settings route must keep working on API 24–28.

### 6.4 Version-specific code paths
| Feature | API 24–25 | 26–28 | 29–32 | 33+ | 34+ |
|---|---|---|---|---|---|
| Foreground service | `startForeground` | + notification channel | + service type | + notification permission | type permission enforced |
| Vibration | `vibrate(ms)` | `VibrationEffect` | same | same | same |
| LiteRT acceleration | GPU delegate if supported, else CPU | same | same | same | same |
- Do not use NNAPI (deprecated in Android 15). Try the GPU delegate, fall back to CPU with 4 threads.
- If `java.time` is ever used, enable core library desugaring (minSdk < 26). Prefer not to need it.
- Wrap every API-gated call in `Build.VERSION.SDK_INT` checks or AndroidX `*Compat` helpers.

### 6.5 Old and low-end phones
Android 7 phones are usually slow and often **32-bit (armeabi-v7a)**. Keep both
`arm64-v8a` and `armeabi-v7a` native libraries. On slow devices degrade in this order:
decode JPEG at half size (`inSampleSize = 2` → 320×240, cheap and also what the model needs),
skip frames, lower model input to 256. The 10 Hz decision rate never drops; warnings use the
last known obstacles (hold, §8).

---

## 7. ML model

### 7.1 Export (run on the laptop)
Ultralytics ≥ 8.4.83 replaced `format=tflite` with `format=litert` (converted directly from
PyTorch by `litert-torch`), which has **no FP16 option** — only FP32 or INT8. The shipped model
was made with Ultralytics 8.4.121:
```bash
# separate venv, so the Phase-2 'blenav' env is not modified; litert-torch needs torch < 2.14
pip install "torch==2.13.*" "torchvision==0.28.*" --index-url https://download.pytorch.org/whl/cpu
pip install "ultralytics==8.4.121" "litert-torch==0.9.4" "ai-edge-litert==2.2.0"
yolo export model=ml/yolov8n.pt format=litert imgsz=320
# copy yolov8n.tflite to app/src/main/assets/yolov8n_320_fp32.tflite  (12.2 MB)
```
FP32 runs fine on the GPU delegate (which computes in FP16 internally). Try INT8
(`quantize=8`) later only if low-end phones are too slow, and re-check accuracy.
In Gradle: `androidResources { noCompress += "tflite" }` so the model can be memory-mapped.
Runtime: LiteRT **1.4.2** (`litert`, `litert-gpu`, `litert-gpu-api`) — Interpreter API, minSdk 21,
arm64-v8a + armeabi-v7a. The model was checked to load in the 1.4 runtime.

### 7.2 Model I/O (verified on the laptop; the app logs shapes on load)
- Input: **`[1, 3, 320, 320]` float32, NCHW** (the LiteRT export keeps PyTorch's layout; old
  TFLite exports were `[1, 320, 320, 3]` — `YoloDetector` reads the layout from the model),
  RGB, values 0–1. Letterbox the frame to 320×320 (pad colour 114/255), remember scale and
  padding to map boxes back.
- Output: `[1, 84, 2100]` — per candidate: cx, cy, w, h, then 80 class scores. No NMS inside
  the model: implement score threshold + per-class NMS (IoU 0.45) in Kotlin.
- Box coordinates come out **normalised (0–1)**; `YoloDecoder` also accepts pixel output.
  On Ultralytics' `bus.jpg` the Kotlin decoding matches PyTorch within 2 px (unit-test fixture).

### 7.3 Classes used (COCO ids, from `reference/blenav/detector.py`)
Person 0; obstacles: 1 bicycle, 13 bench, 24 backpack, 26 handbag, 28 suitcase, 39 bottle,
41 cup, 56 chair, 57 couch, 58 potted plant, 59 bed, 60 dining table, 62 tv, 63 laptop,
66 keyboard, 73 book, 75 vase. Size priors for distance: `OBJECT_HEIGHTS_M` / `OBJECT_WIDTHS_M`
in `reference/blenav/egocentric.py` — copy them exactly.
Outdoor obstacles (user decision 7 Oct 2026): 2 car (h 1.50, w 1.80 m), 3 motorcycle (1.10, 0.80),
5 bus (3.00, 2.50), 7 truck (3.00, 2.50), 16 dog (0.55, 0.30). Widths are front/back views, so a
side-on view gives a nearer (safe) estimate.

Known gap: the SRS (FR-DET-02) also lists **door, pole/sign and stairs**. These are **not COCO
classes**; standard YOLOv8n cannot detect them. They need a fine-tuned model (stairs is
FR-DET-04, "Should"). Do not claim them as working.

### 7.4 Depth model (FR-OBS-04 / FR-PATH-03, implemented 22 Oct 2026)
Obstacles YOLO cannot name (walls, pillars, boxes). Reference, evaluation and the decided rule:
`ml/depth/README.md` + `ml/depth/eval_photos.py` (do not edit them; the app reproduces them).
- Model: MiDaS v2.1 small, `app/src/main/assets/midas_small_256_fp32.tflite` (copied from
  `ml/depth/midas_v2_1_small_256.tflite`, MIT). **Adds ~66 MB to the APK** (debug APK 70 MB -> 136 MB).
  Input `[1, 256, 256, 3]` float32 RGB NHWC, ImageNet-normalised; output `[1, 256, 256, 1]` relative
  INVERSE depth (bigger = nearer). `MidasDepth` checks and logs the shapes on load.
- Runtime: `DepthRunner` on its own single-thread dispatcher (priority just below the detector), newest
  frame only, plain resize to 256x256, about 4 Hz; GPU delegate if supported, else CPU 4 threads.
  Missing model, load failure or a bad frame = depth off / no result (reason in the debug view);
  guidance never depends on it.
- Scoring (`DepthScorer`, pure): floor ref = median of rows 220-250, cols 96-160; band rows 60-180;
  zone score = p90 of the band in cols 0-85 / 85-171 / 171-256 divided by the floor ref; no score if
  the floor ref is not positive.
- Rule (`DepthRule`, pure, conservative): centre score >= threshold on consecutive results covering
  >= 1 s -> an unlabeled obstacle at the stop distance in the centre ("Stop. Obstacle ahead.", P0) unless
  a YOLO obstacle or stairs is already held in the corridor within the stop distance. L/R score >=
  threshold -> an obstacle in that side lane, so the side chooser never says "Move left/right" into a
  near wall (both sides near -> P3). Released below threshold - 0.1; results older than 1.5 s = no cue.
  Depth obstacles have their own hold (same ticks) and never refresh or replace the held YOLO obstacles.
- Known limits: glass reads as far (a window wall scores ~0.92, below 1.0), pillars read as near (a
  pillared corridor scores ~0.92, which is why the default is 1.0, not 0.85). Rate, backend and the
  GPU/CPU contention with YOLO still need checking on the real phones.

---

## 8. Navigation behaviour (port faithfully, then extend)

Defaults (from `reference/blenav/config/config.yaml` → `first_person`, and the SRS):

| Setting | Default | Notes |
|---|---|---|
| Decision rate | 10 Hz | fixed, independent of detection speed |
| Stop distance | 1.2 m | P0 |
| Warn distance | 2.5 m | P2 |
| Stairs warn | 4.0 m | P1 (needs the stairs model) |
| Corridor half-width | 0.55 m | `BODY_HALF_WIDTH_M` |
| Min confidence | 0.40 | |
| Skip unreliable flat items | true | keyboard, book, cup, laptop |
| Hold after dropout | 6 ticks (0.6 s) | a missed frame is never "all clear" |
| Phrase cooldown | 3 s | per identical phrase; P0 uses 2 s |
| "Path clear" repeat | 6 s | |
| Horizontal FOV | **measure it** (§8.1); 54° until measured | wrong FOV scales every distance. Applies to the cap camera. The phone-camera test source computes its own FOV from Camera2 focal length + sensor size; recorded videos use a 65° phone default (both settings). |
| User height | 165 cm | the cap camera sits about 10 cm below it |
| Camera height above floor | **1.30 m** (phone worn at chest), valid 0.5–2.2 | ground-plane distance (FR-DIST-02). Cap camera: user height − ~0.1 m |
| Ground-plane limits | roll < 25°, ray ≥ 2° below horizon, box bottom not on the frame edge | fixed in `GroundPlane`; otherwise size-based only. Fusion: nearer of size/floor wins |
| Distance smoothing | EMA α 0.5, match same label + IoU ≥ 0.3, forget after 1 s | fixed in `TrackParams`. A raw distance > 30% nearer than the average is taken at once |
| Volume-button control | **on** | FR-APP-06, see §8.2: up x2 / up x3 start, down x2 stop, down x3 status; standby after the app was opened |
| Depth obstacle detection | **on** | FR-OBS-04 / FR-PATH-03, §7.4 |
| Depth threshold | **1.00**, valid 0.6–1.5 | zone score vs the floor just ahead; sustain 1 s, release at −0.1, stale after 1.5 s (fixed in `DepthParams`) |
| Decision log | always on while navigating | FR-APP-07: `files/decision_logs/decisions_<time>.csv`, newest 20 kept, excluded from backup; "Share decision log" in the debug view |

Priority policy (lower wins) — SRS §4.7 wording is authoritative for speech:
| P | Trigger | Speech |
|---|---|---|
| 0 | obstacle ≤ stop distance in corridor, **or camera lost > 2 s**, or a sustained near unknown obstacle from depth (§7.4) | "Stop. Chair ahead." / "Camera disconnected. Please stop." / "Stop. Obstacle ahead." |
| 1 | stairs ≤ 4 m | "Caution. Stairs ahead." |
| 2 | obstacle ≤ warn distance in corridor | "Chair ahead. Move left." (side from `free_side`, ties go right) |
| 3 | left, centre and right all blocked (new, not in Python) | "Path blocked. Stop and turn." |
| 4 | clear | "Path clear." (Python said "Continue straight."; use the SRS phrase) |

Port the logic of `decide()`, `egocentric_obstacles()`, `estimate_distance()` (including the
truncation and frame-edge corrections), `relative_to_user()`, `in_corridor()`, `free_side()` and
the asymmetric hold in `GuidanceLoop._tick_first_person()`. Reproduce the cases in
`reference/tests/test_guidance.py` and `test_egocentric.py` as JUnit tests. The ceiling-mode parts
(BLE, homography, path deviation, target lock) are **not** ported.

Speaker rules (port of `voice.py`): one pending slot, not a queue; a new message replaces the
pending one unless the pending one is more urgent; identical phrase suppressed within its
cooldown; P0 interrupts current speech (`QUEUE_FLUSH`). Set audio attributes to
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`. If TTS fails to initialise or goes silent, vibrate on
P0/P2 and keep showing captions.

### 8.1 Measuring the camera's horizontal field of view
The build guide's "66°" is a typical lens spec and may be the **diagonal** figure. Measure the
real horizontal FOV once per lens: put the camera exactly 1.00 m from a flat wall, mark where
the left and right edges of the image hit the wall, measure that width W in metres,
FOV = 2·atan(W / 2). Enter it in Settings. Update the build guide if it differs.

### 8.2 Volume-button control (FR-APP-06, 22 Oct 2026; new gesture scheme 24 Oct 2026)
Setting "Volume-button control" (default **on**). The old "double-press volume-down toggles" gesture is gone.

| Gesture | Stopped (or standby) | Running without camera | Running with camera | Vibration |
|---|---|---|---|---|
| **Volume up x2** | start landmarks only: "Navigation started without camera. To use the camera, press volume up three times." | "Navigation is already running without camera." | "Navigation is already running with camera." (never turns the camera off) | 2 short |
| **Volume up x3** | start with the camera: "Navigation started with camera. Press volume down twice to stop." | restart with the camera (same phrase) | "Navigation is already running with camera." | 2 short |
| **Volume down x2** | "Navigation is already stopped. Press volume up twice to start." | stop: "Navigation stopped. Press volume up twice to start." | stop (same) | 1 long |
| **Volume down x3** | "Navigation is stopped. Press volume up twice to start." | status: "Navigating without camera. Room 304 door, about 3 metres." | "Navigating with camera. ..." | 3 short |

- **Recognition** (`control/MultiPressDetector`, pure): presses of one key within 600 ms of each other form a
  burst; the burst is decided **500 ms after its last press** (so in practice presses must be < 500 ms
  apart). Exactly 2 or 3 presses = gesture; 1 or 4+ = only volume; a press of the other key cancels the
  burst (and starts its own); holding the key (Activity auto-repeat, or presses < 90 ms apart on the
  MediaSession path) = no gesture. Every press still changes the media-stream volume (the stream speech
  uses); after a recognised gesture the volume from before its first press is restored. During a phone /
  VoIP call the keys change the call volume and are never a gesture.
- **Meaning** (`control/GestureRules`, pure, table above): "with camera" = the selected camera source (the cap
  in Phase C, the phone camera, a video file), or the phone camera if "No camera" is selected. Up x3 without
  the camera permission, or when Android refuses the camera: "Camera is not available. Unlock the phone and
  allow the camera in CapSight. Navigating without camera." and landmarks only (camera and landmarks both
  impossible: "Camera is not available and landmarks are switched off. Open CapSight to start."). Up x2 with
  landmarks off: "Landmarks are switched off. To use the camera, press volume up three times." The status line
  uses the strongest beacon heard now with the §4.6 distance wording ("No landmark nearby." / "Landmarks are
  not available." otherwise; only the start hint while stopped, as no BLE scan runs then). All replies are
  INFO priority: they never interrupt or replace a warning. All wording lives in `voice/Phrases.kt`.
- **Where the keys are seen:** (a) CapSight on screen: `MainActivity.onKeyDown` watches them without consuming
  them, while the service's MediaSession is not active. With CapSight on screen a missing permission opens the
  normal start flow for that wish. (b) Navigating, screen on or off: `NavigationService` holds a `MediaSession`
  with REMOTE playback + `VolumeProvider` ("playing"), so Android routes volume keys to the app. (c) **Standby**:
  once CapSight has been opened (safety statement accepted) and while the setting is on, `MainActivity` starts
  `NavigationService` in standby: foreground type connectedDevice, ongoing notification "CapSight ready. Volume
  up twice to start." with **Exit CapSight** (navigating: "CapSight is guiding you" + Stop navigation / Exit CapSight). Nothing runs in standby (no camera, YOLO, depth, BLE scan, wake lock
  or Wi-Fi lock); the MediaSession is active only while the screen is off or the phone is locked, so with the
  phone unlocked in another app the volume keys behave exactly as without CapSight. A short partial wake lock
  (4 s, timeout) per key press keeps the 500 ms decision on time with the screen off. Stop (any way) returns to
  standby; Exit (notification, also on the navigation notification) or switching the setting off ends it.
- **Camera from the pocket, honestly:** Android limits camera use by apps that are not on screen
  (while-in-use rule; API 34+ also refuses adding the camera service type from the background). Up x3 from
  standby with the phone locked may therefore be refused. The service detects it (startForeground refused,
  CameraX critical error, or no frame within 5 s) and continues landmarks only with the "Camera is not
  available..." phrase; it never crashes or goes silent. Before the fallback a "Camera disconnected. Please
  stop." may be heard once. Reliable camera start: unlock the phone (CapSight on screen) and press up x3.
- **Decision log:** gesture starts, stops, status and replies are `control` rows (kind `control`, reason e.g.
  "volume down x2: stop"), only while a session log is open (nothing is logged while stopped).
- Known limits, verify per phone: some Android 13+ builds ignore remote sessions without a media route;
  another app playing media may take the volume keys while it plays (then its volume changes, no gesture);
  holding a key for exactly ~0.5 s (one auto-repeat) can look like a double press; raising the volume quickly
  by exactly two or three steps is a gesture (the volume is restored).

---

## 9. Requirements map

SRS ids (see `docs/SRS_Camera_Navigation.pdf`): M1 FR-CAM (firmware), M2 FR-RX, M3 FR-DET,
M4 FR-OBS, M5 FR-DIST, M6 FR-PATH, M7 FR-NAV, M8 FR-VOICE, M9 FR-APP, NFR-*. Mention the
ids you implement in commit messages and test names. Acceptance tests AT-01…AT-10 are run on
the taped test course described in the build guide, step 10.

Build order — Phase A (each step usable on its own; the cap does not exist yet, see §1A):
1. Frame sources (`PhoneCameraSource`, `VideoFileSource`, `MjpegFrameSource`) + `tools/fake_esp32cam.py`
   + health probe + network binding + configurable camera URL + "Camera disconnected" (M2)
2. YOLO detection with on-screen boxes in the debug view (M3)
3. Speaker with priorities, cooldowns and earphone output (M8)
4. Obstacles + distance (M4, M5)
5. Zones / free side (M6)
6. decide() + 10 Hz loop + its unit tests (M7)
7. Foreground service, settings, accessible main screen (M9)
8. BLE landmarks (§4.6, added 18 Oct 2026): scanner in the service, pure proximity/announce
   logic with JVM tests, phrases through the existing Speaker, permissions (§6.1), debug-view line
   listing beacons seen with RSSI

Phase B (with the user, once parts are bought): firmware contract v2 (`/status`, `/imu`,
`ae_level` 0, cap-mount comments) → flash → bench test with the app → assemble the box →
mount on the cap → measure FOV (§8.1).

Phase C: switch the app to the real device, re-record `sample.mjpeg`, run AT-01…AT-10 on the
test course, tune thresholds, then follow the release checklist (§10).

---

## 10. Deployment

**Distribution:** signed **release APK**, sideloaded with `adb install -r` or shared as a file.
The app will **not** be published on the Play Store, so no AAB, store listing or Play policy work is needed.

**Signing:** release keystore lives **outside the repo** (e.g. `~/keystores/capsight.jks`).
Credentials go in `~/.gradle/gradle.properties` or a git-ignored `keystore.properties` read by
`app/build.gradle.kts`. Never commit a keystore or password. Losing the keystore means
existing installs cannot be updated — back it up.

**Versioning:** bump `versionCode` (+1) and `versionName` (semver) for every APK you hand
out. Record which firmware contract it needs (§4.4) in the release notes.

**Release build:** R8 is currently off (`optimization { enable = false }`). If enabled, add
keep rules for LiteRT in `app/src/main/keepRules/rules.keep` and re-test inference on device.

**Before handing an APK to anyone (release checklist):**
- [ ] Unit tests, lint and `assembleRelease` pass.
- [ ] Tested on **two phones**: one old/low-end (Android 7–9, ideally 32-bit) and one recent (Android 13+).
- [ ] First-run flow: notification permission, joining `BLENAV_CAM`, earphones, TalkBack labels.
- [ ] Screen off for 10 minutes: guidance continues, no crash.
- [ ] Pull the cap's power: "Camera disconnected. Please stop." within 2 s; reconnects by itself.
- [ ] Airplane mode with Wi-Fi on: everything works (no internet dependency).
- [ ] App works against the firmware version actually flashed on the demo cap.

**OEM battery killers (very common on Indian phones — Xiaomi/Redmi, Oppo, Vivo, Realme, Samsung):**
they kill background services despite the foreground notification. The setup screen must
guide the helper to: allow Autostart, set Battery to "No restrictions", lock the app in recents.
Do not depend on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` alone.

**Privacy:** frames are processed in memory and never saved or uploaded. Decision logs go to
app-specific storage only (no storage permission). The only network traffic is to 192.168.4.1.

**Safety statement** (must appear in the app's About/first-run screen and docs): CapSight
supplements the white cane; it does not replace it.

---

## 11. Working conventions
- Kotlin official style; Compose for all UI; `StateFlow` for UI state.
- Log tag `CapSight`. No `println`.
- Every pure-logic class gets JVM unit tests; parser tests use a recorded stream sample
  (record it from the fake device in Phase A, from the real camera in Phase C:
  `curl --max-time 5 http://<camera-ip>:81/stream -o app/src/test/resources/sample.mjpeg`).
- No magic numbers in logic — thresholds come from settings with the defaults in §8.
- Keep this file current: when a decision in §1, the device contract in §4 or the deployment
  rules in §10 change, update this file in the same change.
