#!/usr/bin/env bash
# Finds camera settings that stop the ESP32-CAM from choking its own Wi-Fi.
#
# Needs firmware esp32_cam_ap with the /control endpoint. Temporarily switches this
# laptop to BLENAV_CAM (internet off for about 2 minutes), tries each camera setting
# through http://192.168.4.1/control, measures landing-page time and stream speed,
# prints a table, then switches back to the previous Wi-Fi. Settings reset to the
# firmware defaults when the board restarts.
#
# Usage:   bash tools/camera_tuning.sh
# Results: /tmp/capsight_tuning/result.txt
set -u

CAM_SSID="BLENAV_CAM"
CAM_PASS="blenav1234"
OUT="/tmp/capsight_tuning"
SECS=6
mkdir -p "$OUT"
rm -f "$OUT"/*

CONFIGS=(
  "baseline: 20MHz, 2 buffers, latest, 640x480|xclk=20&fb=2&grab=latest&size=vga&q=12"
  "10MHz clock|xclk=10&fb=2&grab=latest&size=vga&q=12"
  "1 buffer, grab when empty|xclk=20&fb=1&grab=empty&size=vga&q=12"
  "10MHz + 1 buffer, when empty|xclk=10&fb=1&grab=empty&size=vga&q=12"
  "320x240|xclk=20&fb=2&grab=latest&size=qvga&q=12"
  "10MHz + 320x240|xclk=10&fb=2&grab=latest&size=qvga&q=12"
  "10MHz + 1 buffer, when empty + 320x240|xclk=10&fb=1&grab=empty&size=qvga&q=12"
)

HOME_WIFI="$(nmcli -t -f NAME,TYPE connection show --active | awk -F: '$2=="802-11-wireless"{print $1; exit}')"
if [ -z "$HOME_WIFI" ] || [ "$HOME_WIFI" = "$CAM_SSID" ]; then
  # Already on the camera (e.g. an earlier run was interrupted): return to the most recently used other Wi-Fi.
  HOME_WIFI="$(nmcli -t -f TIMESTAMP,NAME,TYPE connection show | awk -F: -v cam="$CAM_SSID" '$3=="802-11-wireless" && $2!=cam' | sort -rn | head -1 | cut -d: -f2)"
fi
restore() {
  if [ -n "$HOME_WIFI" ] && [ "$HOME_WIFI" != "$CAM_SSID" ]; then
    echo "Switching back to '$HOME_WIFI'..."
    nmcli connection up "$HOME_WIFI" >/dev/null 2>&1 || echo "Could not reconnect; pick '$HOME_WIFI' in the Wi-Fi menu."
  fi
}
trap restore EXIT

echo "== CapSight camera tuning $(date '+%F %T') ==" | tee "$OUT/result.txt"
echo "Joining $CAM_SSID (internet will be off for about 2 minutes)..."
JOINED=0
for attempt in 1 2 3; do
  nmcli device wifi rescan >/dev/null 2>&1
  sleep 3
  if ERR=$(nmcli device wifi connect "$CAM_SSID" password "$CAM_PASS" 2>&1); then JOINED=1; break; fi
  echo "  attempt $attempt failed: $ERR"
done
if [ "$JOINED" -ne 1 ]; then
  echo "FAIL: could not join $CAM_SSID. Last error: $ERR" | tee -a "$OUT/result.txt"
  exit 1
fi
sleep 3

if ! curl -sS -m 10 http://192.168.4.1/control | grep -q '"xclk_mhz"'; then
  echo "FAIL: /control not found. Flash the latest esp32_cam_ap firmware first." | tee -a "$OUT/result.txt"
  exit 1
fi

printf "%-42s %10s %8s %9s %10s\n" "setting" "page (s)" "frames" "fps" "KB/s" | tee -a "$OUT/result.txt"
for entry in "${CONFIGS[@]}"; do
  label="${entry%%|*}"
  query="${entry#*|}"
  RESP=$(curl -sS -m 15 "http://192.168.4.1/control?$query" 2>&1)
  if ! echo "$RESP" | grep -q '"ok":true'; then
    printf "%-42s  could not apply: %s\n" "$label" "$RESP" | tee -a "$OUT/result.txt"
    continue
  fi
  sleep 2
  PAGE=$(curl -sS -m 10 -o /dev/null -w "%{time_total}" http://192.168.4.1/ 2>/dev/null || echo "timeout")
  curl -sS -m "$SECS" -o "$OUT/stream.mjpeg" http://192.168.4.1:81/stream 2>/dev/null
  BYTES=$(stat -c%s "$OUT/stream.mjpeg" 2>/dev/null || echo 0)
  FRAMES=$(grep -c -a "Content-Type: image/jpeg" "$OUT/stream.mjpeg" 2>/dev/null || echo 0)
  FPS=$(awk -v f="$FRAMES" -v s="$SECS" 'BEGIN{printf "%.1f", f/s}')
  KBS=$(awk -v b="$BYTES" -v s="$SECS" 'BEGIN{printf "%.0f", b/1024/s}')
  printf "%-42s %10s %8s %9s %10s\n" "$label" "$PAGE" "$FRAMES" "$FPS" "$KBS" | tee -a "$OUT/result.txt"
done

# put the board back on its defaults for the rest of the session
curl -sS -m 15 "http://192.168.4.1/control?xclk=20&fb=2&grab=latest&size=vga&q=12" >/dev/null 2>&1
echo "Good result: page under 0.2 s and 10+ fps." | tee -a "$OUT/result.txt"
