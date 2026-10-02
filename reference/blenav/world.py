"""
Floor-plane geometry: the layer that turns pixels into metres.

Everything the guidance loop decides - "is that chair 1.4 m or 4 m away",
"is the user drifting left of the path", "which side is free" - is computed
here, in floor coordinates, not in pixels.

The mapping comes from a homography fitted to four clicked floor points whose
real-world separation you measured with a tape. See calibrate.py.

Coordinate frame (metres):
    X = across the calibration rectangle (right positive)
    Y = away from the camera (depth positive)

This module is deliberately free of OpenCV *capture*, threads and hardware, so
it can be unit-tested on any machine.
"""
from __future__ import annotations

import math
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
import yaml


# ============================================================== plane mapping
class FloorPlane:
    """Pixel <-> floor-metre mapping via a 4-point homography."""

    def __init__(self, H: np.ndarray):
        self.H = np.asarray(H, dtype=np.float64)
        self.H_inv = np.linalg.inv(self.H)

    # ---- construction -----------------------------------------------------
    @classmethod
    def from_rectangle(cls, pixel_pts, width_m: float, depth_m: float) -> "FloorPlane":
        """Four clicked pixels -> a rectangle of known size on the floor.

        pixel_pts must be in this order, as seen in the image:
            near-left, near-right, far-right, far-left
        """
        if len(pixel_pts) != 4:
            raise ValueError("exactly 4 points required")
        src = np.array(pixel_pts, dtype=np.float32)
        dst = np.array([[0.0, 0.0], [width_m, 0.0],
                        [width_m, depth_m], [0.0, depth_m]], dtype=np.float32)
        return cls(cv2.getPerspectiveTransform(src, dst))

    @classmethod
    def load(cls, path) -> "FloorPlane | None":
        path = Path(path)
        if not path.exists():
            return None
        with open(path) as fh:
            data = yaml.safe_load(fh) or {}
        if "homography" not in data:
            return None
        return cls(np.array(data["homography"], dtype=np.float64))

    def save(self, path, extra: dict | None = None):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"homography": self.H.tolist()}
        payload.update(extra or {})
        with open(path, "w") as fh:
            yaml.safe_dump(payload, fh, sort_keys=False)

    # ---- transforms -------------------------------------------------------
    def to_floor(self, px: float, py: float) -> tuple[float, float]:
        pt = np.array([[[float(px), float(py)]]], dtype=np.float64)
        out = cv2.perspectiveTransform(pt, self.H)[0][0]
        return float(out[0]), float(out[1])

    def to_pixel(self, x: float, y: float) -> tuple[float, float]:
        pt = np.array([[[float(x), float(y)]]], dtype=np.float64)
        out = cv2.perspectiveTransform(pt, self.H_inv)[0][0]
        return float(out[0]), float(out[1])


# ================================================================ path following
def _point_segment(p, a, b):
    """Return (distance, signed_side, t) from point p to segment ab.

    signed_side > 0 means p lies to the LEFT of a->b.
    """
    px, py = p
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    seg2 = dx * dx + dy * dy
    if seg2 < 1e-12:
        return math.hypot(px - ax, py - ay), 0.0, 0.0
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
    cx, cy = ax + t * dx, ay + t * dy
    dist = math.hypot(px - cx, py - cy)
    cross = dx * (py - ay) - dy * (px - ax)      # z of (b-a) x (p-a)
    return dist, math.copysign(1.0, cross) if abs(cross) > 1e-9 else 0.0, t


@dataclass
class NavPath:
    """The predefined safe route, as a polyline in floor metres."""
    points: list[tuple[float, float]] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return len(self.points) >= 2

    def deviation(self, pos) -> tuple[float, float]:
        """Return (perpendicular_distance_m, side).

        side = +1 when the user is LEFT of the path (so they must move right).
        """
        if not self.valid:
            return 0.0, 0.0
        best = min((_point_segment(pos, self.points[i], self.points[i + 1])
                    for i in range(len(self.points) - 1)),
                   key=lambda r: r[0])
        return best[0], best[1]


# ================================================================== user state
class UserState:
    """Tracks the target user's position, speed and heading in floor metres.

    Heading comes from the displacement over a short window rather than from a
    body-orientation model. It is accurate while walking - which is the only
    time guidance needs it - and simply goes stale when the user stands still.
    """

    def __init__(self, heading_window_s: float = 0.8, history: int = 60):
        self.heading_window_s = heading_window_s
        self._hist: deque[tuple[float, float, float]] = deque(maxlen=history)

    def update(self, pos: tuple[float, float], stamp: float | None = None):
        self._hist.append((stamp if stamp is not None else time.time(), pos[0], pos[1]))

    @property
    def position(self) -> tuple[float, float] | None:
        return (self._hist[-1][1], self._hist[-1][2]) if self._hist else None

    @property
    def last_update(self) -> float:
        return self._hist[-1][0] if self._hist else 0.0

    def _window(self):
        if len(self._hist) < 2:
            return None
        t_now = self._hist[-1][0]
        old = next((s for s in self._hist if t_now - s[0] <= self.heading_window_s),
                   self._hist[0])
        return old, self._hist[-1]

    @property
    def speed(self) -> float:
        w = self._window()
        if w is None:
            return 0.0
        (t0, x0, y0), (t1, x1, y1) = w
        dt = t1 - t0
        return math.hypot(x1 - x0, y1 - y0) / dt if dt > 1e-3 else 0.0

    @property
    def heading(self) -> tuple[float, float] | None:
        """Unit vector of travel, or None if too slow to be meaningful."""
        w = self._window()
        if w is None:
            return None
        (_, x0, y0), (_, x1, y1) = w
        dx, dy = x1 - x0, y1 - y0
        n = math.hypot(dx, dy)
        if n < 0.12:                      # < 12 cm of travel: direction is noise
            return None
        return (dx / n, dy / n)

    def predict(self, at_time: float) -> tuple[float, float] | None:
        """Extrapolate position - lets guidance run faster than the detector."""
        pos = self.position
        if pos is None:
            return None
        h = self.heading
        dt = max(0.0, min(0.5, at_time - self.last_update))   # never extrapolate far
        if h is None or dt == 0.0:
            return pos
        s = self.speed
        return (pos[0] + h[0] * s * dt, pos[1] + h[1] * s * dt)

    def reset(self):
        self._hist.clear()


# ============================================================ obstacle geometry
@dataclass
class RelativeObstacle:
    label: str
    forward_m: float      # distance ahead along the user's heading
    lateral_m: float      # + = to the user's LEFT
    distance_m: float

    @property
    def side(self) -> str:
        return "left" if self.lateral_m > 0 else "right"


def relative_to_user(user_pos, heading, obstacle_pos, label="obstacle") -> RelativeObstacle | None:
    """Express an obstacle in the user's own forward/lateral frame."""
    if user_pos is None or heading is None:
        return None
    vx = obstacle_pos[0] - user_pos[0]
    vy = obstacle_pos[1] - user_pos[1]
    hx, hy = heading
    forward = vx * hx + vy * hy          # dot
    lateral = hx * vy - hy * vx          # cross z: + is left of heading
    return RelativeObstacle(label=label, forward_m=forward, lateral_m=lateral,
                            distance_m=math.hypot(vx, vy))


def in_corridor(rel: RelativeObstacle, half_width_m: float, max_forward_m: float) -> bool:
    """Is this obstacle actually in the user's walking corridor?

    Filters out the chair 3 m off to the side that is no threat at all.
    """
    return 0.0 < rel.forward_m <= max_forward_m and abs(rel.lateral_m) <= half_width_m


def free_side(rels: list[RelativeObstacle], half_width_m: float) -> str:
    """Pick the side with more clearance. Ties go right (keep-right convention)."""
    left_block = sum(1 for r in rels if r.lateral_m >= 0)
    right_block = sum(1 for r in rels if r.lateral_m < 0)
    if left_block < right_block:
        return "left"
    if right_block < left_block:
        return "right"
    # Equal counts: go away from the nearest single obstacle.
    nearest = min(rels, key=lambda r: r.forward_m, default=None)
    if nearest is None:
        return "right"
    return "right" if nearest.lateral_m >= 0 else "left"
