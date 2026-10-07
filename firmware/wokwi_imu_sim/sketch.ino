/*
 * CapSight - MPU6050 head-tilt test for the Wokwi simulator (and later the real board).
 *
 * Wokwi has no ESP32-CAM, so this runs on a plain ESP32 DevKit using the SAME GPIOs
 * the cap uses: SDA = IO15, SCL = IO14 (see docs/Hardware_Build_Guide.pdf, wiring page).
 *
 * It prints, 10 times a second, the exact JSON the planned firmware endpoint
 * GET http://192.168.4.1/imu will return (CLAUDE.md section 4.4):
 *   {"pitch_deg":-28.5,"roll_deg":1.2,"t_ms":123456}
 * Negative pitch = camera looking down.
 *
 * Mounting rule for the real device: stick the MPU6050 flat in the box with the
 * X arrow printed on the board pointing FORWARD (towards the camera lens).
 *
 * In Wokwi: click the MPU6050 while the simulation runs and move the acceleration
 * sliders to imitate tilting the head.
 *   Head level        : accel x = 0,     z = 1
 *   Looking 30 deg down: accel x = -0.5,  z = 0.87   -> pitch about -30
 */
#include <Wire.h>
#include <math.h>

const int SDA_PIN = 15;
const int SCL_PIN = 14;
const uint8_t MPU_ADDR = 0x68;
const float ALPHA = 0.2f;          // low-pass smoothing, 0..1 (higher = faster, noisier)

float pitchDeg = 0, rollDeg = 0;
bool imuOk = false;

static void writeReg(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

static bool readRegs(uint8_t reg, uint8_t *buf, size_t n) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)n) != n) return false;
  for (size_t i = 0; i < n; i++) buf[i] = Wire.read();
  return true;
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Wire.begin(SDA_PIN, SCL_PIN);

  uint8_t who = 0;
  imuOk = readRegs(0x75, &who, 1) && who == 0x68;   // WHO_AM_I
  if (!imuOk) {
    Serial.printf("[imu] MPU6050 NOT FOUND (WHO_AM_I=0x%02X). Check SDA->IO15, SCL->IO14, VCC->3V3, GND.\n", who);
    return;
  }
  writeReg(0x6B, 0x00);   // PWR_MGMT_1: wake up
  writeReg(0x1C, 0x00);   // ACCEL_CONFIG: +/-2 g
  writeReg(0x1A, 0x03);   // CONFIG: digital low-pass ~44 Hz
  Serial.println("[imu] MPU6050 ready on SDA=IO15, SCL=IO14");
}

void loop() {
  static uint32_t last = 0;
  if (!imuOk || millis() - last < 100) return;      // 10 Hz, same as the app's decision loop
  last = millis();

  uint8_t b[6];
  if (!readRegs(0x3B, b, 6)) {
    Serial.println("[imu] read failed");
    return;
  }
  float ax = (int16_t)(b[0] << 8 | b[1]) / 16384.0f;
  float ay = (int16_t)(b[2] << 8 | b[3]) / 16384.0f;
  float az = (int16_t)(b[4] << 8 | b[5]) / 16384.0f;

  // Gravity direction gives tilt. Nose-down makes ax negative, so pitch goes negative.
  float p = atan2f(ax, sqrtf(ay * ay + az * az)) * 180.0f / PI;
  float r = atan2f(ay, az) * 180.0f / PI;
  pitchDeg += ALPHA * (p - pitchDeg);
  rollDeg  += ALPHA * (r - rollDeg);

  Serial.printf("{\"pitch_deg\":%.1f,\"roll_deg\":%.1f,\"t_ms\":%lu}\n",
                pitchDeg, rollDeg, (unsigned long)millis());
}
