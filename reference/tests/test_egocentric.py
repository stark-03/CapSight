"""Unit tests for first-person (egocentric) geometry. No camera, no phone, no threads.

In this mode the user IS the camera: they sit at the origin facing +Y, and every
obstacle is placed from its apparent size and where it falls across the frame.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from blenav.egocentric import (
    OBJECT_HEIGHTS_M, OBJECT_WIDTHS_M, UNRELIABLE_SMALL, focal_from_fov, estimate_distance,
    to_relative, egocentric_obstacles,
)
from blenav.detector import Detection, DetectionResult
from blenav.guidance import GuidanceLoop, P_STOP, P_AVOID
from blenav.world import in_corridor

PASS = FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [ok]   {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def near(a, b, tol=1e-6):
    return abs(a - b) < tol


def det(label, x1, y1, x2, y2, conf=0.9):
    """A detection with the given pixel box."""
    return Detection(label=label, conf=conf, xyxy=(x1, y1, x2, y2), cls_id=0)


# A 640-wide frame with a 65 degree horizontal field of view.
W, H = 640, 480
FOV = 65.0
F = focal_from_fov(FOV, W)


print("\n--- focal length from field of view ---")
check("65 deg over 640 px gives a plausible focal length", 480.0 < F < 530.0, f"got {F:.1f}")
check("wider field of view -> shorter focal length",
      focal_from_fov(90.0, W) < focal_from_fov(45.0, W))
check("focal scales with frame width",
      near(focal_from_fov(FOV, 1280) / focal_from_fov(FOV, 640), 2.0, 1e-9))
check("a 90 deg field of view puts focal at half the width",
      near(focal_from_fov(90.0, 640), 320.0, 1e-6))


print("\n--- distance from apparent size ---")
# A chair is 0.90 m tall. At distance d its pixel height is f * 0.90 / d.
chair_h = OBJECT_HEIGHTS_M["chair"]
for d_true in (1.0, 2.0, 4.0):
    px_h = F * chair_h / d_true
    box = det("chair", 300, 100, 340, 100 + px_h)
    got = estimate_distance(box, F)
    check(f"chair {px_h:.0f}px tall -> {d_true:.1f} m", near(got, d_true, 1e-6), f"got {got}")

check("a taller box means a nearer object",
      estimate_distance(det("chair", 0, 0, 40, 300), F)
      < estimate_distance(det("chair", 0, 0, 40, 100), F))

check("an unknown class has no size prior, so no distance",
      estimate_distance(det("unicorn", 0, 0, 40, 100), F) is None)

check("a zero-height box cannot give a distance",
      estimate_distance(det("chair", 10, 50, 40, 50), F) is None)

check("person is a known obstacle in first-person mode",
      "person" in OBJECT_HEIGHTS_M)

# Lower bound is 0.02 m because flat objects are genuinely that thin: a keyboard
# lying on a desk is about 3 cm tall. Upper bound rules out anything taller than
# a person, which would mean a mis-typed prior.
check("every size prior is a plausible real-world height",
      all(0.02 < v < 2.5 for v in OBJECT_HEIGHTS_M.values()),
      f"{OBJECT_HEIGHTS_M}")

check("flat desk objects are flagged as unreliable for distance",
      set(UNRELIABLE_SMALL) <= set(OBJECT_HEIGHTS_M),
      f"{UNRELIABLE_SMALL}")


print("\n--- clipped boxes: an object too close to fit in frame ---")
# The size prior assumes the WHOLE object is visible. When it is not - you are
# standing close enough that it overflows the frame - the visible box is smaller
# than the true projection, so the naive estimate says "further away" exactly
# when the object is nearest. That is the unsafe direction, so it is corrected.
#
# The bound is rigorous: an object that overflows the frame projects taller than
# the frame, so it must be nearer than the distance at which it exactly fills it.
person_h = OBJECT_HEIGHTS_M["person"]
fill_bound = F * person_h / H          # distance at which a person exactly fills the frame

full = det("person", 250, 0, 400, H)             # touches top AND bottom
naive = F * person_h / H
check("a person filling the frame is bounded by the fill distance",
      estimate_distance(full, F, frame_h=H) <= fill_bound + 1e-9,
      f"got {estimate_distance(full, F, frame_h=H)} bound {fill_bound}")

cut_bottom = det("person", 250, 120, 400, H)     # head visible, legs cut off
naive_cut = estimate_distance(cut_bottom, F)
corrected = estimate_distance(cut_bottom, F, frame_h=H)
check("a person cut off by the frame edge is reported NEARER, not further",
      corrected < naive_cut, f"naive {naive_cut:.2f} corrected {corrected:.2f}")

whole = det("person", 250, 60, 400, 60 + F * person_h / 3.0)   # fully inside frame
check("a fully visible object is left completely alone",
      near(estimate_distance(whole, F, frame_h=H),
           estimate_distance(whole, F), 1e-9))

check("omitting frame height keeps the old behaviour",
      near(estimate_distance(cut_bottom, F), naive_cut, 1e-9))

check("the correction never reports a negative or zero distance",
      estimate_distance(full, F, frame_h=H) > 0)


# A box can be truncated WITHOUT touching a frame edge - a desk hides your legs,
# a table hides a chair's base. The tell is the aspect ratio: a whole person is
# far taller than wide, so a near-square person box means the height is not the
# whole person and only the width can be trusted.
check("every class with a height prior also has a width prior",
      set(OBJECT_WIDTHS_M) == set(OBJECT_HEIGHTS_M),
      f"missing {set(OBJECT_HEIGHTS_M) ^ set(OBJECT_WIDTHS_M)}")
check("every width prior is a plausible real-world width",
      all(0.02 < v < 2.5 for v in OBJECT_WIDTHS_M.values()), f"{OBJECT_WIDTHS_M}")

# The case that started this: a real webcam box, person truly about 0.7 m away.
head = det("person", 190, 100, 450, 100 + 264)
got = estimate_distance(head, F, frame_h=H)
check("a close cropped person is no longer mistaken for 3 m away",
      got < 2.5, f"got {got:.2f} m")
check("and the corrected distance is close to the truth (~0.7 m)",
      0.4 < got < 1.5, f"got {got:.2f} m")

# A fully visible person must be untouched by the truncation guard.
d3 = 3.0
whole_person = det("person", 300, 50,
                   300 + F * OBJECT_WIDTHS_M["person"] / d3,
                   50 + F * person_h / d3)
check("a fully visible person still uses the height estimate",
      near(estimate_distance(whole_person, F, frame_h=H), d3, 1e-6),
      f"got {estimate_distance(whole_person, F, frame_h=H)}")

# A chair whose base is hidden under a table.
d2 = 2.0
cut_chair = det("chair", 300, 100,
                300 + F * OBJECT_WIDTHS_M["chair"] / d2,
                100 + F * OBJECT_HEIGHTS_M["chair"] / d2 * 0.35)   # only 35% visible
naive_chair = F * OBJECT_HEIGHTS_M["chair"] / (F * OBJECT_HEIGHTS_M["chair"] / d2 * 0.35)
check("a chair with its base hidden is reported nearer than the naive estimate",
      estimate_distance(cut_chair, F, frame_h=H) < naive_chair,
      f"naive {naive_chair:.2f} got {estimate_distance(cut_chair, F, frame_h=H):.2f}")
check("and lands near its true 2 m",
      1.4 < estimate_distance(cut_chair, F, frame_h=H) < 2.6,
      f"got {estimate_distance(cut_chair, F, frame_h=H):.2f}")


print("\n--- bearing: where the obstacle sits across the frame ---")
# Dead centre must be straight ahead: all range, no sideways offset.
centre = det("chair", W / 2 - 20, 100, W / 2 + 20, 100 + F * chair_h / 2.0)
rel = to_relative(centre, W, F)
check("dead centre -> no lateral offset", near(rel.lateral_m, 0.0, 1e-9), f"got {rel.lateral_m}")
check("dead centre -> forward distance is the estimate", near(rel.forward_m, 2.0, 1e-6),
      f"got {rel.forward_m}")

left = det("chair", 40, 100, 80, 100 + F * chair_h / 2.0)
right = det("chair", W - 80, 100, W - 40, 100 + F * chair_h / 2.0)
check("object on the left of frame -> positive lateral (user's left)",
      to_relative(left, W, F).lateral_m > 0, f"got {to_relative(left, W, F).lateral_m}")
check("object on the right of frame -> negative lateral (user's right)",
      to_relative(right, W, F).lateral_m < 0, f"got {to_relative(right, W, F).lateral_m}")
check("left and right at equal offset are mirror images",
      near(to_relative(left, W, F).lateral_m, -to_relative(right, W, F).lateral_m, 1e-6))
check("side property agrees with the sign", to_relative(left, W, F).side == "left")
check("side property agrees on the right", to_relative(right, W, F).side == "right")

check("the label is carried through", to_relative(left, W, F).label == "chair")
check("an unknown class yields no relative obstacle",
      to_relative(det("unicorn", 40, 100, 80, 300), W, F) is None)

# Further away, the same angular offset means a bigger sideways distance.
near_box = det("chair", 40, 100, 80, 100 + F * chair_h / 1.0)   # 1 m
far_box = det("chair", 40, 100, 80, 100 + F * chair_h / 4.0)    # 4 m
check("same bearing, further away -> larger lateral offset",
      abs(to_relative(far_box, W, F).lateral_m) > abs(to_relative(near_box, W, F).lateral_m))


print("\n--- corridor filtering reuses the existing rule ---")
ahead = to_relative(centre, W, F)
check("an obstacle straight ahead at 2 m is in the walking corridor",
      in_corridor(ahead, 0.55, 3.5))
far_side = to_relative(det("chair", 0, 100, 30, 100 + F * chair_h / 3.0), W, F)
check("an obstacle far off to the side is not in the corridor",
      not in_corridor(far_side, 0.55, 3.5), f"lateral {far_side.lateral_m:.2f}")


print("\n--- egocentric_obstacles: detections -> policy input ---")
dets = [
    det("chair", W / 2 - 20, 100, W / 2 + 20, 100 + F * chair_h / 2.0),
    det("unicorn", 10, 10, 50, 200),                       # unknown class, dropped
    det("bottle", 200, 100, 220, 100 + F * OBJECT_HEIGHTS_M["bottle"] / 3.0),
    det("chair", 10, 10, 50, 10, conf=0.9),                # zero height, dropped
]
obs = egocentric_obstacles(dets, W, F)
check("only obstacles with a size prior survive", len(obs) == 2, f"got {len(obs)}")
check("output is (label, (x, y)) pairs ready for GuidanceInput",
      all(isinstance(o, tuple) and len(o) == 2 and isinstance(o[0], str)
          and len(o[1]) == 2 for o in obs), f"got {obs}")
check("labels are preserved for speaking",
      sorted(o[0] for o in obs) == ["bottle", "chair"], f"got {[o[0] for o in obs]}")

# The world positions must round-trip through relative_to_user with the user at
# the origin facing +Y, because that is exactly what the guidance policy does.
from blenav.world import relative_to_user
label, pos = [o for o in obs if o[0] == "chair"][0]
back = relative_to_user((0.0, 0.0), (0.0, 1.0), pos, label)
check("chair round-trips to 2 m ahead through relative_to_user",
      near(back.forward_m, 2.0, 1e-6), f"got {back.forward_m}")
check("chair round-trips to no lateral offset",
      near(back.lateral_m, 0.0, 1e-9), f"got {back.lateral_m}")

left_pos = to_relative(left, W, F)
lp = egocentric_obstacles([left], W, F)[0][1]
back_left = relative_to_user((0.0, 0.0), (0.0, 1.0), lp, "chair")
check("a left-of-frame obstacle stays on the left after round-trip",
      back_left.lateral_m > 0 and near(back_left.lateral_m, left_pos.lateral_m, 1e-6),
      f"got {back_left.lateral_m} vs {left_pos.lateral_m}")


print("\n--- flat desk objects are not walking hazards ---")
# A keyboard is 3 cm tall, so a few pixels of box error swings its distance
# wildly - and it sits on a desk, not in the walking corridor. Announcing it
# would be noise, so first-person mode skips these by default.
kbd = det("keyboard", 300, 200, 380, 200 + F * OBJECT_HEIGHTS_M["keyboard"] / 2.0)
check("flat desk objects are skipped by default",
      len(egocentric_obstacles([kbd], W, F)) == 0)
check("they can still be included on request",
      len(egocentric_obstacles([kbd], W, F, skip_unreliable=False)) == 1)
check("a chair is never treated as an unreliable desk object",
      "chair" not in UNRELIABLE_SMALL)
check("a person is never treated as an unreliable desk object",
      "person" not in UNRELIABLE_SMALL)


print("\n--- confidence gate ---")
weak = det("chair", 300, 100, 340, 300, conf=0.10)
check("low-confidence detections are dropped",
      len(egocentric_obstacles([weak], W, F, min_conf=0.40)) == 0)
check("high-confidence detections are kept",
      len(egocentric_obstacles([det("chair", 300, 100, 340, 300, conf=0.9)],
                               W, F, min_conf=0.40)) == 1)


print("\n--- GuidanceLoop wired for first-person ---")


class StubDetector:
    def __init__(self, result): self._r = result
    def read(self): return self._r


class StubSpeaker:
    def __init__(self): self.said = []
    def say(self, text, priority=3, cooldown_s=3.0):
        self.said.append((priority, text)); return True


GCFG = {
    "loop_hz": 10, "heading_window_s": 0.8,
    "stop_distance_m": 1.5, "warn_distance_m": 3.5, "stairs_warn_m": 4.0,
    "path_deviation_m": 0.6, "clear_repeat_s": 6.0,
    "speak_object_names": True,
}
FP = {"horizontal_fov_deg": FOV, "min_confidence": 0.40}


def run_tick(obstacles):
    res = DetectionResult(persons=[], obstacles=obstacles,
                          frame_w=W, frame_h=H, stamp=1.0)
    spk = StubSpeaker()
    loop = GuidanceLoop(detector=StubDetector(res), matcher=None, speaker=spk,
                        cfg=GCFG, first_person=FP)
    loop._tick()
    return loop.decision, spk


chair_1m = det("chair", W / 2 - 30, 100, W / 2 + 30, 100 + F * chair_h / 1.0)
d, spk = run_tick([chair_1m])
check("names the obstacle it saw", "Chair" in d.message, f"got {d.message!r}")
check("a chair at 1 m is a stop", d.priority == P_STOP, f"got {d.priority}")
check("the instruction was actually spoken", any("Chair" in t for _, t in spk.said),
      f"got {spk.said}")

chair_3m = det("chair", 120, 100, 180, 100 + F * chair_h / 3.0)
d3, _ = run_tick([chair_3m])
check("a chair at 3 m off to the left is an avoid, not a stop",
      d3.priority in (P_AVOID, 4), f"got {d3.priority} {d3.message!r}")

d0, _ = run_tick([])
check("an empty room is all clear", d0.message == "Continue straight.",
      f"got {d0.message!r}")

check("first-person mode needs no BLE and no calibration",
      "NOT CALIBRATED" not in d0.reason and "BLE" not in d0.reason,
      f"got {d0.reason!r}")

res_named = DetectionResult(persons=[det("person", W / 2 - 40, 50,
                                         W / 2 + 40, 50 + F * 1.70 / 1.2)],
                            obstacles=[], frame_w=W, frame_h=H, stamp=1.0)
spk2 = StubSpeaker()
loop2 = GuidanceLoop(detector=StubDetector(res_named), matcher=None, speaker=spk2,
                     cfg=GCFG, first_person=FP)
loop2._tick()
check("a person in the way is treated as an obstacle and named",
      "Person" in loop2.decision.message, f"got {loop2.decision.message!r}")


print("\n--- dropout hold: one missed frame must not mean all-clear ---")
# Walking towards a chair, the detector misses it now and then (motion blur).
# Without a hold, a single missed frame swings the instruction all the way to
# "Continue straight" - a FALSE ALL-CLEAR, and it happens when you are closest.
# The hold is deliberately asymmetric: react at once to a new hazard, but require
# its absence to persist before standing down.
from blenav.egocentric import OBJECT_WIDTHS_M


def chair_box(dist):
    h = F * chair_h / dist
    w = F * OBJECT_WIDTHS_M["chair"] / dist
    return det("chair", W / 2 - w / 2, 150, W / 2 + w / 2, 150 + h)


def walk(drop_at, hold_ticks):
    """Walk 3.5 m -> 0.5 m; drop the detection on the given tick indices."""
    stub = StubDetector(DetectionResult())
    fp = dict(FP)
    if hold_ticks is not None:
        fp["hold_ticks"] = hold_ticks
    loop = GuidanceLoop(detector=stub, matcher=None, speaker=StubSpeaker(),
                        cfg=GCFG, first_person=fp)
    msgs, d, i = [], 3.5, 0
    while d > 0.5:
        seen = i not in drop_at
        stub._r = DetectionResult(obstacles=[chair_box(d)] if seen else [],
                                  frame_w=W, frame_h=H, stamp=0.0)
        loop._tick()
        msgs.append(loop.decision.message)
        d -= 0.12
        i += 1
    return msgs


# Tick 20 is roughly 1.1 m away - inside the stop distance.
DROPS = {9, 14, 20, 21}

no_hold = walk(DROPS, hold_ticks=0)
with_hold = walk(DROPS, hold_ticks=6)

check("without a hold, a dropout causes a false all-clear",
      "Continue straight." in no_hold[18:], "expected the bug to reproduce")
check("with a hold, there is no all-clear once the chair is close",
      "Continue straight." not in with_hold[15:],
      f"got {[m for m in with_hold[15:] if 'Continue' in m]}")


def flips(ms):
    return sum(1 for a, b in zip(ms, ms[1:]) if a != b)


check("the hold reduces message changes", flips(with_hold) < flips(no_hold),
      f"no_hold={flips(no_hold)} with_hold={flips(with_hold)}")
check("a clean walk still gives the natural clear->avoid->stop progression",
      flips(walk(set(), 6)) <= 3, f"got {flips(walk(set(), 6))}")

# The hold must expire: an obstacle really gone must eventually clear.
stub = StubDetector(DetectionResult())
loop = GuidanceLoop(detector=stub, matcher=None, speaker=StubSpeaker(),
                    cfg=GCFG, first_person=dict(FP, hold_ticks=4))
stub._r = DetectionResult(obstacles=[chair_box(1.0)], frame_w=W, frame_h=H)
loop._tick()
check("hazard is reported while present", "Chair" in loop.decision.message)
stub._r = DetectionResult(obstacles=[], frame_w=W, frame_h=H)
for _ in range(3):
    loop._tick()
check("still held a few ticks after it vanishes", "Chair" in loop.decision.message,
      f"got {loop.decision.message!r}")
for _ in range(5):
    loop._tick()
check("but the hold expires and it does clear",
      loop.decision.message == "Continue straight.", f"got {loop.decision.message!r}")

check("a NEW closer hazard is reported immediately, not held back",
      "Chair" in walk(set(), 6)[-1] or "Stop" in walk(set(), 6)[-1],
      f"got {walk(set(), 6)[-1]!r}")


print("\n" + "=" * 54)
print(f" {PASS} passed, {FAIL} failed")
print("=" * 54)
sys.exit(1 if FAIL else 0)
