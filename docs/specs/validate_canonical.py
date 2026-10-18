"""Validate the canonical Hirai three-shot spec without any LLM arithmetic."""
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = ROOT / "2026-10-18-hirai-canonical.json"
TOL = 0.02


def main() -> int:
    spec = json.loads(SPEC.read_text("utf-8"))
    tol = spec.get("tolerances", {})
    door_tol = tol.get("doorHeightRatio", 0.03)
    speed_tol = tol.get("axisSpeedMps", 0.005)
    failures = []
    shots = spec["shots"]
    fps = spec["fps"]
    duration = spec["durationSeconds"]
    total = spec["totalFrames"]

    frames = sum(s["endFrame"] - s["startFrame"] + 1 for s in shots)
    if frames != total:
        failures.append(f"frames: {frames} != {total}")
    if any(s["endFrame"] - s["startFrame"] + 1 != round(s["durationSeconds"] * fps) for s in shots):
        failures.append("shot duration does not match frame range")

    speed = (shots[-1]["endZ"] - shots[0]["startZ"]) / duration
    if abs(speed - spec["axisSpeedMps"]) > speed_tol:
        failures.append(f"axis speed: {speed} != {spec['axisSpeedMps']}")
    for shot in shots:
        shot_speed = (shot["endZ"] - shot["startZ"]) / shot["durationSeconds"]
        if abs(shot_speed - speed) > speed_tol:
            failures.append(f"S{shot['id']} speed {shot_speed} != {speed}")

    facade = spec["house"]["facadeZ"]
    sensor = spec["sensorHeightMm"]
    focal = spec["focalLengthMm"]
    door_h = spec["door"]["heightM"]
    for shot in shots:
        for key, distance in (
                ("Start", shot["doorDistanceStartM"]),
                ("End", shot["doorDistanceEndM"])):
            expected = door_h * focal / (distance * sensor)
            actual = shot[f"doorHeightRatio{key}"]
            if abs(expected - actual) > door_tol:
                failures.append(f"S{shot['id']} {key} ratio {actual} != {expected:.3f}")

    eave = spec["house"]["eaveHeightM"]
    if eave - shots[-1]["endY"] < 0.9:
        failures.append("eave clearance below 0.9m at final camera height")
    if spec["house"]["ridgeInFrame"]:
        failures.append("ridge must stay out of frame by decision")
    if not (0 < shots[-1]["doorHeightRatioEnd"] < 0.95):
        failures.append("S3 final door ratio must be below 0.95")

    # A 16:9 full-frame sensor is 36 x 20.25 mm; vertical FOV is ~39.7 degrees.
    vfov = 2 * math.degrees(math.atan(sensor / (2 * focal)))
    if not (38 < vfov < 41):
        failures.append(f"unexpected vertical FOV {vfov:.2f}")

    # Foreground must be inside the horizontal frame at shot 1 start.
    hfov = 2 * math.degrees(math.atan(36 / (2 * focal)))
    half = math.radians(hfov / 2)
    for item in spec["foreground"]:
        z = item["z"]
        angle = math.degrees(math.atan(abs(item["x"]) / z))
        if angle >= hfov / 2:
            failures.append(f"{item['id']} outside horizontal frame: {angle:.1f}deg")
        width = 2 * z * math.tan(half)
        ratio = abs(item["x"]) / width
        low, high = item.get("frameWidthRatioRange", [ratio, ratio])
        if not (low <= ratio <= high):
            failures.append(f"{item['id']} width ratio {ratio:.3f} outside {low}..{high}")

    # S3 door must fit vertically after the downward pitch.
    s3 = shots[-1]
    low_y, high_y = s3.get("endYRange", [s3["endY"], s3["endY"]])
    low_p, high_p = s3.get("pitchDegRange", [s3["pitchDeg"], s3["pitchDeg"]])
    if not (low_y <= s3["endY"] <= high_y):
        failures.append(f"S3 endY {s3['endY']} outside {low_y}..{high_y}")
    if not (low_p <= s3["pitchDeg"] <= high_p):
        failures.append(f"S3 pitch {s3['pitchDeg']} outside {low_p}..{high_p}")
    coverage = s3["doorDistanceM"] * sensor / focal
    center = s3["endY"] + s3["doorDistanceM"] * math.tan(math.radians(s3["pitchDeg"]))
    low, high = center - coverage / 2, center + coverage / 2
    if 0 < low or door_h > high:
        failures.append(f"S3 door does not fit: frame {low:.2f}..{high:.2f}, door 0..{door_h}")

    if failures:
        print("FAIL")
        for item in failures:
            print("-", item)
        return 1
    print(f"OK frames={frames} speed={speed:.6f}m/s vfov={vfov:.2f}deg")
    print("ratios=" + ", ".join(
        f"S{s['id']}:{s['doorHeightRatioStart']}->{s['doorHeightRatioEnd']}" for s in shots))
    return 0


if __name__ == "__main__":
    sys.exit(main())
