/*
 * CapSight BLE landmark beacon (ESP32 Dev Module or any plain ESP32).
 *
 * Place one at a doorway, the top of a staircase, a lift, a washroom... The CapSight app scans
 * for these and, when the user comes within a few metres, speaks the landmark once:
 *   "Room 304 door nearby."   "Caution. Stairs nearby."
 *
 * Contract (CLAUDE.md §4.6, "BLE landmarks v1"): service UUID in the advertising packet, and in the
 * scan response the Complete Local Name
 *     CS:<TYPE>:<LABEL>
 *   TYPE  = DOOR | STAIRS | LIFT | WASHROOM | EXIT | ROOM | OTHER
 *   LABEL = free text, may be empty, keep the whole name <= 26 characters
 *
 * TO SET UP A BEACON: change LANDMARK_TYPE and LANDMARK_LABEL below, then flash.
 *   Arduino IDE -> Board "ESP32 Dev Module" -> Upload. Power it from any USB charger / power bank.
 */
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <esp_bt.h>

// ---------------------------------------------------------------- edit these two lines
#define LANDMARK_TYPE  "DOOR"        // DOOR, STAIRS, LIFT, WASHROOM, EXIT, ROOM or OTHER
#define LANDMARK_LABEL "Room 304"    // spoken as written; "" for none
// ---------------------------------------------------------------------------------------

static const char *BEACON_NAME = "CS:" LANDMARK_TYPE ":" LANDMARK_LABEL;

// Same value as Landmark.SERVICE_UUID in the app. Never change one without the other.
#define CAPSIGHT_SERVICE_UUID "6c616e64-6d61-726b-4353-000000000001"

void setup() {
  Serial.begin(115200);
  delay(400);

  if (strlen(BEACON_NAME) > 26) {
    Serial.printf("[beacon] NAME TOO LONG (%u chars, max 26): %s\n", (unsigned)strlen(BEACON_NAME), BEACON_NAME);
    Serial.println("[beacon] shorten LANDMARK_LABEL and flash again.");
    while (true) delay(1000);
  }

  BLEDevice::init(BEACON_NAME);

  // Maximum transmit power (+9 dBm) so the phone hears it reliably across a corridor.
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);

  // Advertising packet: flags + the CapSight service UUID. The phone filters its scan on this UUID,
  // because Android pauses unfiltered scans while the screen is off. Both do not fit in 31 bytes
  // together with the name, so the name goes in the scan response (the phone scans actively).
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData;
  advData.setFlags(0x06);              // LE General Discoverable, BR/EDR not supported
  advData.setCompleteServices(BLEUUID(CAPSIGHT_SERVICE_UUID));
  adv->setAdvertisementData(advData);

  BLEAdvertisementData scanData;
  scanData.setName(BEACON_NAME);
  adv->setScanResponseData(scanData);
  adv->setScanResponse(true);

  // 100 ms advertising interval: the phone sees several packets per second for stable RSSI.
  adv->setMinInterval(0x00A0);
  adv->setMaxInterval(0x00A0);
  adv->start();

  Serial.println();
  Serial.println("=========================================");
  Serial.println("  CapSight BLE landmark advertising");
  Serial.printf ("  Name : %s\n", BEACON_NAME);
  Serial.printf ("  MAC  : %s\n", BLEDevice::getAddress().toString().c_str());
  Serial.println("  TX   : +9 dBm, every 100 ms");
  Serial.println("=========================================");
}

void loop() {
  delay(10000);
  Serial.printf("[beacon] advertising %s\n", BEACON_NAME);
}
