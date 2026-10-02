"""
First-person (egocentric) geometry: the camera is carried BY the user.

The ceiling-camera mode watches the user from outside and uses a homography to
turn floor pixels into metres. None of that applies here — the camera moves with
the user, so there is no fixed floor plane to calibrate against.

Instead this module uses the one cue a single moving camera still gives you:
**apparent size**. A chair is about 0.90 m tall in the real world, so how tall it
appears in pixels says how far away it is:

    pixel_height = focal_px * real_height_m / distance_m

Rearranged, that is the distance. Deliberately chosen over floor-line geometry
because the user is *walking* — every tilt of the hand would move an assumed
floor line, whereas apparent size does not care which way the camera is pointed.

The pay-off is that the user sits at the origin facing +Y, which is exactly the
frame `world.relative_to_user()` produces. So the output of this module drops
straight into the existing, already-tested `guidance.decide()` policy.

LIMITATION, stated plainly: only classes in OBJECT_HEIGHTS_M get a distance.
A wall, a step, a glass door or anything else the detector does not know is
invisible to this mode.
"""
from __future__ import annotations

import math

from .world import RelativeObstacle

# Typical real-world heights in metres, for the classes the detector reports.
# These are size *priors*: a real chair might be 0.75-1.05 m, so every distance
# derived from them carries roughly 25% error. That is the price of monocular
# distance without a depth sensor.
OBJECT_HEIGHTS_M: dict[str, float] = {
    "person": 1.70,
    "bicycle": 1.05,
    "bench": 0.90,
    "backpack": 0.50,
    "handbag": 0.30,
    "suitcase": 0.70,
    "bottle": 0.25,
    "cup": 0.12,
    "chair": 0.90,
    "couch": 0.85,
    "potted plant": 0.60,
    "bed": 0.60,
    "dining table": 0.75,
    "tv": 0.60,
    "laptop": 0.25,
    "keyboard": 0.03,
    "book": 0.05,
    "vase": 0.30,
}


# Typical real-world WIDTHS, used when the height cannot be trusted (below).
# Width survives truncation: a desk hides your legs but not your shoulders.
OBJECT_WIDTHS_M: dict[str, float] = {
    "person": 0.45,          # shoulders
    "bicycle": 1.60,         # seen side-on
    "bench": 1.50,
    "backpack": 0.35,
    "handbag": 0.35,
    "suitcase": 0.45,
    "bottle": 0.08,
    "cup": 0.09,
    "chair": 0.50,
    "couch": 1.90,
    "potted plant": 0.40,
    "bed": 1.50,
    "dining table": 1.40,
    "tv": 1.00,
    "laptop": 0.35,
    "keyboard": 0.45,
    "book": 0.15,
    "vase": 0.20,
}

# How much wider than expected a box must look before we stop trusting its
# height. 1.6x is loose enough to tolerate odd poses and viewing angles, tight
# enough to catch a person whose legs are hidden.
_TRUNCATION_ASPECT_FACTOR = 1.6


# Flat objects whose distance estimate is not trustworthy. A keyboard is 3 cm
# tall, so a two-pixel error in the box swings the distance by metres. They also
# sit on desks rather than in the walking corridor, so announcing them is noise.
# Kept in OBJECT_HEIGHTS_M so they can still be named if explicitly asked for.
UNRELIABLE_SMALL = frozenset({"keyboard", "book", "cup", "laptop"})


def focal_from_fov(fov_deg: float, frame_width_px: int) -> float:
    """Focal length in pixels, from the camera's horizontal field of view.

    Phone cameras do not report their intrinsics, but their field of view is a
    published number (typically 60-70 degrees for a main rear camera).
    """
    return (frame_width_px / 2.0) / math.tan(math.radians(fov_deg) / 2.0)


# How close to the frame edge a box must come before it counts as cut off.
_EDGE_PX = 2.0


def estimate_distance(det, focal_px: float, frame_h: int | None = None) -> float | None:
    """Forward distance in metres from apparent size. None if it cannot be known.

    Pass `frame_h` to correct for objects too close to fit in the frame.

    Why that correction matters: the size prior assumes the WHOLE object is
    visible. Stand close enough that a person overflows the top or bottom of the
    frame and only part of them is boxed, so they measure *smaller* and the naive
    estimate reports them *further away* - precisely when they are nearest. That
    is the unsafe direction of error, so it is worth correcting.

    The correction is a rigorous bound, not a guess: an object that overflows the
    frame must project taller than the frame, so it must be nearer than the
    distance at which it would exactly fill the frame.
    """
    real_h = OBJECT_HEIGHTS_M.get(det.label)
    if real_h is None:
        return None
    _, y1, _, y2 = det.xyxy
    px_h = y2 - y1
    if px_h <= 0:
        return None
    dist = float(focal_px * real_h / px_h)

    # A box can be truncated without touching a frame edge - a desk hides your
    # legs, a table hides a chair's base. The aspect ratio gives it away: a whole
    # person is far taller than wide, so a near-square person box means the
    # height is not the whole person. Width survives truncation, so use it.
    x1, _, x2, _ = det.xyxy
    px_w = x2 - x1
    real_w = OBJECT_WIDTHS_M.get(det.label)
    if real_w and px_w > 0:
        expected_aspect = real_w / real_h
        if px_w / px_h > expected_aspect * _TRUNCATION_ASPECT_FACTOR:
            dist = float(focal_px * real_w / px_w)

    if frame_h:
        touches_top = y1 <= _EDGE_PX
        touches_bottom = y2 >= frame_h - _EDGE_PX
        if touches_top or touches_bottom:
            # Nearer than the object would be if it exactly filled the frame.
            dist = min(dist, float(focal_px * real_h / frame_h))
    return dist


def to_relative(det, frame_width_px: int, focal_px: float,
                frame_h: int | None = None) -> RelativeObstacle | None:
    """Place a detection in the user's own forward/lateral frame.

    Pinhole geometry: the size relation gives depth along the optical axis, and
    horizontal offset from the frame centre gives the sideways component.
    Positive lateral means the user's LEFT, matching `world.relative_to_user`.
    """
    forward = estimate_distance(det, focal_px, frame_h=frame_h)
    if forward is None:
        return None
    x1, _, x2, _ = det.xyxy
    centre_x = (x1 + x2) / 2.0
    dx_px = centre_x - frame_width_px / 2.0
    # Image x grows to the right, but lateral is positive to the left.
    lateral = -forward * dx_px / focal_px
    return RelativeObstacle(
        label=det.label,
        forward_m=forward,
        lateral_m=lateral,
        distance_m=math.hypot(forward, lateral),
    )


def egocentric_obstacles(dets, frame_width_px: int, focal_px: float,
                         min_conf: float = 0.0, skip_unreliable: bool = True,
                         frame_h: int | None = None) -> list[tuple[str, tuple[float, float]]]:
    """Detections -> the (label, position) pairs `GuidanceInput` expects.

    Positions are world coordinates for a user standing at the origin facing +Y,
    chosen so that `relative_to_user((0,0), (0,1), pos)` returns exactly the
    relative obstacle computed here. That is what lets the existing navigation
    policy run unmodified in first-person mode.
    """
    out: list[tuple[str, tuple[float, float]]] = []
    for d in dets:
        if d.conf < min_conf:
            continue
        if skip_unreliable and d.label in UNRELIABLE_SMALL:
            continue
        rel = to_relative(d, frame_width_px, focal_px, frame_h=frame_h)
        if rel is None:
            continue
        # relative_to_user with heading (0,1) yields lateral = -x, forward = y.
        out.append((rel.label, (-rel.lateral_m, rel.forward_m)))
    return out
