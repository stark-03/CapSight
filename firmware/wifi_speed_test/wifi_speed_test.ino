/*
 * CapSight - Wi-Fi speed test for the ESP32-CAM, with the CAMERA SWITCHED OFF.
 *
 * Purpose: find out whether the slow stream is caused by the board's Wi-Fi itself
 * or by the camera interfering with it. Same hotspot, password, IP and URLs as the
 * real firmware (esp32_cam_ap), so tools/stream_check.sh works unchanged, but every
 * "frame" is a fixed 20 KB dummy JPEG from memory - the camera is never started.
 *
 *   Fast here (>= 10 frames/s)  -> board Wi-Fi is fine; the camera is the problem.
 *   Slow here too               -> board radio / antenna / power problem (hardware).
 *
 * The Serial Monitor (115200) also shows how strongly the BOARD hears each connected
 * device (RSSI, dBm). Around -30..-60 is good, below -75 is weak.
 *
 * Flash exactly like esp32_cam_ap (AI Thinker ESP32-CAM, Huge APP). Re-flash
 * esp32_cam_ap afterwards to get the camera back.
 */
#include <WiFi.h>
#include "esp_http_server.h"
#include "esp_wifi.h"
#include "esp_timer.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

const char *AP_SSID = "BLENAV_CAM";
const char *AP_PASS = "blenav1234";
const int   AP_CHANNEL = 6;

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static const size_t FRAME_BYTES = 20000;     // about one VGA frame from the real camera
static uint8_t frame[FRAME_BYTES];

httpd_handle_t index_httpd = NULL;
httpd_handle_t stream_httpd = NULL;

static esp_err_t index_handler(httpd_req_t *req) {
  static const char page[] =
    "<!doctype html><html><head><title>BLENAV_CAM</title></head>"
    "<body><h2>BLENAV_CAM is live (Wi-Fi speed test, camera off)</h2></body></html>";
  Serial.println("[http] GET /");
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, page, strlen(page));
}

static esp_err_t stream_handler(httpd_req_t *req) {
  char part_buf[64];
  esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;
  Serial.println("[http] stream client connected (dummy frames)");

  uint32_t frames = 0;
  int64_t t0 = esp_timer_get_time(), lastReport = t0;
  while (true) {
    size_t hlen = snprintf(part_buf, sizeof(part_buf), STREAM_PART, (unsigned)FRAME_BYTES);
    res = httpd_resp_send_chunk(req, part_buf, hlen);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)frame, FRAME_BYTES);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
    if (res != ESP_OK) break;
    frames++;
    int64_t now = esp_timer_get_time();
    if (now - lastReport >= 5000000) {
      float secs = (now - t0) / 1e6f;
      Serial.printf("[http] streaming: %lu frames, %.1f fps, %.0f KB/s\n",
                    (unsigned long)frames, frames / secs, frames * (FRAME_BYTES / 1024.0f) / secs);
      lastReport = now;
    }
  }
  float secs = (esp_timer_get_time() - t0) / 1e6f;
  Serial.printf("[http] stream client gone (%s) after %.1f s, %lu frames, %.1f fps\n",
                esp_err_to_name(res), secs, (unsigned long)frames, secs > 0 ? frames / secs : 0);
  return res;
}

static void start_servers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  cfg.ctrl_port = 32768;
  httpd_uri_t index_uri = { "/", HTTP_GET, index_handler, NULL };
  esp_err_t err = httpd_start(&index_httpd, &cfg);
  Serial.printf("[http] port 80 server start: %s\n", esp_err_to_name(err));
  if (err == ESP_OK) httpd_register_uri_handler(index_httpd, &index_uri);

  cfg.server_port = 81;
  cfg.ctrl_port = 32769;
  httpd_uri_t stream_uri = { "/stream", HTTP_GET, stream_handler, NULL };
  err = httpd_start(&stream_httpd, &cfg);
  Serial.printf("[http] port 81 server start: %s\n", esp_err_to_name(err));
  if (err == ESP_OK) httpd_register_uri_handler(stream_httpd, &stream_uri);
}

void setup() {
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);   // same as the real firmware
  Serial.begin(115200);
  delay(400);

  // A minimal valid-looking JPEG: start marker, filler, end marker.
  memset(frame, 0x55, FRAME_BYTES);
  frame[0] = 0xFF; frame[1] = 0xD8;
  frame[FRAME_BYTES - 2] = 0xFF; frame[FRAME_BYTES - 1] = 0xD9;

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL);
  delay(300);
  start_servers();

  wifi_bandwidth_t bw;
  esp_wifi_get_bandwidth(WIFI_IF_AP, &bw);
  Serial.println();
  Serial.println("==== Wi-Fi SPEED TEST (camera OFF) ====");
  Serial.printf("  SSID %s  ch %d  bandwidth %s  tx power %.1f dBm\n", AP_SSID, AP_CHANNEL,
                bw == WIFI_BW_HT40 ? "HT40" : "HT20", (int)WiFi.getTxPower() / 4.0f);
  Serial.println("  Run: bash tools/stream_check.sh");
  Serial.println("=======================================");
}

void loop() {
  static uint32_t last = 0;
  if (millis() - last > 5000) {
    last = millis();
    wifi_sta_list_t list;
    if (esp_wifi_ap_get_sta_list(&list) == ESP_OK) {
      Serial.printf("[wifi] up %lus | clients %d", millis() / 1000, list.num);
      for (int i = 0; i < list.num; i++) Serial.printf(" | client %d hears at %d dBm", i + 1, list.sta[i].rssi);
      Serial.println();
    }
  }
  delay(50);
}
