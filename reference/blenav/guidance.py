"""
The guidance brain: decides what to say, and when.

Split in two on purpose:

  decide()       - a PURE function. Same inputs, same output, no threads, no
                   hardware, no clock. This is where every navigation rule
                   lives, and it is fully unit-testable.
  GuidanceLoop   - the thread that gathers inputs at a fixed rate and hands
                   them to decide(). Contains no navigation logic at all.

Running at a fixed 10 Hz regardless of detector speed is what keeps warnings
timely on the slow demo machine: between YOLO passes the user's position is
extrapolated from their last known velocity.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field

from .world import NavPath, UserState, free_side, in_corridor, relative_to_user
from .egocentric import egocentric_obstacles, focal_from_fov

# Priorities: lower wins.
P_STOP, P_STAIRS, P_AVOID, P_DEVIATION, P_CLEAR, P_IDLE = 0, 1, 2, 3, 4, 5

BODY_HALF_WIDTH_M = 0.55        # shoulder width + clearance margin


@dataclass
class GuidanceInput:
    ble_present: bool = False
    target_locked: bool = False
    user_pos: tuple[float, float] | None = None
    heading: tuple[float, float] | None = None
    obstacles: list[tuple[str, tuple[float, float]]] = field(default_factory=list)
    stairs: list[tuple[str, tuple[float, float]]] = field(default_factory=list)
    path: NavPath | None = None
    calibrated: bool = True


@dataclass
class Decision:
    priority: int
    message: str
    reason: str
    cooldown_s: float = 3.0

    @property
    def speak(self) -> bool:
        return bool(self.message)


def _what(label: str, cfg: dict) -> str:
    """What to call the thing in speech.

    The ceiling-camera mode says "Obstacle" because the examiner cares about the
    geometry, not the furniture. First-person mode names it, because the user is
    walking towards it and "Chair ahead" is far more use than "Obstacle ahead".
    """
    if not cfg.get("speak_object_names", False):
        return "Obstacle"
    return label.capitalize()


def decide(inp: GuidanceInput, cfg: dict) -> Decision:
    """Pure navigation policy. Returns the single highest-priority instruction."""
    if not inp.ble_present:
        return Decision(P_IDLE, "", "waiting for the user's BLE device", 10.0)
    if not inp.calibrated:
        return Decision(P_IDLE, "", "NOT CALIBRATED - run calibrate.py", 30.0)
    if not inp.target_locked:
        return Decision(P_IDLE, "", "BLE present, user not yet seen by camera", 10.0)
    if inp.user_pos is None:
        return Decision(P_IDLE, "", "user seen but position not yet resolved", 10.0)

    # Standing still leaves heading undefined. On a predefined route the path
    # direction is a sound assumption - better than refusing to guide at all.
    heading = inp.heading
    if heading is None and inp.path is not None and inp.path.valid:
        heading = _path_direction(inp.path, inp.user_pos)
    if heading is None:
        return Decision(P_IDLE, "", "no heading yet - user has not started walking", 10.0)

    warn_m = cfg["warn_distance_m"]
    stop_m = cfg["stop_distance_m"]

    rels = []
    for label, pos in inp.obstacles:
        r = relative_to_user(inp.user_pos, heading, pos, label)
        if r is not None and in_corridor(r, BODY_HALF_WIDTH_M, warn_m):
            rels.append(r)

    stair_rels = []
    for label, pos in inp.stairs:
        r = relative_to_user(inp.user_pos, heading, pos, label)
        if r is not None and in_corridor(r, BODY_HALF_WIDTH_M * 2, cfg["stairs_warn_m"]):
            stair_rels.append(r)

    # ---- P0: something is close enough that walking on means collision ----
    imminent = [r for r in rels if r.forward_m <= stop_m]
    if imminent:
        nearest = min(imminent, key=lambda r: r.forward_m)
        return Decision(P_STOP, f"Stop. {_what(nearest.label, cfg)} ahead.",
                        f"{nearest.label} at {nearest.forward_m:.1f} m", 2.0)

    # ---- P1: stairs are a fall risk, so they outrank ordinary obstacles ----
    if stair_rels:
        nearest = min(stair_rels, key=lambda r: r.forward_m)
        return Decision(P_STAIRS, "Caution. Stairs ahead.",
                        f"stairs at {nearest.forward_m:.1f} m", 4.0)

    # ---- P2: obstacle ahead but there is still room to steer around it ----
    if rels:
        nearest = min(rels, key=lambda r: r.forward_m)
        side = free_side(rels, BODY_HALF_WIDTH_M)
        return Decision(P_AVOID, f"{_what(nearest.label, cfg)} ahead. Move {side}.",
                        f"{nearest.label} at {nearest.forward_m:.1f} m, steer {side}", 3.0)

    # ---- P3: drifting off the predefined route ----
    if inp.path is not None and inp.path.valid:
        dev, side = inp.path.deviation(inp.user_pos)
        if dev > cfg["path_deviation_m"]:
            drift = "left" if side > 0 else "right"
            correct = "right" if side > 0 else "left"
            return Decision(P_DEVIATION,
                            f"You are drifting {drift}. Move {correct}.",
                            f"{dev:.2f} m off path", 3.0)

    # ---- P4: all clear ----
    return Decision(P_CLEAR, "Continue straight.", "path clear",
                    cfg["clear_repeat_s"])


def _path_direction(path: NavPath, pos) -> tuple[float, float] | None:
    """Direction of the path segment nearest the user - the assumed heading."""
    import math
    best, best_d = None, float("inf")
    for i in range(len(path.points) - 1):
        (ax, ay), (bx, by) = path.points[i], path.points[i + 1]
        mx, my = (ax + bx) / 2, (ay + by) / 2
        d = math.hypot(pos[0] - mx, pos[1] - my)
        if d < best_d:
            n = math.hypot(bx - ax, by - ay)
            if n > 1e-6:
                best_d, best = d, ((bx - ax) / n, (by - ay) / n)
    return best


# ============================================================== the loop
class GuidanceLoop:
    """Gathers inputs at a fixed rate and feeds decide(). No policy lives here."""

    def __init__(self, detector, matcher, speaker, cfg,
                 ble=None, plane=None, path=None, first_person=None):
        self.detector = detector
        self.matcher = matcher
        self.speaker = speaker
        self.cfg = cfg
        self.ble = ble
        self.plane = plane
        self.path = path or NavPath()
        # When set, the camera is carried BY the user: no homography, no BLE
        # matching, no predefined route. See blenav/egocentric.py.
        self.first_person = first_person
        # Dropout hold for first-person mode. A single missed detection must not
        # be read as "all clear" - see _tick_first_person.
        self._fp_last_obstacles: list = []
        self._fp_hold_left = 0

        self.user = UserState(heading_window_s=cfg["heading_window_s"])
        self.period = 1.0 / cfg["loop_hz"]

        self._decision = Decision(P_IDLE, "", "starting", 10.0)
        self._ble_state = {"present": False, "rssi": 0.0, "distance_m": 0.0}
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._running = False

    def start(self) -> "GuidanceLoop":
        self._running = True
        self._thread = threading.Thread(target=self._run, name="guidance", daemon=True)
        self._thread.start()
        return self

    def _floor(self, det):
        """Detection -> floor metres, or None when uncalibrated."""
        if self.plane is None:
            return None
        return self.plane.to_floor(*det.foot_point)

    def _run(self):
        while self._running:
            t0 = time.perf_counter()
            try:
                self._tick()
            except Exception as exc:
                print(f"[guidance] tick error: {exc}")
            time.sleep(max(0.0, self.period - (time.perf_counter() - t0)))

    def _tick(self):
        result = self.detector.read()
        if self.first_person is not None:
            return self._tick_first_person(result)
        ble = self.ble.read() if self.ble else {"present": True, "rssi": 0.0, "distance_m": 0.0}

        def cam_distance(det):
            pos = self._floor(det)
            return None if pos is None else (pos[0] ** 2 + pos[1] ** 2) ** 0.5

        target_id = self.matcher.update(result.persons, ble, cam_distance)
        target = next((p for p in result.persons if p.track_id == target_id), None)

        if target is not None:
            pos = self._floor(target)
            if pos is not None:
                self.user.update(pos, stamp=result.stamp or time.time())

        # Everyone who is not the target is an obstacle in their own right.
        obstacles = [(d.label, self._floor(d)) for d in result.obstacles]
        obstacles += [(f"person", self._floor(p)) for p in result.persons
                      if p.track_id != target_id]
        obstacles = [(lab, p) for lab, p in obstacles if p is not None]
        stairs = [(d.label, self._floor(d)) for d in result.stairs]
        stairs = [(lab, p) for lab, p in stairs if p is not None]

        inp = GuidanceInput(
            ble_present=bool(ble.get("present")),
            target_locked=target is not None,
            user_pos=self.user.predict(time.time()),
            heading=self.user.heading,
            obstacles=obstacles, stairs=stairs,
            path=self.path, calibrated=self.plane is not None,
        )
        decision = decide(inp, self.cfg)

        if decision.speak:
            if decision.priority == P_STOP:
                self.speaker.say(decision.message, P_STOP, decision.cooldown_s)
            else:
                self.speaker.say(decision.message, decision.priority, decision.cooldown_s)

        with self._lock:
            self._decision = decision
            self._ble_state = ble

    def _tick_first_person(self, result):
        """The camera is the user: obstacles come from apparent size, not a floor plane.

        The user is at the origin facing +Y, which is the frame decide() already
        reasons in, so the navigation policy runs completely unmodified.
        """
        fp = self.first_person
        obstacles = []
        if result.frame_w:
            focal = focal_from_fov(fp.get("horizontal_fov_deg", 65.0), result.frame_w)
            # A person in the way is an obstacle like any other - there is no
            # "which one is the user" question when the user holds the camera.
            obstacles = egocentric_obstacles(
                list(result.obstacles) + list(result.persons),
                result.frame_w, focal,
                min_conf=fp.get("min_confidence", 0.0),
                skip_unreliable=fp.get("skip_unreliable", True),
                frame_h=result.frame_h,
            )

        # Asymmetric hold. React to a hazard the instant it appears, but do NOT
        # stand down the instant it vanishes: the detector misses objects to
        # motion blur while walking, and a single missed frame would otherwise
        # produce a false all-clear at the moment the user is closest.
        #
        # This holds the last MEASUREMENT rather than a stale pixel box, which is
        # why the detector's pixel cache is switched off in this mode - that cache
        # assumes a camera that does not move.
        hold_ticks = int(fp.get("hold_ticks", 6))
        if obstacles:
            self._fp_last_obstacles = obstacles
            self._fp_hold_left = hold_ticks
        elif self._fp_hold_left > 0:
            self._fp_hold_left -= 1
            obstacles = self._fp_last_obstacles
        else:
            self._fp_last_obstacles = []

        inp = GuidanceInput(
            ble_present=True,          # no beacon question in first-person mode
            target_locked=True,        # the user is definitionally present
            user_pos=(0.0, 0.0),       # the user is the origin
            heading=(0.0, 1.0),        # and always faces straight ahead
            obstacles=obstacles, stairs=[],
            path=None,                 # no predefined route to drift from
            calibrated=True,           # apparent size needs no calibration
        )
        decision = decide(inp, self.cfg)

        if decision.speak:
            self.speaker.say(decision.message, decision.priority, decision.cooldown_s)

        with self._lock:
            self._decision = decision
            self._ble_state = {"present": True, "rssi": 0.0, "distance_m": 0.0}

    # ------------------------------------------------------------------ read
    @property
    def decision(self) -> Decision:
        with self._lock:
            return self._decision

    @property
    def ble_state(self) -> dict:
        with self._lock:
            return self._ble_state

    def stop(self):
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=3.0)
