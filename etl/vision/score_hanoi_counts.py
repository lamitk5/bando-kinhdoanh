#!/usr/bin/env python3
"""Chấm số đếm trên nhãn mắt của ảnh Hà Nội. Không chấm ảnh ngoài Hà Nội và không dùng nhãn Seoul."""

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures"
LABELS = ROOT / "hanoi_visible_counts.json"
PYTHON = ROOT.parents[1] / ".venv-yolo" / "bin" / "python"
COUNTER = ROOT / "yolo_count.py"


def in_range(value, low, high):
    if high is None:
        return value >= low
    return low <= value <= high


def points_for(value, low, high, weight):
    if in_range(value, low, high):
        return weight, "khớp"
    if high is not None and value > high:
        over = value - high
        return max(0, weight - over * 2), f"cao hơn trần {high} đúng {over}"
    short = low - value
    return max(0, weight - short * 2), f"thấp hơn sàn {low} đúng {short}"


def main():
    labels = json.loads(LABELS.read_text(encoding="utf-8"))
    paths = [str(FIXTURES / item["file"]) for item in labels["images"]]
    proc = subprocess.run(
        [str(PYTHON), str(COUNTER)],
        input="\n".join(paths) + "\n",
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0 and not proc.stdout:
        sys.stderr.write(proc.stderr)
        raise SystemExit(proc.returncode or 1)
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    if not lines or '"ready"' not in lines[0]:
        sys.stderr.write(proc.stderr)
        sys.stderr.write(proc.stdout)
        raise SystemExit("Bộ đếm không sẵn sàng")
    results = [json.loads(line) for line in lines[1:]]
    if len(results) != len(labels["images"]):
        raise SystemExit(f"Nhận {len(results)} kết quả, cần {len(labels['images'])}")

    earned = 0
    maximum = 0
    rows = []
    for item, result in zip(labels["images"], results):
        if result.get("error"):
            raise SystemExit(result["error"])
        counts = result["counts"]
        requested = result["requested"]
        checks = [
            ("xe máy", requested["motorcycle"], item.get("motorcycle_min"), item.get("motorcycle_max"), 12),
        ]
        if "tree_min" in item:
            checks.append(("cây", requested["tree"], item["tree_min"], item.get("tree_max"), 8))
        if "person_min" in item:
            checks.append(("người đi bộ", requested["person"], item["person_min"], item.get("person_max"), 6))
        if "vehicle_min" in item:
            vehicles = counts["car"] + counts["bus"] + counts["truck"]
            checks.append(("ô tô gộp", vehicles, item["vehicle_min"], item.get("vehicle_max"), 6))
        checks.append((
            "cảnh mặt tiền",
            1 if result["scene_verified"] else 0,
            1 if item["scene_verified"] else 0,
            1 if item["scene_verified"] else 0,
            4,
        ))
        for name, value, low, high, weight in checks:
            if low is None:
                continue
            got, note = points_for(value, low, high, weight)
            earned += got
            maximum += weight
            rows.append({
                "file": item["file"],
                "class": name,
                "value": value,
                "min": low,
                "max": high,
                "points": got,
                "weight": weight,
                "note": note,
            })

    ratio = earned / maximum if maximum else 0
    report = {
        "label_file": "etl/vision/hanoi_visible_counts.json",
        "earned": earned,
        "maximum": maximum,
        "count_score_0_100": round(100 * ratio, 1),
        "rows": rows,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
