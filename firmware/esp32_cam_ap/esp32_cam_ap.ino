/*
 * BLENAV_CAM - ESP32-CAM as the cap camera for the CapSight navigation app.
 *
 * Creates its OWN WiFi hotspot and serves an MJPEG stream. The laptop joins
 * that hotspot directly, so the demo does not depend on college WiFi (which
 * usually isolates clients from each other and would silently break the feed).
 *
 * BOARD: AI-Thinker ESP32-CAM  ·  SENSOR: OV3660 or OV2640 (both handled)
 *
 * ---------------------------------------------------------------------------
 * FLASHING  (the ESP32-CAM has no USB port - you need a programmer)
 *
 *   Option 1 - ESP32-CAM-MB shield: clip the board on, plug in USB. Done.
 *   Option 2 - FTDI / CP2102 USB-TTL adapter, wired:
 *
 *       FTDI 5V   -> ESP32-CAM 5V
 *       FTDI GND  -> ESP32-CAM GND
 *       FTDI TX   -> ESP32-CAM U0R
 *       FTDI RX   -> ESP32-CAM U0T
 *       JUMPER    -> IO0 to GND        (REQUIRED to enter flash mode)
 *
 *   Arduino IDE:
 *     Boards Manager -> install "esp32 by Espressif Systems"
 *     Board          -> "AI Thinker ESP32-CAM"
 *     Partition      -> "Huge APP (3MB No OTA)"
 *     Upload speed   -> 115200
 *
 *   Press the on-board RESET button, then Upload.
 *   AFTER UPLOADING: remove the IO0-GND jumper and press RESET again,
 *   otherwise the board just sits in flash mode and never runs.
 *
 * ---------------------------------------------------------------------------
 * USING IT
 *
 *   1. Power the board (5V power bank recommended - see note below).
 *   2. On the laptop, join the WiFi network:  BLENAV_CAM
 *      password: blenav1234
 *   3. Check it in a browser first:  http://192.168.4.1
 *   4. Run the project:
 *        python -m blenav.main --source http://192.168.4.1:81/stream --fake-ble
 *
 * POWER: brownouts are the number one failure of this board. Use a 5V supply
 * that can deliver 500 mA (a phone power bank is ideal). A laptop USB port
 * through a long thin cable often is not enough, and the board will reboot
 * every few seconds under streaming load.
 */

#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"
#include "esp_timer.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ---------------------------------------------------------------- hotspot
const char *AP_SSID = "BLENAV_CAM";
const char *AP_PASS = "blenav1234";      // must be >= 8 characters
const int   AP_CHANNEL = 6;              // 1, 6 or 11 - least crowded wins

// ------------------------------------------------- AI-Thinker ESP32-CAM pins
#define PWDN_GPIO_NUM   32
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM    0
#define SIOD_GPIO_NUM   26
#define SIOC_GPIO_NUM   27
#define Y9_GPIO_NUM     35
#define Y8_GPIO_NUM     34
#define Y7_GPIO_NUM     39
#define Y6_GPIO_NUM     36
#define Y5_GPIO_NUM     21
#define Y4_GPIO_NUM     19
#define Y3_GPIO_NUM     18
#define Y2_GPIO_NUM      5
#define VSYNC_GPIO_NUM  25
#define HREF_GPIO_NUM   23
#define PCLK_GPIO_NUM   22

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_CONTENT_TYPE =
    "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART =
    "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

httpd_handle_t stream_httpd = NULL;
httpd_handle_t index_httpd  = NULL;

// ------------------------------------------------------- camera settings
// Changeable at runtime through GET /control (see control_handler), so camera
// settings can be tuned without re-flashing. Defaults = original behaviour.
struct CamSettings {
  int xclk_mhz;        // camera clock. 20 is the usual value; lower can reduce Wi-Fi interference
  int fb_count;        // frame buffers in PSRAM (1 or 2)
  bool grab_latest;    // true = CAMERA_GRAB_LATEST, false = CAMERA_GRAB_WHEN_EMPTY
  framesize_t size;    // FRAMESIZE_QVGA (320x240) ... FRAMESIZE_VGA (640x480)
  int quality;         // JPEG quality 10..63, lower number = better = bigger
};
// 8 MHz camera clock is REQUIRED on this board: at 10-20 MHz the clock interferes with
// the on-board antenna and the stream stalls after ~2 packets (measured 6 Oct / 14 Oct 2026:
// 20 MHz = 0 fps, 8 MHz = 11 fps). 320x240 is what the app's detector uses anyway.
static CamSettings cam = { 8, 2, true, FRAMESIZE_QVGA, 12 };
static bool camReady = false;
static volatile bool streamActive = false;
static volatile bool reconfiguring = false;

static const char *size_name(framesize_t s) {
  switch (s) {
    case FRAMESIZE_QQVGA: return "qqvga";
    case FRAMESIZE_QVGA:  return "qvga";
    case FRAMESIZE_CIF:   return "cif";
    case FRAMESIZE_HVGA:  return "hvga";
    case FRAMESIZE_VGA:   return "vga";
    default:              return "other";
  }
}

static bool parse_size(const char *v, framesize_t *out) {
  if (!strcmp(v, "qqvga")) { *out = FRAMESIZE_QQVGA; return true; }   // 160x120
  if (!strcmp(v, "qvga"))  { *out = FRAMESIZE_QVGA;  return true; }   // 320x240
  if (!strcmp(v, "cif"))   { *out = FRAMESIZE_CIF;   return true; }   // 400x296
  if (!strcmp(v, "hvga"))  { *out = FRAMESIZE_HVGA;  return true; }   // 480x320
  if (!strcmp(v, "vga"))   { *out = FRAMESIZE_VGA;   return true; }   // 640x480
  return false;
}

static esp_err_t start_camera(const CamSettings &st) {
  camera_config_t config = {};   // zero every field, including ones this sketch does not set
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;   config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;   config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;   config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;   config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;   config.pin_pclk  = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM; config.pin_href  = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;   config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = st.xclk_mhz * 1000000;
  config.pixel_format = PIXFORMAT_JPEG;
  // LATEST throws away stale frames instead of queuing them (newest-frame-only).
  config.grab_mode = st.grab_latest ? CAMERA_GRAB_LATEST : CAMERA_GRAB_WHEN_EMPTY;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.frame_size = st.size;
  config.jpeg_quality = st.quality;
  config.fb_count = st.fb_count;
  if (!psramFound()) {
    // No PSRAM: fall back hard, otherwise the allocator fails at init.
    config.frame_size  = FRAMESIZE_QVGA;
    config.jpeg_quality = 15;
    config.fb_count    = 1;
    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) return err;

  // Sensor-specific correction. The OV3660 ships upside down and oversaturated.
  sensor_t *s = esp_camera_sensor_get();
  if (s->id.PID == OV3660_PID) {
    s->set_vflip(s, 1);
    s->set_brightness(s, 1);
    s->set_saturation(s, -2);
  }
  // Bias the exposure towards the floor, which is the half that matters.
  s->set_gain_ctrl(s, 1);
  s->set_exposure_ctrl(s, 1);
  s->set_ae_level(s, 1);
  return ESP_OK;
}

// ------------------------------------------------------------ MJPEG stream
// Diagnostic source modes (GET /stream?src=...):
//   camera (default) send each camera frame straight from its PSRAM buffer
//   copy             copy each frame into internal RAM, release the camera buffer, then send the copy
//   dummy            camera keeps running, but send a fixed 20 KB frame from internal RAM
static const size_t TX_BUF_BYTES = 64 * 1024;
static uint8_t *txBuf = NULL;            // internal-RAM buffer for "copy" and "dummy"

static uint8_t *internal_tx_buffer() {
  if (!txBuf) {
    txBuf = (uint8_t *)heap_caps_malloc(TX_BUF_BYTES, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (txBuf) {
      memset(txBuf, 0x55, TX_BUF_BYTES);
      txBuf[0] = 0xFF; txBuf[1] = 0xD8;
    }
    Serial.printf("[http] internal tx buffer %s (%u bytes)\n", txBuf ? "ready" : "ALLOC FAILED",
                  (unsigned)TX_BUF_BYTES);
  }
  return txBuf;
}

static esp_err_t stream_handler(httpd_req_t *req) {
  camera_fb_t *fb = NULL;
  esp_err_t res = ESP_OK;
  char part_buf[64];

  if (reconfiguring || !camReady) {
    httpd_resp_set_status(req, "503 Service Unavailable");
    return httpd_resp_send(req, "camera restarting", HTTPD_RESP_USE_STRLEN);
  }

  char query[64], src[12] = "camera";
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK)
    httpd_query_key_value(query, "src", src, sizeof(src));
  const bool useCopy = !strcmp(src, "copy");
  const bool useDummy = !strcmp(src, "dummy");
  if ((useCopy || useDummy) && !internal_tx_buffer()) {
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_send(req, "no internal buffer", HTTPD_RESP_USE_STRLEN);
  }
  if (useDummy) {
    const size_t n = 20000;
    txBuf[n - 2] = 0xFF; txBuf[n - 1] = 0xD9;
  }

  res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  Serial.printf("[http] stream client connected (src=%s)\n", useCopy ? "copy" : useDummy ? "dummy" : "camera");
  streamActive = true;

  // Per-session counters, so the log shows whether frames actually reach the client.
  uint32_t frames = 0;
  uint64_t bytes = 0;
  int64_t t0 = esp_timer_get_time();
  int64_t lastReport = t0;

  while (true) {
    if (reconfiguring) break;      // /control is restarting the camera; end this stream
    const uint8_t *data;
    size_t len;
    if (useDummy) {
      data = txBuf;
      len = 20000;
    } else {
      fb = esp_camera_fb_get();
      if (!fb) { res = ESP_FAIL; break; }
      len = fb->len;
      if (useCopy) {
        if (len > TX_BUF_BYTES) {            // frame larger than the copy buffer: skip it
          esp_camera_fb_return(fb); fb = NULL;
          continue;
        }
        memcpy(txBuf, fb->buf, len);
        esp_camera_fb_return(fb); fb = NULL; // camera can capture the next frame while we send
        data = txBuf;
      } else {
        data = fb->buf;
      }
    }

    size_t hlen = snprintf(part_buf, sizeof(part_buf), STREAM_PART, (unsigned)len);
    res = httpd_resp_send_chunk(req, part_buf, hlen);
    if (res == ESP_OK)
      res = httpd_resp_send_chunk(req, (const char *)data, len);
    if (res == ESP_OK)
      res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));

    if (fb) { esp_camera_fb_return(fb); fb = NULL; }
    if (res != ESP_OK) break;      // client disconnected

    frames++;
    bytes += len;
    int64_t now = esp_timer_get_time();
    if (frames == 1)
      Serial.printf("[http] first frame sent: %u bytes\n", (unsigned)len);
    if (now - lastReport >= 5000000) {   // every 5 s
      float secs = (now - t0) / 1e6f;
      Serial.printf("[http] streaming: %lu frames, %.1f fps, %.0f KB/s\n",
                    (unsigned long)frames, frames / secs, bytes / 1024.0f / secs);
      lastReport = now;
    }
  }
  float secs = (esp_timer_get_time() - t0) / 1e6f;
  Serial.printf("[http] stream client gone (%s) after %.1f s, %lu frames sent\n",
                esp_err_to_name(res), secs, (unsigned long)frames);
  streamActive = false;
  return res;
}

// ------------------------------------------------------- settings endpoint
// GET /control                      -> current settings as JSON
// GET /control?xclk=10&fb=1&grab=empty&size=qvga&q=12  -> restart camera with them
static esp_err_t control_handler(httpd_req_t *req) {
  CamSettings next = cam;
  bool change = false, bad = false;
  char query[160], v[16];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    if (httpd_query_key_value(query, "xclk", v, sizeof(v)) == ESP_OK) {
      int x = atoi(v); if (x >= 6 && x <= 24) { next.xclk_mhz = x; change = true; } else bad = true;
    }
    if (httpd_query_key_value(query, "fb", v, sizeof(v)) == ESP_OK) {
      int f = atoi(v); if (f == 1 || f == 2) { next.fb_count = f; change = true; } else bad = true;
    }
    if (httpd_query_key_value(query, "grab", v, sizeof(v)) == ESP_OK) {
      if (!strcmp(v, "latest")) { next.grab_latest = true; change = true; }
      else if (!strcmp(v, "empty")) { next.grab_latest = false; change = true; }
      else bad = true;
    }
    if (httpd_query_key_value(query, "size", v, sizeof(v)) == ESP_OK) {
      if (parse_size(v, &next.size)) change = true; else bad = true;
    }
    if (httpd_query_key_value(query, "q", v, sizeof(v)) == ESP_OK) {
      int q = atoi(v); if (q >= 10 && q <= 63) { next.quality = q; change = true; } else bad = true;
    }
  }

  esp_err_t err = ESP_OK;
  if (change && !bad) {
    reconfiguring = true;
    for (int i = 0; i < 150 && streamActive; i++) vTaskDelay(pdMS_TO_TICKS(20));   // up to 3 s
    if (camReady) esp_camera_deinit();
    camReady = false;
    err = start_camera(next);
    if (err == ESP_OK) {
      cam = next;
    } else {
      Serial.printf("[cam] restart with new settings failed (%s), restoring previous\n", esp_err_to_name(err));
      start_camera(cam);
    }
    camReady = true;
    reconfiguring = false;
    Serial.printf("[cam] settings now xclk=%dMHz fb=%d grab=%s size=%s q=%d\n", cam.xclk_mhz,
                  cam.fb_count, cam.grab_latest ? "latest" : "empty", size_name(cam.size), cam.quality);
  }

  char json[200];
  snprintf(json, sizeof(json),
           "{\"ok\":%s,\"error\":\"%s\",\"xclk_mhz\":%d,\"fb\":%d,\"grab\":\"%s\",\"size\":\"%s\",\"q\":%d}",
           (!bad && err == ESP_OK) ? "true" : "false", bad ? "invalid value" : esp_err_to_name(err),
           cam.xclk_mhz, cam.fb_count, cam.grab_latest ? "latest" : "empty", size_name(cam.size), cam.quality);
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, json, HTTPD_RESP_USE_STRLEN);
}

// ----------------------------------------------------------- landing page
static esp_err_t index_handler(httpd_req_t *req) {
  static const char page[] =
    "<!doctype html><html><head><meta name=viewport "
    "content='width=device-width,initial-scale=1'>"
    "<title>BLENAV_CAM</title></head>"
    "<body style='font-family:sans-serif;text-align:center;background:#111;color:#eee'>"
    "<h2>BLENAV_CAM is live</h2>"
    "<p>Stream URL for the project:<br><code>http://192.168.4.1:81/stream</code></p>"
    "<img src='http://192.168.4.1:81/stream' style='max-width:100%;border:2px solid #555'>"
    "</body></html>";
  Serial.println("[http] GET /  (landing page / health probe)");
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, page, strlen(page));
}

static void start_servers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  cfg.ctrl_port   = 32768;
  httpd_uri_t index_uri = { "/", HTTP_GET, index_handler, NULL };
  esp_err_t err = httpd_start(&index_httpd, &cfg);
  Serial.printf("[http] port 80 server start: %s\n", esp_err_to_name(err));
  httpd_uri_t control_uri = { "/control", HTTP_GET, control_handler, NULL };
  if (err == ESP_OK) {
    httpd_register_uri_handler(index_httpd, &index_uri);
    httpd_register_uri_handler(index_httpd, &control_uri);
  }

  // The stream lives on its own port so a slow browser tab cannot block it.
  cfg.server_port = 81;
  cfg.ctrl_port   = 32769;
  httpd_uri_t stream_uri = { "/stream", HTTP_GET, stream_handler, NULL };
  err = httpd_start(&stream_httpd, &cfg);
  Serial.printf("[http] port 81 server start: %s\n", esp_err_to_name(err));
  if (err == ESP_OK)
    httpd_register_uri_handler(stream_httpd, &stream_uri);
}

// ------------------------------------------------------------------- setup
void setup() {
  // The single most common ESP32-CAM failure is a brownout reboot loop under
  // streaming load. Disabling the detector is the standard fix - but it is not
  // a substitute for a decent 5V supply.
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

  Serial.begin(115200);
  delay(400);
  Serial.setDebugOutput(false);

  esp_err_t err = start_camera(cam);
  if (err != ESP_OK) {
    Serial.printf("\n[cam] INIT FAILED 0x%x\n", err);
    Serial.println("[cam] check the ribbon cable is fully seated and latched.");
    delay(3000);
    ESP.restart();
  }
  camReady = true;

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL);
  delay(300);

  start_servers();

  Serial.println();
  Serial.println("=================================================");
  Serial.println("  BLENAV_CAM ready  (hotspot mode)");
  Serial.printf ("  Join WiFi : %s\n", AP_SSID);
  Serial.printf ("  Password  : %s\n", AP_PASS);
  Serial.print  ("  Preview   : http://"); Serial.println(WiFi.softAPIP());
  Serial.print  ("  Stream    : http://"); Serial.print(WiFi.softAPIP());
                 Serial.println(":81/stream");
  Serial.println("-------------------------------------------------");
  Serial.println("  Run on the laptop:");
  Serial.println("   python -m blenav.main \\");
  Serial.println("     --source http://192.168.4.1:81/stream --fake-ble");
  Serial.println("=================================================");
}

void loop() {
  static uint32_t last = 0;
  if (millis() - last > 10000) {
    last = millis();
    Serial.printf("[cam] up %lus | clients %d | free heap %u\n",
                  millis() / 1000, WiFi.softAPgetStationNum(), ESP.getFreeHeap());
  }
  delay(100);
}
