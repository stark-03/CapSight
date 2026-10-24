# CapSight: student handover guide

CapSight is an Android app that helps a blind or low-vision person walk safely indoors.

- **The phone camera** spots obstacles (chairs, people, doors, stairs, walls) and the app speaks short instructions such as *"Stop. Chair ahead."* or *"Person ahead. Move left."*
- **Small ESP32 boards called BLE beacons**, placed at doors, stairs and rooms, tell the user where they are. For example: *"Room 304 door nearby, about 3 metres."*
- **The camera is optional.** Without it the app still announces the landmarks.

**Safety:** CapSight supplements the white cane; it never replaces it. Say this in every demo and in the report.

---

## 1. What you need

| Item | Notes |
|---|---|
| Android phone, Android 7.0 or newer | Tested on a Pixel 9a (Android 17). Bluetooth earphones are optional |
| `CapSight` APK | The latest is in the `APK Backup` folder, or build it yourself (section 6) |
| One ESP32 board per landmark | A plain **ESP32 DevKit** is easiest. The team's **ESP32-CAM on its MB programmer board** also works as a beacon |
| A USB phone charger or power bank per beacon | See the power bank warning in section 7 |
| Laptop with Arduino IDE 2.x | Only for flashing the beacons |

---

## 2. Install the app

1. Copy the APK to the phone (USB cable, or Drive / WhatsApp sent **as a document**), open it, and allow "Install unknown apps".
2. Open **Cap Sight** and accept the safety statement ("I understand", the big yellow button at the bottom). A spoken welcome explains the volume buttons; tap **Got it** at the bottom. **Help** on the main screen replays it at any time.
3. When asked, allow:
   - **Notifications**, so the Stop button shows in the notification;
   - **Nearby devices** and **Location → Precise → While using the app**, needed for beacons. Android only lets an app scan for Bluetooth beacons if it also has precise location; CapSight never reads where you are;
   - **Camera**, only if you want obstacle detection.
4. Keep **Bluetooth** and **Location** switched on in the phone's quick settings.

**The main screen** (high-contrast, large text, works with TalkBack):
- **When stopped:** two big buttons, **Start with camera** (top, yellow) and **Start without camera** (bottom).
- **When running:** one big **Stop navigation** button.
- **Always available:** **Help**, **Repeat status**, and a small **Helper settings** button at the bottom for teachers. Helper settings has all options, calibration and the Debug view.

The on-screen buttons do exactly what the volume gestures do.

---

## 3. Using it: the volume buttons

A blind user can do everything with the volume buttons, even with the screen off and the phone in a pocket, once the app has been opened at least once.

| Press | What happens | What the user hears |
|---|---|---|
| **Volume up, 2 quick presses** | Start **without camera** (beacons only) | "Navigation started without camera. To use the camera, press volume up three times." |
| **Volume up, 3 quick presses** | Start **with camera** (or switch the camera on) | "Navigation started with camera. Press volume down twice to stop." |
| **Volume down, 2 quick presses** | **Stop** | "Navigation stopped. Press volume up twice to start." |
| **Volume down, 3 quick presses** | **Status**: mode and nearest landmark | e.g. "Navigating with camera. Room 304 door, about 3 metres." |

- Presses must be quick, **less than half a second apart**.
- A single press still changes the volume as normal. After a recognised gesture the volume goes back to where it was.
- **With the phone locked, Android may refuse to open the camera.** The app then says so and carries on without the camera. To start with the camera reliably, unlock the phone, open CapSight and press volume up 3 times, or use the on-screen button.
- While the app is ready in the background, a notification says **"CapSight ready – press volume up twice to start"**. Use **Exit** in that notification to close the app completely.

---

## 4. Setting up a beacon (ESP32)

1. In Arduino IDE, install the board package **"esp32 by Espressif Systems"** (Boards Manager).
2. Open `firmware/ble_landmark/ble_landmark.ino` and edit the two lines at the top:
   ```cpp
   #define LANDMARK_TYPE  "DOOR"        // DOOR, STAIRS, LIFT, WASHROOM, EXIT, ROOM or OTHER
   #define LANDMARK_LABEL "Room 304"    // spoken as written; "" for none
   ```
   The whole name `CS:TYPE:LABEL` must be 26 characters or fewer.
3. Board: **ESP32 Dev Module** for a plain ESP32. For the ESP32-CAM, use **AI Thinker ESP32-CAM** with Partition **Huge APP**. Then Upload.
   - If upload says "Failed to connect", hold **IO0** (or **BOOT**), tap **RST**, release, and upload again.
   - On Linux, "Permission denied /dev/ttyUSB0" means you need to run `sudo usermod -aG dialout $USER`, then log out and back in.
4. In the Serial Monitor (115200 baud) you should see `CapSight BLE landmark advertising  Name : CS:DOOR:Room 304`.
5. Power it from a charger or power bank and mount it at about 1.2–1.5 m height. Keep the **antenna end** (the zigzag gold track) away from metal and cables.
6. **Calibrate once per beacon:** hold the phone exactly **1 m** from it, start navigation, then open **Helper settings → BLE landmarks** and tap **"Calibrate at 1 m"**. The same button is also in the Debug view.

**What the user hears when walking towards a beacon:**
- first *"Room 304 door nearby, about 5 metres"*;
- then *"…about 3 metres"* and *"…about 2 metres"* as they get closer;
- and finally *"Room 304 door, you are here."*

A **STAIRS** beacon says *"Caution. Stairs nearby…"* and uses the urgent priority.

**Accuracy (put this in the report):** Bluetooth distance is approximate.
- At 1–3 m it is typically within about ±1 m. Farther away, or through walls, bodies or a pocket, it is much less accurate.
- It cannot tell direction.

---

## 5. The optional cap camera (ESP32-CAM)

The app can also use an ESP32-CAM mounted on a cap instead of the phone camera.

- **Firmware:** flash `firmware/esp32_cam_ap/esp32_cam_ap.ino` (AI Thinker ESP32-CAM, Huge APP). Choose **"ESP32-CAM over Wi-Fi"** as the source in Helper settings.
- **The phone must join** the Wi-Fi `BLENAV_CAM` (password `blenav1234`).
- **Keep the camera clock at 8 MHz** (`xclk`), as the firmware does now. At 10–20 MHz this board's camera jams its own Wi-Fi antenna and the video stalls. Check speed with `tools/stream_check.sh`.
- **One job at a time:** a board is either the camera or a beacon. Re-flash to switch.
- Full hardware steps and wiring: `docs/Hardware_Build_Guide.pdf` and `docs/Build_Steps_3D.html`.

---

## 6. For developers

- **Source:** `/home/sourab/AndroidStudioProjects/CapSight` (Kotlin, Jetpack Compose). Git remote `github.com/stark-03/CapSight`.
- **Read `CLAUDE.md` first:** it records every design decision, the device contracts, Android-version pitfalls and default thresholds.
- **Build:** use Android Studio's bundled JDK.
  ```bash
  ./gradlew testDebugUnitTest assembleDebug lint   # all unit tests, APK, lint
  ./gradlew installDebug                           # install on a USB-connected phone
  adb logcat -s CapSight                           # app log
  ```
- **Code map (`app/src/main/java/com/example/capsight/`):**
  - `camera/`: frame sources (phone camera, ESP32 stream, video file).
  - `detect/`: YOLO (COCO + hazard model).
  - `depth/`: MiDaS walls / unknown obstacles.
  - `perception/`, `path/`, `nav/`: distances, left/centre/right, the decision rules.
  - `voice/`: speech priorities and phrases.
  - `ble/`: beacons and distance.
  - `control/`: volume gestures.
  - `service/`: the background navigation service.
  - `ui/`: screens.
  - `record/`: decision log.
- **Models (`app/src/main/assets/`):** `yolov8n_320_fp32.tflite` (everyday objects), `hazards_320_fp32.tflite` (door, stairs, pole, sign, pothole; trained by us), `midas_small_256_fp32.tflite` (depth).
- **Retraining:** the hazard pipeline is in `ml/hazards/` (Colab notebook). The depth evaluation is in `ml/depth/`.
- **Decision log for the report:** Helper settings → Debug view → Evaluation → **Share decision log** gives a CSV of every spoken message with time, reason, distances and frame rates.

---

## 7. Known limitations and troubleshooting

| Symptom | Cause / fix |
|---|---|
| Beacon never announced | Location permission must be **Precise**, and Location and Bluetooth must be on. The debug view's `BLE:` line says exactly what is missing |
| Beacon stops after a few minutes | Many **power banks switch off** at low current (a beacon uses ~0.1 A). Use the bank's low-current / always-on mode or a wall charger |
| Same landmark not repeated | By design: once per 60 s, and again only after walking away (below -80 dBm) and coming back |
| False "Stop. Obstacle ahead." in corridors | The depth model reads pillars as near. Raise **Depth threshold** in Helper settings |
| Wall with a window not detected | Glass reads as far to the depth model. Known limitation |
| Hazard model misses poles and potholes | Weak classes (mAP50 22–32%); doors, stairs and signs are better (48–53%) |
| "Camera is not available" even with the phone unlocked | The camera permission was refused twice, so Android no longer asks. **Helper settings → Phone setup → Allow camera and other permissions** → Permissions → Camera → Allow while using the app |
| Camera won't start from the locked phone | Android rule; unlock and use volume up x3 or the on-screen button |
| ESP32-CAM video stalls | Camera clock must be 8 MHz (see section 5) |

**Accuracy figures** (held-out test images): everyday objects use the standard YOLOv8n (COCO); the hazard model scores overall mAP50 41% (door 53%, stairs 49%, sign 48%, pothole 32%, pole 22%).

**Still to verify in a full walking test:** distance announcements at walking pace, landmarks with the screen off, and the volume gestures on other phone brands.
