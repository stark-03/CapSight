#!/usr/bin/env bash
# Checks the real ESP32-CAM from the laptop, independently of the phone.
#
# Temporarily switches this laptop's Wi-Fi to BLENAV_CAM (internet is off for
# about 30 seconds), fetches the landing page, records 10 s of the MJPEG stream,
# saves the first frame as a JPEG, then switches back to the previous Wi-Fi.
#
# Usage:  bash tools/stream_check.sh
# Results: /tmp/capsight_stream_check/result.txt and first_frame.jpg
set -u

CAM_SSID="BLENAV_CAM"
CAM_PASS="blenav1234"
OUT="/tmp/capsight_stream_check"
mkdir -p "$OUT"
rm -f "$OUT"/*

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

{
  echo "== CapSight stream check $(date '+%F %T') =="
  echo "Previous Wi-Fi: ${HOME_WIFI:-none}"
} | tee "$OUT/result.txt"

echo "Joining $CAM_SSID (internet will be off for ~30 s)..."
# The scan list goes stale when the board has just restarted, so refresh it and retry.
JOINED=0
for attempt in 1 2 3; do
  nmcli device wifi rescan >/dev/null 2>&1
  sleep 3
  if ERR=$(nmcli device wifi connect "$CAM_SSID" password "$CAM_PASS" 2>&1); then
    JOINED=1
    break
  fi
  echo "  attempt $attempt failed: $ERR"
done
if [ "$JOINED" -ne 1 ]; then
  echo "FAIL: could not join $CAM_SSID. Last error: $ERR" | tee -a "$OUT/result.txt"
  exit 1
fi
sleep 3

{
  echo "Laptop address on camera Wi-Fi: $(ip -4 -o addr show | awk '/192\.168\.4\./{print $4}')"
  curl -sS -m 5 -o "$OUT/index.html" -w "Landing page: HTTP %{http_code} in %{time_total}s\n" http://192.168.4.1/ 2>&1
} | tee -a "$OUT/result.txt"

echo "Recording 10 s of video..."
curl -sS -m 10 -o "$OUT/stream.mjpeg" http://192.168.4.1:81/stream 2>/dev/null
SIZE=$(stat -c%s "$OUT/stream.mjpeg" 2>/dev/null || echo 0)
FRAMES=$(grep -c -a "Content-Type: image/jpeg" "$OUT/stream.mjpeg" 2>/dev/null || echo 0)

python3 - "$OUT" <<'EOF' | tee -a "$OUT/result.txt"
import sys, pathlib
out = pathlib.Path(sys.argv[1])
data = (out / "stream.mjpeg").read_bytes() if (out / "stream.mjpeg").exists() else b""
start = data.find(b"\xff\xd8")
end = data.find(b"\xff\xd9", start + 2) if start >= 0 else -1
if start >= 0 and end > start:
    (out / "first_frame.jpg").write_bytes(data[start:end + 2])
    print(f"First frame saved: {out / 'first_frame.jpg'} ({end + 2 - start} bytes)")
else:
    print("No complete JPEG frame received.")
EOF

{
  echo "Stream: $SIZE bytes, $FRAMES frames in 10 s (about $((FRAMES / 10)) fps)"
  if [ "$FRAMES" -ge 30 ]; then echo "RESULT: board streams fine to the laptop -> the problem is on the phone side."
  elif [ "$FRAMES" -ge 1 ]; then echo "RESULT: board streams but slowly/unstably -> suspect power or Wi-Fi link."
  else echo "RESULT: no frames even to the laptop -> problem is on the board (power, camera or firmware)."
  fi
} | tee -a "$OUT/result.txt"
