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
| BLE / ceiling camera | **Removed.** | The old Phase-2 design. Kept only as reference code (§9). |
| Distance method | Apparent object size (ported from `egocentric.py`), later fused with ground-plane geometry once the IMU exists. | Works with no calibration rig; proven in Phase 2. |
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
- `GET http://192.168.4.1/imu` → `{"pitch_deg":-28.5,"roll_deg":1.2,"t_ms":123456}` (negative pitch = looking down)
- MPU6050 wiring (from the build guide): VCC→3V3, GND→GND, **SCL→IO14, SDA→IO15**, so in firmware `Wire.begin(15, 14)`. No SD card may be inserted (IO14/15 are shared with it).
- While editing firmware for the cap: set `ae_level` to 0 (the +1 bias was for a ceiling mount), and update the header comment from "ceiling camera" to "cap camera".

### 4.5 Flashing the firmware
Arduino IDE 2.x, board package "esp32 by Espressif Systems", Board **AI Thinker ESP32-CAM**,
Partition **Huge APP (3MB No OTA)**, 115200 baud, via the ESP32-CAM-MB programmer. Full steps
with pictures: `docs/Hardware_Build_Guide.pdf`, step 2.

---

## 5. App architecture

Pipeline (SRS §3): **receive → detect → obstacles + distance → path → decide → speak**.

Package plan (sub-packages of `com.example.capsight`):
```
camera/      M2  FrameSource interface; MjpegFrameSource (ESP32); PhoneCameraSource and
                 VideoFileSource for testing without the cap; CameraNetwork (Wi-Fi binding)
detect/      M3  YoloDetector (LiteRT), Letterbox, Nms, CocoLabels
perception/  M4,M5  Obstacle filtering, size-based distance (port of egocentric.py),
                 relative geometry (port of world.py helpers)
path/        M6  ZoneAnalyzer: left / centre / right clearance
nav/         M7  decide() pure policy + GuidanceLoop (10 Hz ticker)
voice/       M8  Speaker (single pending slot, priority, cooldown), Haptics
service/         NavigationService — foreground service that owns the whole pipeline
settings/        AppSettings (DataStore) with the defaults in §8
ui/          M9  Compose screens: Main (Start/Stop), Settings, Debug overlay
```

Rules:
- `detect/` post-processing, `perception/`, `path/`, `nav/` are **pure Kotlin**: no `android.*`
  imports, no clocks (pass time in), no threads. They are JVM-unit-tested like the Python originals.
- The pipeline lives in `NavigationService`, **not** in an Activity or ViewModel, so it keeps
  running with the screen off. The UI only observes `StateFlow`s from the service.
- **Newest-frame-only**: frames flow through a conflated channel / `MutableStateFlow`. Never queue frames.
- Threads: stream reading on `Dispatchers.IO`; inference on one dedicated single-thread
  dispatcher (the interpreter is not thread-safe); the 10 Hz decision ticker on its own coroutine.
- Use `SystemClock.elapsedRealtime()` for timing, not wall-clock time.
- Fail safe (FR-NAV-05): any exception in a tick → say "Stop" and log it. Never go silent while running.

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
  connectedDevice only. The service also holds a partial wake lock and a low-latency Wi-Fi lock.
- TTS on API 30+ needs package visibility, or the engine is invisible:
  `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`
- Do **not** request location permission. We never read the SSID; we identify the camera with
  the HTTP health probe (§4.1).

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
| User height | 165 cm | camera height ≈ height − 10 cm (for later ground-plane distance) |

Priority policy (lower wins) — SRS §4.7 wording is authoritative for speech:
| P | Trigger | Speech |
|---|---|---|
| 0 | obstacle ≤ stop distance in corridor, **or camera lost > 2 s** | "Stop. Chair ahead." / "Camera disconnected. Please stop." |
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
