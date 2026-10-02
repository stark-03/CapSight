"""
Guidance policy tests.

These encode the project's stated requirements directly:
  * obstacle directly ahead -> warn early, then "Stop. Obstacle ahead."
  * then guide the user AROUND the obstacle
  * detect stairs, detect people, detect obstacles
  * deviation from the predefined path is corrected
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from blenav.guidance import (
    GuidanceInput, decide, P_STOP, P_STAIRS, P_AVOID, P_DEVIATION, P_CLEAR, P_IDLE,
)
from blenav.world import NavPath

CFG = {
    "stop_distance_m": 1.5, "warn_distance_m": 3.5, "stairs_warn_m": 4.0,
    "path_deviation_m": 0.6, "clear_repeat_s": 6.0, "heading_window_s": 0.8,
}
PATH = NavPath(points=[(0.0, 0.0), (0.0, 8.0)])     # straight route along +Y

PASS = FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [ok]   {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def walking(**kw):
    """A user locked, on the path at origin, walking forward along +Y."""
    base = dict(ble_present=True, target_locked=True,
                user_pos=(0.0, 0.0), heading=(0.0, 1.0), path=PATH)
    base.update(kw)
    return GuidanceInput(**base)


print("\n--- Gating: say nothing until there is someone to guide ---")
d = decide(GuidanceInput(ble_present=False), CFG)
check("no BLE -> silent", d.priority == P_IDLE and not d.speak, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=False), CFG)
check("BLE present but camera has not found them -> silent",
      d.priority == P_IDLE and not d.speak, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=True,
                         user_pos=(0.0, 0.0), heading=None, path=None), CFG)
check("no heading and no path -> silent", d.priority == P_IDLE and not d.speak, d.message)


d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=None,
                         calibrated=False), CFG)
check("uncalibrated -> silent AND says so plainly",
      d.priority == P_IDLE and "CALIBRAT" in d.reason.upper(), d.reason)

d = decide(GuidanceInput(ble_present=True, target_locked=False, calibrated=True), CFG)
check("calibrated but no target -> blames the right thing",
      "not yet seen" in d.reason, d.reason)

print("\n--- Requirement: obstacle directly ahead -> 'Stop. Obstacle ahead.' ---")
d = decide(walking(obstacles=[("chair", (0.0, 1.2))]), CFG)
check("chair at 1.2 m (inside 1.5 m stop range) -> STOP",
      d.priority == P_STOP, f"got P{d.priority} '{d.message}'")
check("exact wording is 'Stop. Obstacle ahead.'",
      d.message == "Stop. Obstacle ahead.", d.message)

d = decide(walking(obstacles=[("chair", (0.0, 1.5))]), CFG)
check("chair exactly at 1.5 m still triggers STOP", d.priority == P_STOP, d.message)


print("\n--- Requirement: warn EARLY, then guide AROUND ---")
d = decide(walking(obstacles=[("chair", (0.0, 3.0))]), CFG)
check("chair at 3.0 m -> early warning, not stop", d.priority == P_AVOID, f"P{d.priority}")
check("early warning names a direction to move",
      "Move left" in d.message or "Move right" in d.message, d.message)

d = decide(walking(obstacles=[("chair", (0.4, 2.5))]), CFG)   # obstacle to the RIGHT
check("obstacle on the right -> 'Move left.'", d.message == "Obstacle ahead. Move left.", d.message)

d = decide(walking(obstacles=[("chair", (-0.4, 2.5))]), CFG)  # obstacle to the LEFT
check("obstacle on the left -> 'Move right.'", d.message == "Obstacle ahead. Move right.", d.message)

d = decide(walking(obstacles=[("chair", (0.0, 5.0))]), CFG)
check("chair at 5 m (beyond 3.5 m) -> no warning yet", d.priority == P_CLEAR, d.message)

d = decide(walking(obstacles=[("chair", (2.5, 1.0))]), CFG)
check("chair 2.5 m off to the side -> ignored, not a threat", d.priority == P_CLEAR, d.message)

d = decide(walking(obstacles=[("chair", (0.0, -2.0))]), CFG)
check("chair BEHIND the user -> ignored", d.priority == P_CLEAR, d.message)


print("\n--- Requirement: detect stairs ---")
d = decide(walking(stairs=[("stairs", (0.0, 3.0))]), CFG)
check("stairs at 3 m -> stairs warning", d.priority == P_STAIRS, f"P{d.priority}")
check("stairs wording", d.message == "Caution. Stairs ahead.", d.message)

d = decide(walking(obstacles=[("chair", (0.0, 3.0))], stairs=[("stairs", (0.0, 3.0))]), CFG)
check("stairs OUTRANK an ordinary obstacle at the same distance",
      d.priority == P_STAIRS, f"got P{d.priority}")

d = decide(walking(obstacles=[("chair", (0.0, 1.0))], stairs=[("stairs", (0.0, 3.0))]), CFG)
check("an imminent collision still outranks stairs", d.priority == P_STOP, f"P{d.priority}")


print("\n--- Requirement: detect people (a person is an obstacle) ---")
d = decide(walking(obstacles=[("person", (0.0, 1.2))]), CFG)
check("another person at 1.2 m -> STOP", d.priority == P_STOP, d.message)


print("\n--- Requirement: deviation from the predefined path ---")
d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.9, 3.0),
                         heading=(0.0, 1.0), path=PATH), CFG)
check("0.9 m right of path -> deviation warning", d.priority == P_DEVIATION, f"P{d.priority}")
check("tells the user to move LEFT to get back", "Move left" in d.message, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(-0.9, 3.0),
                         heading=(0.0, 1.0), path=PATH), CFG)
check("0.9 m left of path -> 'Move right.'", "Move right" in d.message, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.3, 3.0),
                         heading=(0.0, 1.0), path=PATH), CFG)
check("0.3 m off (within 0.6 m tolerance) -> no nagging", d.priority == P_CLEAR, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.9, 3.0),
                         heading=(0.0, 1.0), path=PATH,
                         obstacles=[("chair", (0.9, 10.2))]), CFG)
check("deviation reported when no obstacle competes", d.priority == P_DEVIATION, d.message)

d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.9, 3.0),
                         heading=(0.0, 1.0), path=PATH,
                         obstacles=[("chair", (0.9, 6.0))]), CFG)
check("an obstacle 3 m ahead OUTRANKS a path deviation", d.priority == P_AVOID, f"P{d.priority}")


d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.9, 3.0),
                         heading=(0.0, 1.0), path=PATH,
                         obstacles=[("chair", (0.9, 4.0))]), CFG)
check("an IMMINENT collision (1 m) also outranks deviation", d.priority == P_STOP, f"P{d.priority}")

print("\n--- Standing still: fall back to the path direction ---")
d = decide(GuidanceInput(ble_present=True, target_locked=True, user_pos=(0.0, 0.0),
                         heading=None, path=PATH,
                         obstacles=[("chair", (0.0, 1.0))]), CFG)
check("no heading but on a known path -> still warns", d.priority == P_STOP, f"P{d.priority}")


print("\n--- All clear ---")
d = decide(walking(), CFG)
check("empty room -> 'Continue straight.'",
      d.priority == P_CLEAR and d.message == "Continue straight.", d.message)
check("clear message repeats slowly, not constantly", d.cooldown_s >= 6.0, str(d.cooldown_s))
check("stop message has a short cooldown so it can repeat urgently",
      decide(walking(obstacles=[("chair", (0.0, 1.0))]), CFG).cooldown_s <= 2.0)


print("\n--- naming the obstacle (first-person mode) ---")
# The ceiling-camera mode says "Obstacle ahead."; first-person mode names what it
# saw. Off by default so existing behaviour and every test above is unchanged.
NAMED = dict(CFG, speak_object_names=True)

d = decide(walking(obstacles=[("chair", (0.0, 2.5))]), NAMED)
check("names the obstacle when asked", "Chair" in d.message, f"got {d.message!r}")
check("still tells the user which way to go", "Move" in d.message, f"got {d.message!r}")

d_off = decide(walking(obstacles=[("chair", (0.0, 2.5))]), CFG)
check("default wording is unchanged when the flag is absent",
      d_off.message == "Obstacle ahead. Move left." or
      d_off.message == "Obstacle ahead. Move right.", f"got {d_off.message!r}")

d_stop = decide(walking(obstacles=[("couch", (0.0, 1.0))]), NAMED)
check("names the obstacle in a stop warning too",
      "Couch" in d_stop.message, f"got {d_stop.message!r}")
check("a stop warning still says stop", d_stop.message.startswith("Stop"),
      f"got {d_stop.message!r}")
check("stop is still the top priority when named", d_stop.priority == P_STOP)

d_multi = decide(walking(obstacles=[("chair", (0.0, 3.0)), ("person", (0.0, 1.8))]), NAMED)
check("names the NEAREST obstacle, not just any",
      "Person" in d_multi.message, f"got {d_multi.message!r}")

d_two = decide(walking(obstacles=[("potted plant", (0.0, 2.5))]), NAMED)
check("multi-word labels read naturally",
      "Potted plant" in d_two.message, f"got {d_two.message!r}")

d_clear = decide(walking(obstacles=[]), NAMED)
check("naming does not affect the all-clear message",
      d_clear.message == "Continue straight.", f"got {d_clear.message!r}")


print("\n" + "=" * 52)
print(f" {PASS} passed, {FAIL} failed")
print("=" * 52)
sys.exit(1 if FAIL else 0)
