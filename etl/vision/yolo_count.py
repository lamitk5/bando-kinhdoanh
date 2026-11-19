#!/usr/bin/env python3
"""Đếm vật thể trên máy này. Không gửi ảnh đi."""

import hashlib
import json
import os
import sys
from pathlib import Path

os.environ["YOLO_VERBOSE"] = "False"

ROOT = Path(__file__).resolve().parent
WORLD_WEIGHTS = ROOT / "weights" / "yolov8s-worldv2.pt"
WORLD_PROMPTS = ROOT / "weights" / "yolov8s-worldv2-prompts.pt"
SEG_DIR = ROOT / "weights" / "segformer-cityscapes"
CONFIDENCE = 0.25
IMAGE_SIZE = 640
MIN_AREA_FRACTION = 0.0005
CENTER = (0.30, 0.70, 0.20, 0.80)
PROMPTS = [
    "person",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "traffic light",
    "stop sign",
    "street tree",
    "hanging sign",
]
PROMPT_KEY = {
    "person": "person",
    "car": "car",
    "motorcycle": "motorcycle",
    "bus": "bus",
    "truck": "truck",
    "traffic light": "traffic_light",
    "stop sign": "stop_sign",
    "street tree": "tree",
    "hanging sign": "hanging_sign",
}
BLOCKING = {"hanging_sign", "traffic_sign"}


def emit(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def process_rss_mb():
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) / 1024
    return 0


def overlaps_center(box, width, height):
    x1, y1, x2, y2 = box
    left, right, top, bottom = CENTER
    return x2 > left * width and x1 < right * width and y2 > top * height and y1 < bottom * height


def components(mask):
    import cv2

    count, _, stats, _ = cv2.connectedComponentsWithStats(mask.astype("uint8"), connectivity=8)
    height, width = mask.shape
    minimum = MIN_AREA_FRACTION * height * width
    boxes = []
    for index in range(1, count):
        area = int(stats[index, cv2.CC_STAT_AREA])
        if area < minimum:
            continue
        x = int(stats[index, cv2.CC_STAT_LEFT])
        y = int(stats[index, cv2.CC_STAT_TOP])
        boxes.append([
            x,
            y,
            x + int(stats[index, cv2.CC_STAT_WIDTH]),
            y + int(stats[index, cv2.CC_STAT_HEIGHT]),
        ])
    return boxes


def predict(world, segmenter, processor, image_path):
    import time

    import torch
    from PIL import Image

    started = time.perf_counter()
    world_result = world.predict(
        source=str(image_path),
        conf=CONFIDENCE,
        imgsz=IMAGE_SIZE,
        device="cpu",
        verbose=False,
    )[0]
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    inputs = processor(images=image, return_tensors="pt")
    with torch.no_grad():
        logits = segmenter(**inputs).logits
    label = torch.nn.functional.interpolate(
        logits, size=(height, width), mode="bilinear", align_corners=False
    ).argmax(dim=1)[0].numpy()
    wall_ms = (time.perf_counter() - started) * 1000
    counts = {key: 0 for key in PROMPT_KEY.values()}
    counts["utility_pole"] = 0
    counts["traffic_sign"] = 0
    detections = []

    def add(key, confidence, box, source):
        blocks = key in BLOCKING and overlaps_center(box, width, height)
        if key in counts:
            counts[key] += 1
        detections.append({
            "class": key,
            "confidence": None if confidence is None else round(float(confidence), 3),
            "box_px": [round(float(value), 1) for value in box],
            "source": source,
            "blocks_center": blocks,
        })

    names = world_result.names
    boxes = world_result.boxes
    if boxes is not None and boxes.cls is not None:
        for class_id, confidence, box in zip(
            boxes.cls.tolist(), boxes.conf.tolist(), boxes.xyxy.tolist()
        ):
            prompt = names[int(class_id)]
            key = PROMPT_KEY.get(prompt)
            if key is None:
                continue
            add(key, confidence, box, "yolov8s-worldv2")
    for class_id, key in ((5, "utility_pole"), (7, "traffic_sign")):
        for box in components(label == class_id):
            add(key, None, box, "segformer-cityscapes")
    blocking = sum(1 for item in detections if item["blocks_center"])
    speed = world_result.speed or {}
    inference_ms = float(speed.get("inference", wall_ms))
    if inference_ms <= 0:
        inference_ms = wall_ms
    return {
        "weights_file": WORLD_WEIGHTS.name,
        "weights_sha256": sha256(WORLD_WEIGHTS),
        "segmenter_file": "segformer-cityscapes/model.safetensors",
        "segmenter_sha256": sha256(SEG_DIR / "model.safetensors"),
        "prompts": PROMPTS,
        "dataset": "YOLO-World prompts + Cityscapes",
        "confidence_threshold": CONFIDENCE,
        "min_area_fraction": MIN_AREA_FRACTION,
        "center_rule": "Giao với hình chữ nhật 30–70% chiều ngang và 20–80% chiều dọc",
        "image_size_px": IMAGE_SIZE,
        "image_width": width,
        "image_height": height,
        "device": "cpu",
        "inference_ms": round(inference_ms, 1),
        "wall_ms": round(wall_ms, 1),
        "process_rss_mb": round(process_rss_mb(), 1),
        "counts": counts,
        "requested": {
            "person": counts["person"],
            "car": counts["car"],
            "motorcycle": counts["motorcycle"],
            "utility_pole": counts["utility_pole"],
            "tree": counts["tree"],
            "sign_blocking_visibility": blocking,
        },
        "detections": detections,
        "scene_verified": False,
        "note": "Cây lấy từ prompt street tree. Cột điện và biển báo là vùng Cityscapes lớp pole và traffic sign, diện tích tối thiểu 0,05% ảnh. Biển báo che tầm nhìn là biển hoặc biển treo cắt vùng giữa ảnh, không phải nhãn người chấm.",
    }


def main():
    import logging

    import torch
    from transformers import SegformerForSemanticSegmentation, SegformerImageProcessor
    from ultralytics import YOLOWorld

    logging.disable(logging.CRITICAL)
    if not WORLD_WEIGHTS.is_file() or not WORLD_PROMPTS.is_file() or not (SEG_DIR / "model.safetensors").is_file():
        emit({"ready": False, "error": "Thiếu trọng số YOLO-World hoặc SegFormer"})
        return 1
    saved = torch.load(WORLD_PROMPTS, map_location="cpu", weights_only=False)
    if saved.get("prompts") != PROMPTS:
        emit({"ready": False, "error": "Tệp prompt không khớp danh sách đã chốt"})
        return 1
    world = YOLOWorld(str(WORLD_WEIGHTS))
    world.model.txt_feats = saved["txt_feats"]
    world.model.model[-1].nc = len(PROMPTS)
    world.model.names = {index: name for index, name in enumerate(PROMPTS)}
    processor = SegformerImageProcessor.from_pretrained(SEG_DIR)
    segmenter = SegformerForSemanticSegmentation.from_pretrained(SEG_DIR)
    segmenter.eval()
    emit({
        "ready": True,
        "weights_file": WORLD_WEIGHTS.name,
        "weights_sha256": sha256(WORLD_WEIGHTS),
        "segmenter_sha256": sha256(SEG_DIR / "model.safetensors"),
        "class_count": len(PROMPTS),
        "device": "cpu",
        "confidence_threshold": CONFIDENCE,
    })
    for line in sys.stdin:
        image = line.strip()
        if not image:
            continue
        try:
            emit(predict(world, segmenter, processor, Path(image)))
        except Exception as error:
            emit({"error": "Không đếm được ảnh: " + str(error)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
