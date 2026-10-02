#!/usr/bin/env python3
"""
Fake BLENAV_CAM: a laptop stand-in for the ESP32-CAM during Phase A.

Reproduces the device contract in CLAUDE.md section 4.1-4.2, as implemented by
firmware/esp32_cam_ap/esp32_cam_ap.ino:

  * port 80, GET /        -> the firmware's landing page, byte for byte
                              (title BLENAV_CAM; used by the app's health probe)
  * port 80, anything else -> 404, like esp_http_server (lets the app's v2
                              feature detection see "no /status, no /imu")
  * port 81, GET /stream  -> multipart/x-mixed-replace, same boundary string,
                              same per-frame headers, NO leading boundary on the
                              first frame, sent with chunked transfer encoding
                              (httpd_resp_send_chunk does that on the device)
  * one stream client at a time: the stream server is single-threaded, so a
    second viewer connects but gets nothing until the first one leaves -
    exactly like the firmware's stream handler looping inside its server task
  * newest-frame-only (CAMERA_GRAB_LATEST): a video file plays in real time and
    frames are skipped, never queued
  * a client that vanishes is noticed only when a send times out (~5 s)

Frames are 640x480 JPEG (or 320x240 with --qvga, the no-PSRAM mode).

Needs OpenCV:  pip install opencv-python   (already present in the Phase-2 env)

Examples:
  python tools/fake_esp32cam.py --source walk.mp4        # recorded walk, looped
  python tools/fake_esp32cam.py --source 0               # laptop webcam
  python tools/fake_esp32cam.py --pattern                # moving test card
  python tools/fake_esp32cam.py --pattern --port 8080    # no root: 8080 + 8081

Ports below 1024 need root on Linux. Either run once
  sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80
or use --port 8080 (stream then on 8081) and enter http://<laptop-ip>:8080 as
the camera address in the app.
"""
from __future__ import annotations

import argparse
import socket
import socketserver
import sys
import threading
import time

try:
    import cv2
    import numpy as np
except ImportError:
    sys.exit("OpenCV is required:  pip install opencv-python")

# ----------------------------------------------------------- device contract
# Every value here is copied from esp32_cam_ap.ino. Change both together.
PART_BOUNDARY = b"123456789000000000000987654321"
STREAM_CONTENT_TYPE = b"multipart/x-mixed-replace;boundary=" + PART_BOUNDARY
STREAM_BOUNDARY = b"\r\n--" + PART_BOUNDARY + b"\r\n"
STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n"

INDEX_PAGE = (
    b"<!doctype html><html><head><meta name=viewport "
    b"content='width=device-width,initial-scale=1'>"
    b"<title>BLENAV_CAM</title></head>"
    b"<body style='font-family:sans-serif;text-align:center;background:#111;color:#eee'>"
    b"<h2>BLENAV_CAM is live</h2>"
    b"<p>Stream URL for the project:<br><code>http://192.168.4.1:81/stream</code></p>"
    b"<img src='http://192.168.4.1:81/stream' style='max-width:100%;border:2px solid #555'>"
    b"</body></html>"
)

# esp_http_server's default 404 body.
NOT_FOUND_BODY = b"This URI does not exist"

# esp_http_server's default send timeout (HTTPD_DEFAULT_CONFIG send_wait_timeout).
SEND_TIMEOUT_S = 5.0

VGA = (640, 480)      # PSRAM present (normal case), jpeg_quality 12
QVGA = (320, 240)     # no PSRAM, jpeg_quality 15

# The ESP32 quality scale is 0-63, lower = better. OpenCV is 0-100, higher =
# better. These give roughly the same file sizes as the real board (~20-40 KB
# per VGA frame). Content is not part of the contract; framing is.
CV_QUALITY = {VGA: 80, QVGA: 75}


# ------------------------------------------------------------- frame source
class FrameProducer:
    """Keeps only the newest encoded JPEG, like CAMERA_GRAB_LATEST."""

    def __init__(self, source: str | None, size: tuple[int, int], fps: float):
        self.size = size
        self.period = 1.0 / fps
        self.quality = CV_QUALITY[size]
        self._source = source
        self._latest: bytes | None = None
        self._seq = 0
        self._cond = threading.Condition()
        threading.Thread(target=self._run, name="producer", daemon=True).start()

    def _encode(self, img) -> bytes:
        if (img.shape[1], img.shape[0]) != self.size:
            img = cv2.resize(img, self.size, interpolation=cv2.INTER_AREA)
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, self.quality])
        if not ok:
            raise RuntimeError("JPEG encode failed")
        return buf.tobytes()

    def _publish(self, jpeg: bytes):
        with self._cond:
            self._latest = jpeg
            self._seq += 1
            self._cond.notify_all()

    def next_frame(self, last_seq: int) -> tuple[bytes, int]:
        """Block until a frame newer than last_seq exists, return the newest."""
        with self._cond:
            while self._seq == last_seq or self._latest is None:
                self._cond.wait()
            return self._latest, self._seq

    # ---------------------------------------------------------------- loops
    def _run(self):
        if self._source is None:
            self._run_pattern()
        else:
            self._run_capture()

    def _run_pattern(self):
        w, h = self.size
        n = 0
        t0 = time.monotonic()
        while True:
            img = np.zeros((h, w, 3), np.uint8)
            img[:] = (40, 40, 40)
            # Moving bar, so frozen frames are obvious on the phone.
            x = int((n * 8) % w)
            cv2.rectangle(img, (x, 0), (x + w // 10, h), (0, 180, 255), -1)
            cv2.putText(img, "FAKE BLENAV_CAM", (10, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8 * w / 640, (255, 255, 255), 2)
            cv2.putText(img, f"frame {n}", (10, h - 40),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8 * w / 640, (255, 255, 255), 2)
            cv2.putText(img, time.strftime("%H:%M:%S"), (10, h - 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6 * w / 640, (200, 200, 200), 1)
            self._publish(self._encode(img))
            n += 1
            time.sleep(max(0.0, t0 + n * self.period - time.monotonic()))

    def _run_capture(self):
        src: int | str = int(self._source) if self._source.isdigit() else self._source
        is_file = isinstance(src, str)
        while True:
            cap = cv2.VideoCapture(src)
            if not cap.isOpened():
                print(f"[fake] cannot open {src!r}, retrying in 2 s")
                time.sleep(2.0)
                continue
            video_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
            if is_file:
                self._play_file(cap, video_fps)
            else:
                self._play_live(cap)
            cap.release()

    def _play_file(self, cap, video_fps: float):
        """Real-time playback: skip frames to stay live, loop at the end."""
        start = time.monotonic()
        pos = -1
        while True:
            want = int((time.monotonic() - start) * video_fps)
            img = None
            while pos < want:                 # drop stale frames, never queue
                ok, img = cap.read()
                if not ok:
                    return                    # end of file -> reopen and loop
                pos += 1
            if img is not None:
                self._publish(self._encode(img))
            time.sleep(self.period)

    def _play_live(self, cap):
        last = 0.0
        while True:
            ok, img = cap.read()
            if not ok:
                print("[fake] webcam read failed, reopening")
                return
            now = time.monotonic()
            if now - last >= self.period:
                last = now
                self._publish(self._encode(img))


# --------------------------------------------------------------- servers
def _send_simple(conn: socket.socket, status: str, ctype: bytes, body: bytes):
    head = (f"HTTP/1.1 {status}\r\n".encode()
            + b"Content-Type: " + ctype + b"\r\n"
            + f"Content-Length: {len(body)}\r\n\r\n".encode())
    conn.sendall(head + body)


def _read_request_path(conn: socket.socket) -> str | None:
    """Read the request head, return the path of a GET, or None."""
    conn.settimeout(SEND_TIMEOUT_S)
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(1024)
        if not chunk:
            return None
        data += chunk
        if len(data) > 8192:
            return None
    parts = data.split(b"\r\n", 1)[0].split()
    if len(parts) < 2 or parts[0] != b"GET":
        return None
    return parts[1].decode("latin-1").split("?", 1)[0]


class IndexHandler(socketserver.BaseRequestHandler):
    """Port 80. Threaded, like the device's separate index httpd task."""

    def handle(self):
        try:
            path = _read_request_path(self.request)
            if path is None:
                return
            if path == "/":
                _send_simple(self.request, "200 OK", b"text/html", INDEX_PAGE)
            else:
                _send_simple(self.request, "404 Not Found", b"text/html", NOT_FOUND_BODY)
            print(f"[fake:80] {self.client_address[0]} GET {path}")
        except OSError:
            pass


def _chunk(data: bytes) -> bytes:
    """One httpd_resp_send_chunk call on the wire."""
    return f"{len(data):X}\r\n".encode() + data + b"\r\n"


class StreamHandler(socketserver.BaseRequestHandler):
    """Port 81. Single-threaded server: one client at a time, like the device."""

    producer: FrameProducer = None  # set in main()

    def handle(self):
        peer = self.client_address[0]
        try:
            path = _read_request_path(self.request)
            if path is None:
                return
            if path != "/stream":
                _send_simple(self.request, "404 Not Found", b"text/html", NOT_FOUND_BODY)
                return
            print(f"[fake:81] {peer} connected to /stream")
            self.request.sendall(
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: " + STREAM_CONTENT_TYPE + b"\r\n"
                b"Transfer-Encoding: chunked\r\n"
                b"Access-Control-Allow-Origin: *\r\n"
                b"\r\n")
            seq = 0
            sent = 0
            t0 = time.monotonic()
            while True:
                jpeg, seq = self.producer.next_frame(seq)
                # Three chunks per frame, in the firmware's order. The first
                # frame starts directly with Content-Type: - no boundary before it.
                self.request.sendall(
                    _chunk((STREAM_PART % len(jpeg)).encode())
                    + _chunk(jpeg)
                    + _chunk(STREAM_BOUNDARY))
                sent += 1
                if sent % 100 == 0:
                    fps = sent / (time.monotonic() - t0)
                    print(f"[fake:81] {peer} {sent} frames, {fps:.1f} fps, "
                          f"last {len(jpeg)} bytes")
        except OSError as exc:
            print(f"[fake:81] {peer} gone ({exc.__class__.__name__})")


class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True


class ReusableThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def _local_ips() -> list[str]:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))     # no packet is sent
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--source", help="video file path, or webcam index (0, 1, ...)")
    src.add_argument("--pattern", action="store_true", help="synthetic moving test card")
    ap.add_argument("--qvga", action="store_true",
                    help="320x240 frames (the board's no-PSRAM mode)")
    ap.add_argument("--fps", type=float, default=15.0,
                    help="max frame rate (real board: about 10-20 at VGA)")
    ap.add_argument("--port", type=int, default=80,
                    help="landing-page port; the stream uses port+1 (default 80 -> 81)")
    ap.add_argument("--bind", default="0.0.0.0", help="address to listen on")
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)   # live logs even when piped

    size = QVGA if args.qvga else VGA
    StreamHandler.producer = FrameProducer(None if args.pattern else args.source,
                                           size, args.fps)

    try:
        index = ReusableThreadingTCPServer((args.bind, args.port), IndexHandler)
        stream = ReusableTCPServer((args.bind, args.port + 1), StreamHandler)
    except PermissionError:
        sys.exit(f"Cannot bind port {args.port}: ports below 1024 need root.\n"
                 "Run:  sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80\n"
                 "or use --port 8080 (and http://<laptop-ip>:8080 in the app).")

    threading.Thread(target=index.serve_forever, name="index", daemon=True).start()

    base = "" if args.port == 80 else f":{args.port}"
    print("=================================================")
    print("  FAKE BLENAV_CAM ready")
    print(f"  Frames     : {size[0]}x{size[1]}, up to {args.fps:g} fps")
    for ip in _local_ips() or ["<laptop-ip>"]:
        print(f"  App camera address : http://{ip}{base}")
        print(f"     stream          : http://{ip}:{args.port + 1}/stream")
    print("  One stream client at a time. Ctrl+C to stop.")
    print("=================================================")
    try:
        stream.serve_forever()
    except KeyboardInterrupt:
        print("\n[fake] stopped")


if __name__ == "__main__":
    main()
