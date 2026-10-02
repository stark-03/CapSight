"""Unit tests for the floor-plane geometry. No camera, no BLE, no threads."""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from blenav.world import (
    FloorPlane, NavPath, UserState, relative_to_user, in_corridor, free_side,
)

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


print("\n--- FloorPlane: perspective -> metres ---")
# A trapezoid on screen (far edge narrower) mapping to a 4m x 3m floor rectangle.
pix = [(100, 700), (900, 700), (700, 300), (300, 300)]   # near-L, near-R, far-R, far-L
plane = FloorPlane.from_rectangle(pix, width_m=4.0, depth_m=3.0)

for label, p, expect in [("near-left", pix[0], (0.0, 0.0)),
                         ("near-right", pix[1], (4.0, 0.0)),
                         ("far-right", pix[2], (4.0, 3.0)),
                         ("far-left", pix[3], (0.0, 3.0))]:
    got = plane.to_floor(*p)
    check(f"corner {label} -> {expect}", near(got[0], expect[0], 1e-4) and near(got[1], expect[1], 1e-4), f"got {got}")

# Perspective is non-linear, so the image centre is NOT the rectangle centre;
# what must hold is that the round trip is exact.
back = plane.to_pixel(*plane.to_floor(500, 500))
check("pixel -> floor -> pixel round trip", near(back[0], 500, 1e-3) and near(back[1], 500, 1e-3), f"got {back}")

mid_near = plane.to_floor(500, 700)      # midpoint of the near edge
check("near-edge midpoint is (2.0, 0.0)", near(mid_near[0], 2.0, 1e-4) and near(mid_near[1], 0.0, 1e-4), f"got {mid_near}")

check("further up the image = larger Y (depth)", plane.to_floor(500, 400)[1] > plane.to_floor(500, 650)[1])


print("\n--- NavPath: deviation and side ---")
path = NavPath(points=[(0.0, 0.0), (0.0, 5.0)])          # straight, walking +Y
d, side = path.deviation((0.5, 2.0))
check("0.5 m off path measures 0.5 m", near(d, 0.5, 1e-6), f"got {d}")
check("+X of a +Y path reports RIGHT (side=-1)", side < 0, f"side={side}")
d, side = path.deviation((-0.5, 2.0))
check("-X of a +Y path reports LEFT (side=+1)", side > 0, f"side={side}")
check("on the path = zero deviation", near(path.deviation((0.0, 3.0))[0], 0.0, 1e-9))
check("beyond the end clamps to endpoint", near(path.deviation((0.0, 9.0))[0], 4.0, 1e-6))
check("single-point path is invalid", not NavPath(points=[(0, 0)]).valid)


print("\n--- UserState: speed and heading ---")
u = UserState(heading_window_s=1.0)
for i in range(11):                       # 1 m/s straight along +Y for 1 s
    u.update((0.0, i * 0.1), stamp=100.0 + i * 0.1)
check("speed ~1.0 m/s", near(u.speed, 1.0, 0.05), f"got {u.speed:.3f}")
h = u.heading
check("heading is +Y", h is not None and near(h[0], 0.0, 1e-6) and near(h[1], 1.0, 1e-6), f"got {h}")

still = UserState(heading_window_s=1.0)
for i in range(11):                       # standing still -> heading undefined, not garbage
    still.update((2.0, 2.0), stamp=100.0 + i * 0.1)
check("stationary user has no heading", still.heading is None)
check("stationary user has ~0 speed", near(still.speed, 0.0, 1e-6))

pred = u.predict(u.last_update + 0.2)     # 1 m/s for 0.2 s = +0.2 m
check("predict extrapolates 0.2 m ahead", near(pred[1], 1.0 + 0.2, 0.02), f"got {pred}")


print("\n--- Obstacle geometry (user frame) ---")
pos, heading = (0.0, 0.0), (0.0, 1.0)     # at origin, walking +Y
r = relative_to_user(pos, heading, (0.0, 2.0), "chair")
check("dead ahead: forward 2 m, lateral 0", near(r.forward_m, 2.0) and near(r.lateral_m, 0.0), f"got {r}")

r_left = relative_to_user(pos, heading, (-1.0, 2.0))
check("-X obstacle is on the LEFT", r_left.side == "left", f"got {r_left.side}")
r_right = relative_to_user(pos, heading, (1.0, 2.0))
check("+X obstacle is on the RIGHT", r_right.side == "right", f"got {r_right.side}")

behind = relative_to_user(pos, heading, (0.0, -2.0))
check("obstacle behind has negative forward", behind.forward_m < 0)
check("obstacle behind is not in corridor", not in_corridor(behind, 0.6, 4.0))
check("dead-ahead obstacle IS in corridor", in_corridor(r, 0.6, 4.0))
check("obstacle 3 m to the side is not in corridor",
      not in_corridor(relative_to_user(pos, heading, (3.0, 2.0)), 0.6, 4.0))
check("obstacle beyond max range is not in corridor", not in_corridor(r, 0.6, 1.0))
check("no heading -> no relative obstacle", relative_to_user(pos, None, (0.0, 2.0)) is None)


print("\n--- free_side: which way to steer ---")
check("obstacle on left -> steer right", free_side([r_left], 0.6) == "right")
check("obstacle on right -> steer left", free_side([r_right], 0.6) == "left")
check("blocked both sides -> still returns a side",
      free_side([r_left, r_right], 0.6) in ("left", "right"))
check("no obstacles -> defaults right", free_side([], 0.6) == "right")


print("\n" + "=" * 46)
print(f" {PASS} passed, {FAIL} failed")
print("=" * 46)
sys.exit(1 if FAIL else 0)
