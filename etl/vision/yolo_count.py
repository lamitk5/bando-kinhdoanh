#!/usr/bin/env python3
"""Đếm vật thể COCO bằng YOLOv8n. Lớp không có trong mô hình để null."""

import hashlib
import json
import os
import sys
import time
from pathlib import Path

os.environ["YOLO_VERBOSE"] = "False"

WEIGHTS = Path(__file__).resolve().parent / "weights" / "yolov8n.pt"
CONFIDENCE = 0.25
IMAGE_SIZE = 640
COUNTED = {
    "person": "person",
    "car": "car",
    "motorcycle": "motorcycle",
    "bus": "bus",
    "truck": "truck",
    "traffic light": "traffic_light",
    "stop sign": "stop_sign",
}
NOT_MEASURED = [
    {
        "field": "utility_pole",
        "reason": "YOLOv8n huấn luyện trên COCO không có lớp cột điện.",
    },
    {
        "field": "tree",
        "reason": "YOLOv8n huấn luyện trên COCO không có lớp cây xanh. potted plant không được đếm thành cây.",
    },
    {
        "field": "sign_blocking_visibility",
        "reason": "Lớp stop sign và traffic light không cho biết vật thể có che tầm nhìn hay không.",
    },
]


def process_rss_mb():
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) / 1024
    return 0


def emit(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def predict(model, image_path):
    started = time.perf_counter()
    result = model.predict(
        source=str(image_path),
        conf=CONFIDENCE,
        imgsz=IMAGE_SIZE,
        device="cpu",
        verbose=False,
    )[0]
    wall_ms = (time.perf_counter() - started) * 1000
    names = result.names
    detections = []
    counts = {key: 0 for key in COUNTED.values()}
    boxes = result.boxes
    if boxes is not None and boxes.cls is not None:
        classes = boxes.cls.tolist()
        confidences = boxes.conf.tolist()
        coordinates = boxes.xyxy.tolist()
        for index, class_id in enumerate(classes):
            name = names[int(class_id)]
            key = COUNTED.get(name)
            if key is None:
                continue
            counts[key] += 1
            x1, y1, x2, y2 = coordinates[index]
            detections.append({
                "class": key,
                "confidence": round(float(confidences[index]), 3),
                "box_px": [round(float(x1), 1), round(float(y1), 1), round(float(x2), 1), round(float(y2), 1)],
            })
    height, width = result.orig_shape
    speed = result.speed or {}
    inference_ms = float(speed.get("inference", wall_ms))
    if inference_ms <= 0:
        inference_ms = wall_ms
    requested = {
        "person": counts["person"],
        "car": counts["car"],
        "motorcycle": counts["motorcycle"],
        "utility_pole": None,
        "tree": None,
        "sign_blocking_visibility": None,
    }
    return {
        "weights_file": WEIGHTS.name,
        "weights_sha256": sha256(WEIGHTS),
        "dataset": "COCO",
        "confidence_threshold": CONFIDENCE,
        "image_size_px": IMAGE_SIZE,
        "image_width": int(width),
        "image_height": int(height),
        "device": "cpu",
        "inference_ms": round(inference_ms, 1),
        "wall_ms": round(wall_ms, 1),
        "process_rss_mb": round(process_rss_mb(), 1),
        "counts": counts,
        "requested": requested,
        "detections": detections,
        "not_measured": NOT_MEASURED,
        "scene_verified": False,
        "note": "Số đếm là đầu ra YOLOv8n trên ảnh này. Không phải khảo sát hiện trường.",
    }


def main():
    import logging

    logging.disable(logging.CRITICAL)
    from ultralytics import YOLO

    if not WEIGHTS.is_file():
        emit({"ready": False, "error": "Không thấy trọng số yolov8n.pt"})
        return 1
    model = YOLO(str(WEIGHTS))
    names = set(model.names.values())
    missing = [name for name in COUNTED if name not in names]
    if missing or len(names) != 80:
        emit({"ready": False, "error": "Trọng số không phải YOLOv8n COCO 80 lớp"})
        return 1
    emit({
        "ready": True,
        "weights_file": WEIGHTS.name,
        "weights_sha256": sha256(WEIGHTS),
        "class_count": len(names),
        "device": "cpu",
        "confidence_threshold": CONFIDENCE,
        "image_size_px": IMAGE_SIZE,
    })
    for line in sys.stdin:
        image = line.strip()
        if not image:
            continue
        try:
            emit(predict(model, Path(image)))
        except Exception as error:
            emit({"error": "YOLOv8n không đếm được ảnh: " + str(error)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
