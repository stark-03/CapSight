/*
 * BLENAV_CAM - ESP32-CAM as the ceiling camera for the navigation system.
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

// ------------------------------------------------------------ MJPEG stream
static esp_err_t stream_handler(httpd_req_t *req) {
  camera_fb_t *fb = NULL;
  esp_err_t res = ESP_OK;
  char part_buf[64];

  res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

  while (true) {
    fb = esp_camera_fb_get();
    if (!fb) { res = ESP_FAIL; break; }

    size_t hlen = snprintf(part_buf, sizeof(part_buf), STREAM_PART, fb->len);
    res = httpd_resp_send_chunk(req, part_buf, hlen);
    if (res == ESP_OK)
      res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
    if (res == ESP_OK)
      res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));

    esp_camera_fb_return(fb);
    if (res != ESP_OK) break;      // client disconnected
  }
  return res;
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
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, page, strlen(page));
}

static void start_servers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  cfg.ctrl_port   = 32768;
  httpd_uri_t index_uri = { "/", HTTP_GET, index_handler, NULL };
  if (httpd_start(&index_httpd, &cfg) == ESP_OK)
    httpd_register_uri_handler(index_httpd, &index_uri);

  // The stream lives on its own port so a slow browser tab cannot block it.
  cfg.server_port = 81;
  cfg.ctrl_port   = 32769;
  httpd_uri_t stream_uri = { "/stream", HTTP_GET, stream_handler, NULL };
  if (httpd_start(&stream_httpd, &cfg) == ESP_OK)
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

  camera_config_t config;
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
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;

  // CAMERA_GRAB_LATEST matters: it throws away stale frames instead of queuing
  // them, which is the same newest-frame-only policy the Python grabber uses.
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = CAMERA_FB_IN_PSRAM;

  if (psramFound()) {
    config.frame_size   = FRAMESIZE_VGA;   // 640x480 - matches the project
    config.jpeg_quality = 12;              // lower number = better = bigger
    config.fb_count     = 2;
  } else {
    // No PSRAM: fall back hard, otherwise the allocator fails at init.
    config.frame_size   = FRAMESIZE_QVGA;  // 320x240
    config.jpeg_quality = 15;
    config.fb_count     = 1;
    config.fb_location  = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("\n[cam] INIT FAILED 0x%x\n", err);
    Serial.println("[cam] check the ribbon cable is fully seated and latched.");
    delay(3000);
    ESP.restart();
  }

  // Sensor-specific correction. The OV3660 ships upside down and oversaturated.
  sensor_t *s = esp_camera_sensor_get();
  if (s->id.PID == OV3660_PID) {
    s->set_vflip(s, 1);
    s->set_brightness(s, 1);
    s->set_saturation(s, -2);
  }
  // Mounted high and tilted down, the ceiling is bright and the floor is dark.
  // Bias the exposure towards the floor, which is the half that matters.
  s->set_gain_ctrl(s, 1);
  s->set_exposure_ctrl(s, 1);
  s->set_ae_level(s, 1);

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
