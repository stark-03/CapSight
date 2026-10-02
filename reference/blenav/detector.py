"""
YOLO detection + ByteTrack person tracking, running in its own thread.

Design notes:
  * ONE inference pass returns every class, so we split the result into
    persons / obstacles / stairs afterwards rather than running two models.
  * Persons are tracked (stable IDs via ByteTrack) because guidance needs to
    follow one specific human across frames.
  * Obstacles are written into a short-lived cache. This does NOT save
    inference time - it buys stability, so a chair that YOLO misses for two
    frames (or that the user walks in front of) does not vanish from the map.
  * The thread publishes the latest DetectionResult; the guidance loop reads it
    at its own 10 Hz and extrapolates in between. Detector speed and guidance
    responsiveness are therefore independent.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field

import numpy as np

# COCO ids worth treating as walkable-space obstacles indoors.
OBSTACLE_CLASS_IDS = {
    1: "bicycle", 13: "bench", 24: "backpack", 26: "handbag", 28: "suitcase",
    39: "bottle", 41: "cup", 56: "chair", 57: "couch", 58: "potted plant",
    59: "bed", 60: "dining table", 62: "tv", 63: "laptop", 66: "keyboard",
    73: "book", 75: "vase",
}
PERSON_CLASS_ID = 0


@dataclass
class Detection:
    label: str
    conf: float
    xyxy: tuple[float, float, float, float]
    cls_id: int
    track_id: int | None = None
    last_seen: float = 0.0

    @property
    def foot_point(self) -> tuple[float, float]:
        """Bottom-centre of the box - where the object meets the floor.

        This is the only pixel that can be mapped to floor coordinates by the
        homography, so every distance in the system ultimately derives from it.
        """
        x1, _, x2, y2 = self.xyxy
        return ((x1 + x2) / 2.0, y2)

    @property
    def centre(self) -> tuple[float, float]:
        x1, y1, x2, y2 = self.xyxy
        return ((x1 + x2) / 2.0, (y1 + y2) / 2.0)


@dataclass
class DetectionResult:
    persons: list[Detection] = field(default_factory=list)
    obstacles: list[Detection] = field(default_factory=list)
    stairs: list[Detection] = field(default_factory=list)
    stamp: float = 0.0
    seq: int = 0
    infer_ms: float = 0.0
    # Frame size, needed by first-person mode to turn a box position into a
    # bearing. Zero means "not known", which that mode treats as no obstacles.
    frame_w: int = 0
    frame_h: int = 0


class Detector:
    """Runs YOLO in a background thread against whatever the grabber has newest."""

    def __init__(self, grabber, weights="yolov8n.pt", stairs_weights=None,
                 imgsz=416, conf=0.35, device="cpu", frame_skip=1,
                 obstacle_ttl_s=2.5):
        from ultralytics import YOLO

        self.grabber = grabber
        self.imgsz = imgsz
        self.conf = conf
        self.device = device
        self.frame_skip = max(1, int(frame_skip))
        self.obstacle_ttl_s = obstacle_ttl_s

        self.model = YOLO(weights)
        self.stairs_model = YOLO(stairs_weights) if stairs_weights else None

        self._result = DetectionResult()
        self._obstacle_cache: dict[str, Detection] = {}
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._running = False
        self._last_seq = -1

    # ------------------------------------------------------------------ setup
    def start(self) -> "Detector":
        self._running = True
        self._thread = threading.Thread(target=self._run, name="detector", daemon=True)
        self._thread.start()
        return self

    # ------------------------------------------------------------------- loop
    def _run(self):
        skip_counter = 0
        while self._running:
            frame, stamp, seq = self.grabber.read()

            # Nothing new since last pass - yield rather than re-infer.
            if frame is None or seq == self._last_seq:
                time.sleep(0.005)
                continue
            self._last_seq = seq

            skip_counter += 1
            if skip_counter % self.frame_skip != 0:
                continue

            t0 = time.perf_counter()
            try:
                persons, others = self._infer(frame)
            except Exception as exc:            # never let a bad frame kill the thread
                print(f"[detector] inference error: {exc}")
                time.sleep(0.05)
                continue
            infer_ms = (time.perf_counter() - t0) * 1000.0

            stairs = self._infer_stairs(frame) if self.stairs_model else []
            obstacles = self._merge_obstacle_cache(others, stamp)

            with self._lock:
                self._result = DetectionResult(
                    persons=persons, obstacles=obstacles, stairs=stairs,
                    stamp=stamp, seq=seq, infer_ms=infer_ms,
                    frame_w=frame.shape[1], frame_h=frame.shape[0],
                )

    # -------------------------------------------------------------- inference
    def _infer(self, frame):
        """Single YOLO pass, split into tracked persons and untracked obstacles."""
        res = self.model.track(
            frame, persist=True, tracker="bytetrack.yaml",
            imgsz=self.imgsz, conf=self.conf, device=self.device, verbose=False,
        )[0]

        persons: list[Detection] = []
        obstacles: list[Detection] = []
        if res.boxes is None or len(res.boxes) == 0:
            return persons, obstacles

        ids = res.boxes.id
        ids = ids.cpu().numpy().astype(int) if ids is not None else np.full(len(res.boxes), -1)

        for box, cls, cf, tid in zip(res.boxes.xyxy.cpu().numpy(),
                                     res.boxes.cls.cpu().numpy().astype(int),
                                     res.boxes.conf.cpu().numpy(),
                                     ids):
            det = Detection(
                label=res.names[int(cls)], conf=float(cf),
                xyxy=tuple(float(v) for v in box), cls_id=int(cls),
                track_id=int(tid) if tid >= 0 else None, last_seen=time.time(),
            )
            if cls == PERSON_CLASS_ID:
                persons.append(det)
            elif cls in OBSTACLE_CLASS_IDS:
                obstacles.append(det)
        return persons, obstacles

    def _infer_stairs(self, frame):
        """Optional second model - only loaded if a fine-tuned stairs weight exists."""
        try:
            res = self.stairs_model.predict(
                frame, imgsz=self.imgsz, conf=self.conf,
                device=self.device, verbose=False,
            )[0]
        except Exception as exc:
            print(f"[detector] stairs model error: {exc}")
            return []

        out = []
        if res.boxes is None:
            return out
        for box, cf, cls in zip(res.boxes.xyxy.cpu().numpy(),
                                res.boxes.conf.cpu().numpy(),
                                res.boxes.cls.cpu().numpy().astype(int)):
            out.append(Detection(label="stairs", conf=float(cf),
                                 xyxy=tuple(float(v) for v in box),
                                 cls_id=1000 + int(cls), last_seen=time.time()))
        return out

    # ------------------------------------------------------------------ cache
    def _merge_obstacle_cache(self, fresh: list[Detection], stamp: float) -> list[Detection]:
        """Keep obstacles alive briefly after they stop being detected.

        Keyed on a coarse grid cell so the same chair re-detected a few pixels
        over refreshes its entry instead of creating a duplicate.
        """
        now = time.time()
        for det in fresh:
            fx, fy = det.foot_point
            self._obstacle_cache[f"{det.label}:{int(fx // 40)}:{int(fy // 40)}"] = det

        expired = [k for k, d in self._obstacle_cache.items()
                   if now - d.last_seen > self.obstacle_ttl_s]
        for k in expired:
            del self._obstacle_cache[k]

        return list(self._obstacle_cache.values())

    # ------------------------------------------------------------------- read
    def read(self) -> DetectionResult:
        with self._lock:
            return self._result

    def stop(self):
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=3.0)
