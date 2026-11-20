#!/usr/bin/env python3
"""Đếm vật thể, kiểm định cảnh mặt tiền/vỉa hè và kiểm tra biển báo đứng trước mặt tiền trên máy này. Không gửi ảnh đi."""

from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import os
import sys
from pathlib import Path

os.environ["YOLO_VERBOSE"] = "False"

ROOT = Path(__file__).resolve().parent
COCO_WEIGHTS = ROOT / "weights" / "yolov8m.pt"
WORLD_WEIGHTS = ROOT / "weights" / "yolov8s-worldv2.pt"
WORLD_PROMPTS = ROOT / "weights" / "yolov8s-worldv2-prompts.pt"
SEG_DIR = ROOT / "weights" / "segformer-cityscapes"
DEPTH_DIR = ROOT / "weights" / "depth-anything-v2-small"

CONFIDENCE = 0.25
# Xe máy trên ảnh phố dày chỉ hiện ở ngưỡng thấp hơn khi cắt nửa khung.
MOTO_CONFIDENCE = 0.15
# Ô tô, buýt và tải trên cả khung hình thường thấp hơn 0,25 khi xe ở xa hoặc bị cắt mép.
FOUR_WHEEL_FULL_FRAME_CONFIDENCE = 0.18
# Xe buýt cuối đường nhỏ hơn ô tô gần máy ảnh. Ô giữa khung chỉ nhận lớp bus.
DISTANT_BUS_CONFIDENCE = 0.45
MOTO_CLUSTER_RADIUS = 0.16
MOTO_LOOSE_CHILD_RATIO = 0.35
SEG_PIXEL_PROB = 0.50
SEG_POLE_PIXEL_PROB = 0.35
SEG_COMPONENT_MIN_PROB = 0.55
SEG_POLE_MIN_PROB = 0.45
MIN_SEG_SCENE_CONFIDENCE = 0.65
IMAGE_SIZE = 640
SEG_MAX_SIDE = 1024
MIN_AREA_FRACTION = 0.0005
MIN_TREE_AREA_FRACTION = 0.004
MIN_SIGN_OCCLUSION_AREA = 0.0015
MIN_SIGN_BUILDING_RATIO = 0.30
MIN_SIGN_DEPTH_LEAD = 0.12
MIN_FACADE_BUILDING_RATIO = 0.12
MIN_SIDEWALK_RATIO = 0.03
MIN_LOWER_SIDEWALK_RATIO = 0.05
MIN_LOWER_PAVEMENT_RATIO = 0.20
MAX_LOWER_OCCLUSION_RATIO = 0.50
MAX_VIADUCT_CEILING_DROP = 0.25

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

COCO_CLASSES = {
    0: "person",
    2: "car",
    3: "motorcycle",
    5: "bus",
    7: "truck",
    9: "traffic_light",
    11: "stop_sign",
}

SIGN_CLASSES = {"stop_sign", "traffic_sign", "hanging_sign"}


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
    return 0.0


def fmt_pct(ratio):
    val = (Decimal(str(round(ratio, 4))) * Decimal("100")).quantize(
        Decimal("0.1"), rounding=ROUND_HALF_UP
    )
    return str(val).replace(".", ",") + "%"


def box_iou(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0.0:
        return 0.0
    area_a = max(1.0, (a[2] - a[0]) * (a[3] - a[1]))
    area_b = max(1.0, (b[2] - b[0]) * (b[3] - b[1]))
    return inter / max(1.0, area_a + area_b - inter)


def box_iomin(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0.0:
        return 0.0
    area_a = max(1.0, (a[2] - a[0]) * (a[3] - a[1]))
    area_b = max(1.0, (b[2] - b[0]) * (b[3] - b[1]))
    return inter / min(area_a, area_b)


def deduplicate_world_trees(raw_trees):
    if len(raw_trees) <= 1:
        return raw_trees
    # First suppress giant merged boxes enclosing >=2 smaller trees, then suppress nested/overlapping duplicates
    filtered = []
    for i, (ki, ci, bi, si) in enumerate(raw_trees):
        ai = max(1.0, (bi[2] - bi[0]) * (bi[3] - bi[1]))
        smaller_inside = 0
        for j, (_, _, bj, _) in enumerate(raw_trees):
            if i == j:
                continue
            aj = max(1.0, (bj[2] - bj[0]) * (bj[3] - bj[1]))
            if aj < ai * 0.55 and box_iomin(bi, bj) >= 0.70:
                smaller_inside += 1
        if smaller_inside >= 2:
            continue
        filtered.append((ki, ci, bi, si))

    # Prefer higher confidence; when one box is nested inside another (IoMin >= 0.55), keep only one
    ordered = sorted(filtered, key=lambda item: item[1], reverse=True)
    kept = []
    for item in ordered:
        if any(box_iou(item[2], prev[2]) >= 0.45 or box_iomin(item[2], prev[2]) >= 0.55 for prev in kept):
            continue
        kept.append(item)
    return kept


def cluster_motorcycle_centers(items):
    """Gộp các hộp cùng một xe theo tâm. Xe đỗ cạnh nhau có tâm cách xa hơn bán kính này."""
    items = sorted(items, key=lambda item: item[0], reverse=True)
    clustered = []
    for conf, box in items:
        cx = 0.5 * (box[0] + box[2])
        cy = 0.5 * (box[1] + box[3])
        bw = max(1.0, box[2] - box[0])
        bh = max(1.0, box[3] - box[1])
        duplicate = False
        for _, kept in clustered:
            kw = max(1.0, kept[2] - kept[0])
            kh = max(1.0, kept[3] - kept[1])
            if (
                abs(cx - 0.5 * (kept[0] + kept[2])) < MOTO_CLUSTER_RADIUS * min(bw, kw)
                and abs(cy - 0.5 * (kept[1] + kept[3])) < MOTO_CLUSTER_RADIUS * min(bh, kh)
            ):
                duplicate = True
                break
        if not duplicate:
            clustered.append((conf, box))
    return clustered


def drop_loose_motorcycle_parents(items):
    """Bỏ hộp cha khi tâm một hộp nhỏ hơn nhiều nằm bên trong. Giữ xe con và mảnh vỡ của cùng một xe không bị đếm hai lần theo hai hướng."""
    kept = list(items)
    changed = True
    while changed:
        changed = False
        drop = set()
        for i, (_, parent) in enumerate(kept):
            parent_area = max(1.0, (parent[2] - parent[0]) * (parent[3] - parent[1]))
            for j, (_, child) in enumerate(kept):
                if i == j or j in drop:
                    continue
                child_area = (child[2] - child[0]) * (child[3] - child[1])
                if child_area >= MOTO_LOOSE_CHILD_RATIO * parent_area:
                    continue
                cx = 0.5 * (child[0] + child[2])
                cy = 0.5 * (child[1] + child[3])
                if parent[0] <= cx <= parent[2] and parent[1] <= cy <= parent[3]:
                    drop.add(i)
                    changed = True
                    break
        if drop:
            kept = [item for index, item in enumerate(kept) if index not in drop]
    return kept


def motorcycle_boxes(coco_model, img_bgr):
    """Đếm xe máy bằng cả khung hình và lưới nửa khung, rồi gộp tâm."""
    height, width = img_bgr.shape[:2]
    tile_w, tile_h = max(1, width // 2), max(1, height // 2)
    windows = [(0, 0, width, height, True)]
    if max(width, height) >= 900:
        for y0 in (0, height // 4, max(0, height - tile_h)):
            for x0 in (0, width // 4, max(0, width - tile_w)):
                windows.append((x0, y0, min(width, x0 + tile_w), min(height, y0 + tile_h), False))
    unique = []
    seen = set()
    for window in windows:
        if window in seen:
            continue
        seen.add(window)
        unique.append(window)
    crops = [img_bgr[y0:y1, x0:x1] for x0, y0, x1, y1, _ in unique]
    results = coco_model.predict(
        source=crops,
        conf=MOTO_CONFIDENCE,
        imgsz=960,
        device="cpu",
        verbose=False,
    )
    proposals = []
    inference_ms = 0.0
    for (x0, y0, x1, y1, is_full), res in zip(unique, results):
        inference_ms += float((res.speed or {}).get("inference", 0.0))
        if res.boxes is None or res.boxes.cls is None:
            continue
        for cid, conf, box in zip(
            res.boxes.cls.tolist(), res.boxes.conf.tolist(), res.boxes.xyxy.tolist()
        ):
            if int(cid) != 3:
                continue
            mapped = [box[0] + x0, box[1] + y0, box[2] + x0, box[3] + y0]
            if not is_full and (
                (x0 > 0 and mapped[0] - x0 < 8)
                or (x1 < width and x1 - mapped[2] < 8)
                or (y0 > 0 and mapped[1] - y0 < 8)
                or (y1 < height and y1 - mapped[3] < 8)
            ):
                continue
            area_frac = ((mapped[2] - mapped[0]) * (mapped[3] - mapped[1])) / max(1.0, float(width * height))
            if area_frac < 0.00008:
                continue
            proposals.append((float(conf), mapped))
    kept = drop_loose_motorcycle_parents(cluster_motorcycle_centers(proposals))
    return [(3, conf, box) for conf, box in kept], inference_ms


def distant_bus_boxes(coco_model, img_bgr, existing):
    """Ô giữa khung, chỉ nhận xe buýt đủ tin cậy. Không thêm ô tô từ ô này."""
    height, width = img_bgr.shape[:2]
    if max(width, height) < 900:
        return [], 0.0
    x0, y0 = int(width * 0.35), int(height * 0.42)
    x1, y1 = int(width * 0.72), int(height * 0.78)
    if x1 - x0 < 64 or y1 - y0 < 64:
        return [], 0.0
    result = coco_model.predict(
        source=img_bgr[y0:y1, x0:x1],
        conf=DISTANT_BUS_CONFIDENCE,
        imgsz=960,
        device="cpu",
        verbose=False,
    )[0]
    inference_ms = float((result.speed or {}).get("inference", 0.0))
    if result.boxes is None or result.boxes.cls is None:
        return [], inference_ms
    found = []
    four_wheel = {2, 5, 7}
    for cid, conf, box in zip(
        result.boxes.cls.tolist(), result.boxes.conf.tolist(), result.boxes.xyxy.tolist()
    ):
        if int(cid) != 5:
            continue
        mapped = [box[0] + x0, box[1] + y0, box[2] + x0, box[3] + y0]
        if any(cj in four_wheel and box_iou(mapped, bj) >= 0.50 for cj, _, bj in existing):
            continue
        found.append((5, float(conf), mapped))
    return found, inference_ms


def vegetation_instance_boxes(comp_mask, seg_w, seg_h):
    """Tách một vùng tán cây liên thông thành từng cây khi có nhiều đỉnh cách xa nhau."""
    import cv2
    import numpy as np

    ys, xs = np.where(comp_mask)
    if xs.size == 0:
        return []
    x1, x2 = int(xs.min()), int(xs.max()) + 1
    y1, y2 = int(ys.min()), int(ys.max()) + 1
    full = (x1, y1, x2, y2)
    bw, bh = x2 - x1, y2 - y1
    if bw < 0.28 * seg_w or bh < 0.08 * seg_h:
        return [full]
    dist = cv2.distanceTransform(comp_mask.astype("uint8"), cv2.DIST_L2, 5)
    # 0,03 bề rộng ảnh: hai tán cách khoảng đó vẫn là hai cây. 0,07 gộp cả dãy cọ vào một đỉnh.
    min_dist = max(9, int(0.03 * seg_w))
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (min_dist * 2 + 1, min_dist * 2 + 1))
    peak_mask = (dist >= cv2.dilate(dist, kernel) - 1e-6) & (dist >= max(3.0, 0.012 * seg_h))
    py, px = np.where(peak_mask)
    if len(px) < 2:
        return [full]
    order = sorted(range(len(px)), key=lambda i: float(dist[py[i], px[i]]), reverse=True)
    peaks = []
    for i in order:
        y, x = int(py[i]), int(px[i])
        if y > y1 + 0.80 * bh:
            continue
        if any(abs(x - px0) < min_dist and abs(y - py0) < min_dist for py0, px0 in peaks):
            continue
        peaks.append((y, x))
        if len(peaks) == 6:
            break
    if len(peaks) < 2:
        return [full]
    coords = np.stack([ys, xs], axis=1).astype(np.float32)
    peak_arr = np.asarray(peaks, dtype=np.float32)
    d2 = (coords[:, None, 0] - peak_arr[None, :, 0]) ** 2 + (coords[:, None, 1] - peak_arr[None, :, 1]) ** 2
    owners = d2.argmin(axis=1)
    boxes = []
    min_pixels = 0.0035 * seg_w * seg_h
    for i in range(len(peaks)):
        sel = coords[owners == i]
        if sel.shape[0] < min_pixels:
            continue
        yy, xx = sel[:, 0], sel[:, 1]
        boxes.append((int(xx.min()), int(yy.min()), int(xx.max()) + 1, int(yy.max()) + 1))
    return boxes or [full]


def detect_coco_tiled(coco_model, img_bgr, label_map, scale):
    import torch
    import torchvision.ops as tv_ops

    height, width = img_bgr.shape[:2]
    seg_h, seg_w = label_map.shape
    high_res = max(width, height) >= 1800
    tile_imgsz = 960 if high_res else IMAGE_SIZE
    crops = [(0, 0, width, height, True)]
    if high_res:
        crops.extend([
            (0, int(height * 0.18), int(width * 0.78), int(height * 0.56), False),
            (0, int(height * 0.38), int(width * 0.88), int(height * 0.78), False),
            (0, int(height * 0.60), width, height, False),
            (int(width * 0.22), int(height * 0.16), width, int(height * 0.62), False),
        ])
    elif max(width, height) >= 900:
        tw, th = int(width * 0.62), int(height * 0.62)
        for y0 in (0, height - th):
            for x0 in (0, width - tw):
                crops.append((x0, y0, x0 + tw, y0 + th, False))
        crops.append((0, int(height * 0.18), width, int(height * 0.72), False))
    # Ảnh phố Hà Nội rộng: thêm bốn ô giữa và dưới để xe máy nhỏ không bị co về 640.
    if max(width, height) >= 1400:
        tw = int(width * 0.58)
        th = int(height * 0.38)
        y_lo = int(height * 0.28)
        for y0 in (y_lo, max(y_lo, height - th)):
            for x0 in (0, max(0, width - tw)):
                crops.append((x0, y0, min(width, x0 + tw), min(height, y0 + th), False))

    batch = [img_bgr[y0:y1, x0:x1] for (x0, y0, x1, y1, _) in crops]
    results = coco_model.predict(
        source=batch,
        conf=FOUR_WHEEL_FULL_FRAME_CONFIDENCE,
        imgsz=tile_imgsz,
        device="cpu",
        verbose=False,
    )

    total_inf_ms = 0.0
    boxes, scores, clss = [], [], []
    for (x0, y0, x1, y1, is_full), res in zip(crops, results):
        total_inf_ms += float((res.speed or {}).get("inference", 0.0))
        if res.boxes is None or res.boxes.cls is None:
            continue
        for cid, conf, box in zip(
            res.boxes.cls.tolist(), res.boxes.conf.tolist(), res.boxes.xyxy.tolist()
        ):
            cid = int(cid)
            key = COCO_CLASSES.get(cid)
            if key is None or key == "motorcycle":
                continue
            if key in ("car", "bus", "truck"):
                if conf < FOUR_WHEEL_FULL_FRAME_CONFIDENCE:
                    continue
            elif conf < CONFIDENCE:
                continue
            bx1, by1 = box[0] + x0, box[1] + y0
            bx2, by2 = box[2] + x0, box[3] + y0
            if not is_full:
                if (
                    (x0 > 0 and bx1 - x0 < 4)
                    or (x1 < width and x1 - bx2 < 4)
                    or (y0 > 0 and by1 - y0 < 4)
                    or (y1 < height and y1 - by2 < 4)
                ):
                    continue
                if key in ("car", "bus", "truck") and conf < 0.45:
                    sx1 = max(0, int(round(bx1 * scale)))
                    sy1 = max(0, int(round(by1 * scale)))
                    sx2 = min(seg_w, max(sx1 + 1, int(round(bx2 * scale))))
                    sy2 = min(seg_h, max(sy1 + 1, int(round(by2 * scale))))
                    sub = label_map[sy1:sy2, sx1:sx2]
                    veh_ratio = float(((sub == 13) | (sub == 14) | (sub == 15)).mean())
                    if veh_ratio < 0.05:
                        continue
            area_frac = ((bx2 - bx1) * (by2 - by1)) / max(1.0, float(width * height))
            floor = 0.00008 if key == "motorcycle" else 0.0002
            if area_frac < floor:
                continue
            # Hộp ô tô/buýt/tải yếu và rất nhỏ thường là thùng rác hoặc mép xe, không phải một xe.
            if key in ("car", "bus", "truck") and conf < 0.40 and area_frac < 0.0015:
                continue
            # Xe tải dưới 0,35 trên ảnh phố này là thùng hoặc xe đẩy. Xe tải thật trong các ảnh mẫu đều trên 0,70.
            if key == "truck" and conf < 0.35:
                continue
            boxes.append([bx1, by1, bx2, by2])
            scores.append(float(conf))
            clss.append(cid)

    if not boxes:
        motos, moto_ms = motorcycle_boxes(coco_model, img_bgr)
        total_inf_ms += moto_ms
        return [(COCO_CLASSES[ci], si, bi, "yolov8m") for ci, si, bi in motos], total_inf_ms

    bt = torch.tensor(boxes, dtype=torch.float32)
    st = torch.tensor(scores, dtype=torch.float32)
    ct = torch.tensor(clss, dtype=torch.int64)
    keep = []
    for cid_unique in set(clss):
        idxs = [idx for idx, c in enumerate(clss) if c == cid_unique]
        sub_keep = tv_ops.nms(bt[idxs], st[idxs], 0.46).tolist()
        keep.extend(idxs[k] for k in sub_keep)
    kept = [(int(ct[i]), float(st[i]), bt[i].tolist()) for i in keep]

    non_merged = []
    for i, (ci, si, bi) in enumerate(kept):
        ai = max(1.0, (bi[2] - bi[0]) * (bi[3] - bi[1]))
        b_width = bi[2] - bi[0]
        contained = 0
        for j, (cj, _, bj) in enumerate(kept):
            if i == j or ci != cj:
                continue
            aj = max(1.0, (bj[2] - bj[0]) * (bj[3] - bj[1]))
            if aj >= ai * 0.60:
                continue
            if box_iomin(bi, bj) >= 0.70:
                contained += 1
        if contained >= 2 and (ai > 0.04 * width * height or b_width > 0.55 * width):
            continue
        non_merged.append((ci, si, bi))

    # Cross-class deduplication among 4+-wheel motor vehicles (car=2, bus=5, truck=7) and sub-crop fragment suppression
    non_merged.sort(key=lambda item: item[1], reverse=True)
    deduped = []
    four_wheel = {2, 5, 7}
    for ci, si, bi in non_merged:
        if ci in four_wheel:
            if any(
                cj in four_wheel and (box_iou(bi, bj) >= 0.50 or (si < 0.45 and box_iomin(bi, bj) >= 0.80))
                for cj, _, bj in deduped
            ):
                continue
        elif si < 0.38 and any(cj == ci and box_iomin(bi, bj) >= 0.82 for cj, _, bj in deduped):
            continue
        deduped.append((ci, si, bi))

    buses, bus_ms = distant_bus_boxes(coco_model, img_bgr, deduped)
    total_inf_ms += bus_ms
    deduped.extend(buses)

    motos, moto_ms = motorcycle_boxes(coco_model, img_bgr)
    total_inf_ms += moto_ms
    deduped.extend(motos)

    # Separate people sitting on a two-wheeler ("rider", kể cả người ngồi sau) from walking pedestrians.
    moto_boxes = [bi for ci, _, bi in deduped if ci == 3]
    cleaned = []
    for ci, si, bi in deduped:
        cname = COCO_CLASSES[ci]
        if ci == 0:
            sx1 = max(0, int(round(bi[0] * scale)))
            sy1 = max(0, int(round(bi[1] * scale)))
            sx2 = min(seg_w, max(sx1 + 1, int(round(bi[2] * scale))))
            sy2 = min(seg_h, max(sy1 + 1, int(round(bi[3] * scale))))
            sub_lbl = label_map[sy1:sy2, sx1:sx2]
            rider_px = int((sub_lbl == 12).sum())
            ped_px = int((sub_lbl == 11).sum())
            rider_frac = float(rider_px) / max(1, sub_lbl.size)

            pw = max(1.0, bi[2] - bi[0])
            ph = max(1.0, bi[3] - bi[1])
            p_cx = 0.5 * (bi[0] + bi[2])
            p_cy = 0.5 * (bi[1] + bi[3])
            straddles_moto = False
            for mb in moto_boxes:
                mw = max(1.0, mb[2] - mb[0])
                m_cx = 0.5 * (mb[0] + mb[2])
                if (
                    box_iou(bi, mb) >= 0.25
                    and abs(p_cx - m_cx) <= 0.32 * max(pw, mw)
                    and p_cy <= mb[3]
                    and (rider_px > 0 or box_iou(bi, mb) >= 0.35)
                ):
                    straddles_moto = True
                    break
            if straddles_moto or (rider_px > ped_px and rider_frac >= 0.15):
                cname = "rider"
        cleaned.append((cname, si, bi, "yolov8m"))
    return cleaned, total_inf_ms


def evaluate_scene(label_map, max_prob_map, depth_map, depth_range):
    import numpy as np

    seg_h, seg_w = label_map.shape
    total = float(seg_h * seg_w)
    mean_conf = float(max_prob_map.mean())
    conf_mask = max_prob_map >= SEG_PIXEL_PROB

    building_full = float(((label_map == 2) & conf_mask).sum()) / total
    band_y1, band_y2 = int(0.20 * seg_h), int(0.85 * seg_h)
    facade_building = float(
        ((label_map[band_y1:band_y2, :] == 2) & conf_mask[band_y1:band_y2, :]).mean()
    )
    sidewalk = float(((label_map == 1) & conf_mask).sum()) / total
    road = float(((label_map == 0) & conf_mask).sum()) / total
    natural = float(np.isin(label_map, [8, 9, 10]).sum()) / total

    lower_lbl = label_map[seg_h // 2 :, :]
    lower_conf = conf_mask[seg_h // 2 :, :]
    lower_total = float(max(1, lower_lbl.size))
    lower_sidewalk = float(((lower_lbl == 1) & lower_conf).sum()) / lower_total
    lower_pavement = float((((lower_lbl == 0) | (lower_lbl == 1)) & lower_conf).sum()) / lower_total
    lower_occlusion = float(np.isin(lower_lbl, [11, 12, 13, 14, 15, 17, 18]).sum()) / lower_total

    # 3D overhead viaduct / tunnel ceiling check: ceiling is close at y<15% and recedes toward horizon y=35..50%
    top_slice = label_map[: max(1, int(0.15 * seg_h)), :] == 2
    mid_slice = label_map[int(0.35 * seg_h) : int(0.50 * seg_h), :] == 2
    ceiling_drop = 0.0
    if float(top_slice.mean()) > 0.50 and mid_slice.any():
        d_top = float(np.median(depth_map[: max(1, int(0.15 * seg_h)), :][top_slice]))
        d_mid = float(np.median(depth_map[int(0.35 * seg_h) : int(0.50 * seg_h), :][mid_slice]))
        ceiling_drop = (d_top - d_mid) / depth_range

    reasons = []
    if mean_conf < MIN_SEG_SCENE_CONFIDENCE:
        reasons.append(
            f"Độ tin cậy phân đoạn ngữ nghĩa trung bình {fmt_pct(mean_conf)} thấp hơn ngưỡng {MIN_SEG_SCENE_CONFIDENCE * 100:.0f}%"
        )
    if ceiling_drop >= MAX_VIADUCT_CEILING_DROP:
        reasons.append(
            f"Nửa trên ảnh là gầm cầu cạn hoặc trần bê tông trên cao nhô về phía ống kính (độ chênh sâu +{fmt_pct(ceiling_drop)}), không phải mặt tiền tầng trệt"
        )
    if facade_building < MIN_FACADE_BUILDING_RATIO:
        reasons.append(
            f"Tỷ lệ công trình ở dải mặt tiền {fmt_pct(facade_building)} thấp hơn ngưỡng {MIN_FACADE_BUILDING_RATIO * 100:.0f}%"
        )
    if sidewalk < MIN_SIDEWALK_RATIO or lower_sidewalk < MIN_LOWER_SIDEWALK_RATIO:
        reasons.append(
            f"Tỷ lệ vỉa hè nhận diện được ({fmt_pct(sidewalk)} toàn ảnh, {fmt_pct(lower_sidewalk)} nửa dưới) chưa đạt ngưỡng vỉa hè lộ rõ (3% và 5%)"
        )
    if lower_pavement < MIN_LOWER_PAVEMENT_RATIO:
        reasons.append(
            f"Mặt nền lộ rõ ở nửa dưới ảnh {fmt_pct(lower_pavement)} thấp hơn ngưỡng {MIN_LOWER_PAVEMENT_RATIO * 100:.0f}%"
        )
    if lower_occlusion > MAX_LOWER_OCCLUSION_RATIO:
        reasons.append(
            f"Nửa dưới ảnh bị người và xe che khuất {fmt_pct(lower_occlusion)} vượt ngưỡng {MAX_LOWER_OCCLUSION_RATIO * 100:.0f}%"
        )

    verified = len(reasons) == 0
    if verified:
        summary = (
            f"Kiểm định cảnh đạt: công trình dải mặt tiền {fmt_pct(facade_building)}, vỉa hè lộ rõ "
            f"{fmt_pct(sidewalk)} ({fmt_pct(lower_sidewalk)} nửa dưới), đủ điều kiện ước lượng bề rộng vỉa hè."
        )
    else:
        summary = (
            "Không gán bề rộng vỉa hè vì ảnh chưa thỏa điều kiện mặt tiền tầng trệt và vỉa hè lộ rõ: "
            + "; ".join(reasons)
            + "."
        )

    return verified, {
        "verified": verified,
        "mean_seg_confidence": round(mean_conf, 4),
        "building_ratio": round(facade_building, 4),
        "building_full_ratio": round(building_full, 4),
        "ceiling_depth_drop": round(ceiling_drop, 4),
        "sidewalk_ratio": round(sidewalk, 4),
        "lower_sidewalk_ratio": round(lower_sidewalk, 4),
        "road_ratio": round(road, 4),
        "lower_pavement_ratio": round(lower_pavement, 4),
        "lower_occlusion_ratio": round(lower_occlusion, 4),
        "natural_ratio": round(natural, 4),
        "reasons": reasons,
        "summary": summary,
    }


def compute_sidewalk_crop(label_map, max_prob_map, scale, width, height):
    import numpy as np

    side_mask = (label_map == 1) & (max_prob_map >= SEG_PIXEL_PROB)
    ys, xs = np.where(side_mask)
    if xs.size == 0:
        return None
    x1 = max(0, int(round(xs.min() / scale - 0.02 * width)))
    x2 = min(width, int(round(xs.max() / scale + 0.02 * width)))
    y1 = max(0, int(round(ys.min() / scale - 0.04 * height)))
    y2 = min(height, int(round(ys.max() / scale + 0.05 * height)))
    if x2 - x1 < 64 or y2 - y1 < 64:
        return None
    return [x1, y1, x2, y2]


def predict(coco_model, world_model, seg_model, seg_proc, depth_model, depth_proc, shas, image_path):
    import time

    import cv2
    import numpy as np
    import torch
    from PIL import Image, ImageOps

    started = time.perf_counter()
    with Image.open(image_path) as raw_img:
        image = ImageOps.exif_transpose(raw_img).convert("RGB")
    width, height = image.size
    img_bgr = np.ascontiguousarray(np.array(image)[:, :, ::-1])

    scale = min(1.0, float(SEG_MAX_SIDE) / max(width, height))
    seg_w = max(1, int(round(width * scale)))
    seg_h = max(1, int(round(height * scale)))
    img_small = image.resize((seg_w, seg_h), Image.BILINEAR) if scale < 1.0 else image

    with torch.no_grad():
        seg_logits = seg_model(**seg_proc(images=img_small, return_tensors="pt")).logits
        depth_pred = depth_model(**depth_proc(images=img_small, return_tensors="pt")).predicted_depth

    up_logits = torch.nn.functional.interpolate(
        seg_logits, size=(seg_h, seg_w), mode="bilinear", align_corners=False
    )
    probs = torch.softmax(up_logits, dim=1)[0]
    max_prob_t, label_t = probs.max(dim=0)
    label_map = label_t.numpy()
    max_prob_map = max_prob_t.numpy()
    probs_np = probs.numpy()

    depth_map = (
        torch.nn.functional.interpolate(
            depth_pred.unsqueeze(1), size=(seg_h, seg_w), mode="bilinear", align_corners=False
        )[0, 0]
        .numpy()
    )
    p05, p95 = np.percentile(depth_map, [5, 95])
    depth_range = max(1e-6, float(p95 - p05))

    coco_dets, coco_inf_ms = detect_coco_tiled(coco_model, img_bgr, label_map, scale)

    world_res = world_model.predict(
        source=img_bgr,
        conf=CONFIDENCE,
        imgsz=IMAGE_SIZE,
        device="cpu",
        verbose=False,
    )[0]
    world_inf_ms = float((world_res.speed or {}).get("inference", 0.0))

    raw_world_trees = []
    world_signs = []
    if world_res.boxes is not None and world_res.boxes.cls is not None:
        names = world_res.names
        for cid, conf, box in zip(
            world_res.boxes.cls.tolist(),
            world_res.boxes.conf.tolist(),
            world_res.boxes.xyxy.tolist(),
        ):
            pname = names[int(cid)]
            if pname == "street tree":
                sx1 = max(0, int(round(box[0] * scale)))
                sy1 = max(0, int(round(box[1] * scale)))
                sx2 = min(seg_w, max(sx1 + 1, int(round(box[2] * scale))))
                sy2 = min(seg_h, max(sy1 + 1, int(round(box[3] * scale))))
                if float((label_map[sy1:sy2, sx1:sx2] == 8).mean()) >= 0.15:
                    raw_world_trees.append(("tree", float(conf), box, "yolov8s-worldv2"))
            elif pname == "hanging sign":
                world_signs.append(("hanging_sign", float(conf), box, "yolov8s-worldv2"))

    world_trees = deduplicate_world_trees(raw_world_trees)

    # SegFormer traffic_sign (class 7) with pixel probability >= 0.50 and mean component confidence >= 0.55
    sign_bin = ((label_map == 7) & (probs_np[7] >= SEG_PIXEL_PROB)).astype("uint8")
    scnt, slabels, sstats, _ = cv2.connectedComponentsWithStats(sign_bin, connectivity=8)
    seg_signs = []
    seg_sign_boxes_s = []
    for idx in range(1, scnt):
        area_frac = float(sstats[idx, cv2.CC_STAT_AREA]) / (seg_w * seg_h)
        if area_frac < MIN_AREA_FRACTION:
            continue
        comp_prob = float(probs_np[7][slabels == idx].mean())
        if comp_prob < SEG_COMPONENT_MIN_PROB:
            continue
        x = int(sstats[idx, cv2.CC_STAT_LEFT])
        y = int(sstats[idx, cv2.CC_STAT_TOP])
        bw = int(sstats[idx, cv2.CC_STAT_WIDTH])
        bh = int(sstats[idx, cv2.CC_STAT_HEIGHT])
        seg_sign_boxes_s.append((x, y, x + bw, y + bh))
        seg_signs.append((
            "traffic_sign",
            comp_prob,
            [x / scale, y / scale, (x + bw) / scale, (y + bh) / scale],
            "segformer-cityscapes",
        ))

    # SegFormer utility_pole (class 5): vertical morphological bridging across wire breaks, excluding sign mounting posts and human overlaps
    human_boxes_s = [
        [b[0] * scale, b[1] * scale, b[2] * scale, b[3] * scale]
        for k, _, b, _ in coco_dets
        if k in ("person", "rider")
    ]
    pole_raw = (probs_np[5] >= SEG_POLE_PIXEL_PROB).astype("uint8")
    bridge_h = max(3, seg_h // 40)
    pole_bridged = cv2.morphologyEx(
        pole_raw, cv2.MORPH_CLOSE, np.ones((bridge_h, 3), dtype="uint8")
    )
    pcnt, plabels, pstats, _ = cv2.connectedComponentsWithStats(pole_bridged, connectivity=8)
    seg_poles = []
    for idx in range(1, pcnt):
        comp_mask = (plabels == idx) & (pole_raw == 1)
        raw_pixels = int(comp_mask.sum())
        area_frac = float(raw_pixels) / (seg_w * seg_h)
        if area_frac < MIN_AREA_FRACTION:
            continue
        comp_prob = float(probs_np[5][comp_mask].mean())
        if comp_prob < SEG_POLE_MIN_PROB:
            continue
        x = int(pstats[idx, cv2.CC_STAT_LEFT])
        y = int(pstats[idx, cv2.CC_STAT_TOP])
        bw = int(pstats[idx, cv2.CC_STAT_WIDTH])
        bh = int(pstats[idx, cv2.CC_STAT_HEIGHT])
        if bh / max(1, bw) < 2.2 or bh < 0.10 * seg_h:
            continue
        # Exclude any post whose top terminates inside or right below a traffic_sign (mounting post, not overhead utility pole)
        is_sign_post = False
        for sx1, sy1, sx2, sy2 in seg_sign_boxes_s:
            x_overlap = min(x + bw, sx2) - max(x, sx1)
            if x_overlap > 0.30 * bw and y >= sy1 - 0.02 * seg_h and y <= sy2 + 0.05 * seg_h:
                is_sign_post = True
                break
        if is_sign_post:
            continue
        pbox_s = [float(x), float(y), float(x + bw), float(y + bh)]
        if any(box_iomin(pbox_s, hb) >= 0.35 for hb in human_boxes_s):
            continue
        seg_poles.append((
            "utility_pole",
            comp_prob,
            [x / scale, y / scale, (x + bw) / scale, (y + bh) / scale],
            "segformer-cityscapes",
        ))

    # SegFormer vegetation (class 8). Tán liền nhau được tách theo đỉnh khoảng cách.
    veg_bin = ((label_map == 8) & (probs_np[8] >= SEG_PIXEL_PROB)).astype("uint8")
    vcnt, vlabels, vstats, _ = cv2.connectedComponentsWithStats(veg_bin, connectivity=8)
    seg_tree_boxes = []
    for idx in range(1, vcnt):
        area_frac = float(vstats[idx, cv2.CC_STAT_AREA]) / (seg_w * seg_h)
        x = int(vstats[idx, cv2.CC_STAT_LEFT])
        y = int(vstats[idx, cv2.CC_STAT_TOP])
        bw = int(vstats[idx, cv2.CC_STAT_WIDTH])
        bh = int(vstats[idx, cv2.CC_STAT_HEIGHT])
        if area_frac < MIN_TREE_AREA_FRACTION or bh < 0.08 * seg_h:
            continue
        if y >= 0.58 * seg_h and bh / max(1, bw) < 0.45:
            continue
        comp_mask = vlabels == idx
        comp_prob = float(probs_np[8][comp_mask].mean())
        if comp_prob < SEG_COMPONENT_MIN_PROB:
            continue
        for ix1, iy1, ix2, iy2 in vegetation_instance_boxes(comp_mask, seg_w, seg_h):
            inst = comp_mask[iy1:iy2, ix1:ix2]
            if inst.size == 0 or not inst.any():
                continue
            inst_prob = float(probs_np[8][iy1:iy2, ix1:ix2][inst].mean())
            if inst_prob < SEG_COMPONENT_MIN_PROB:
                continue
            full_box = [ix1 / scale, iy1 / scale, ix2 / scale, iy2 / scale]
            seg_tree_boxes.append((inst_prob, full_box))

    # Hộp street-tree nuốt từ hai tán đã tách thì bỏ, để không đếm một cây cho cả dãy.
    filtered_world = []
    for item in world_trees:
        inside = sum(1 for _, sbox in seg_tree_boxes if box_iomin(sbox, item[2]) >= 0.45)
        if inside >= 2:
            continue
        filtered_world.append(item)
    world_trees = filtered_world
    seg_trees = []
    for inst_prob, full_box in seg_tree_boxes:
        if any(box_iomin(full_box, tb) >= 0.45 for _, _, tb, _ in world_trees):
            continue
        seg_trees.append(("tree", inst_prob, full_box, "segformer-cityscapes"))

    def measure_sign_occlusion(cls_name, box_px):
        area_frac = ((box_px[2] - box_px[0]) * (box_px[3] - box_px[1])) / max(
            1.0, float(width * height)
        )
        sx1 = max(0, int(round(box_px[0] * scale)))
        sy1 = max(0, int(round(box_px[1] * scale)))
        sx2 = min(seg_w, max(sx1 + 1, int(round(box_px[2] * scale))))
        sy2 = min(seg_h, max(sy1 + 1, int(round(box_px[3] * scale))))
        bw, bh = sx2 - sx1, sy2 - sy1
        pad_x = max(4, int(round(0.45 * bw)))
        pad_y = max(4, int(round(0.45 * bh)))
        rx1, ry1 = max(0, sx1 - pad_x), max(0, sy1 - pad_y)
        rx2, ry2 = min(seg_w, sx2 + pad_x), min(seg_h, sy2 + pad_y)

        ring_lbl = label_map[ry1:ry2, rx1:rx2]
        ring_prob = probs_np[2, ry1:ry2, rx1:rx2]
        ring_dep = depth_map[ry1:ry2, rx1:rx2]
        sub_sign = np.zeros_like(ring_lbl, dtype=bool)
        if cls_name == "traffic_sign":
            mask = label_map[sy1:sy2, sx1:sx2] == 7
            sub_sign[sy1 - ry1 : sy2 - ry1, sx1 - rx1 : sx2 - rx1] = mask if mask.any() else True
        else:
            sub_sign[sy1 - ry1 : sy2 - ry1, sx1 - rx1 : sx2 - rx1] = True

        bldg_mask = (ring_lbl == 2) & (ring_prob >= SEG_PIXEL_PROB) & (~sub_sign)
        bldg_ratio = float(bldg_mask.sum()) / max(1, int((~sub_sign).sum()))

        sign_d = float(np.median(ring_dep[sub_sign])) if sub_sign.any() else 0.0
        bldg_d = float(np.median(ring_dep[bldg_mask])) if bldg_mask.any() else 0.0
        lead = (sign_d - bldg_d) / depth_range if bldg_mask.any() else 0.0
        blocks = (
            area_frac >= MIN_SIGN_OCCLUSION_AREA
            and bldg_ratio >= MIN_SIGN_BUILDING_RATIO
            and lead >= MIN_SIGN_DEPTH_LEAD
        )
        return blocks, round(area_frac, 5), round(bldg_ratio, 4), round(lead, 4)

    counts = {
        "person": 0,
        "rider": 0,
        "car": 0,
        "motorcycle": 0,
        "bus": 0,
        "truck": 0,
        "traffic_light": 0,
        "stop_sign": 0,
        "tree": 0,
        "hanging_sign": 0,
        "utility_pole": 0,
        "traffic_sign": 0,
    }

    raw_items = coco_dets + world_trees + seg_trees + seg_poles + seg_signs + world_signs
    detections = []
    blocking_boxes = []
    occluding_poles = 0
    occluding_trees = 0
    for key, conf, box, source in raw_items:
        counts[key] += 1
        entry = {
            "class": key,
            "confidence": round(float(conf), 3),
            "box_px": [round(float(v), 1) for v in box],
            "source": source,
            "blocks_facade": False,
        }
        if key in SIGN_CLASSES or key in ("utility_pole", "tree"):
            blocks, area_frac, bldg_ratio, lead = measure_sign_occlusion(key, box)
            entry["area_fraction"] = area_frac
            entry["local_building_ratio"] = bldg_ratio
            entry["depth_lead"] = lead
            if key in SIGN_CLASSES:
                if blocks and not any(box_iou(box, prev) >= 0.40 for prev in blocking_boxes):
                    entry["blocks_facade"] = True
                    blocking_boxes.append(box)
            elif blocks:
                entry["occluding_facade"] = True
                if key == "utility_pole":
                    occluding_poles += 1
                else:
                    occluding_trees += 1
        detections.append(entry)

    blocking_count = sum(1 for item in detections if item["blocks_facade"])
    scene_verified, scene_info = evaluate_scene(label_map, max_prob_map, depth_map, depth_range)
    scene_info["occluding_poles"] = occluding_poles
    scene_info["occluding_trees"] = occluding_trees
    sidewalk_crop = compute_sidewalk_crop(label_map, max_prob_map, scale, width, height) if scene_verified else None
    scene_info["sidewalk_crop_px"] = sidewalk_crop
    wall_ms = (time.perf_counter() - started) * 1000.0
    inference_ms = coco_inf_ms + world_inf_ms
    if inference_ms <= 0.0:
        inference_ms = wall_ms

    car_merged = counts["car"] + counts["bus"] + counts["truck"]
    return {
        "weights_file": COCO_WEIGHTS.name,
        "coco_sha256": shas["coco"],
        "world_file": WORLD_WEIGHTS.name,
        "weights_sha256": shas["world"],
        "segmenter_file": "segformer-cityscapes/model.safetensors",
        "segmenter_sha256": shas["segmenter"],
        "depth_file": "depth-anything-v2-small/model.safetensors",
        "depth_sha256": shas["depth"],
        "prompts": PROMPTS,
        "dataset": "COCO YOLOv8m đa tỷ lệ + YOLO-World + Cityscapes SegFormer + Depth-Anything-V2",
        "confidence_threshold": CONFIDENCE,
        "motorcycle_confidence_threshold": MOTO_CONFIDENCE,
        "four_wheel_confidence_threshold": FOUR_WHEEL_FULL_FRAME_CONFIDENCE,
        "min_area_fraction": MIN_AREA_FRACTION,
        "detection_min_area_fraction": {"motorcycle": 0.00008, "other_coco": 0.0002},
        "car_classes_merged": ["car", "bus", "truck"],
        "occlusion_rule": (
            "Biển báo (stop_sign, traffic_sign, hanging_sign) diện tích >= 0,15% ảnh, "
            "tỷ lệ công trình lớp building trên vành lân cận >= 30% (SegFormer) "
            "và đứng trước mặt công trình theo độ sâu tương đối depth_lead >= 0,12 (Depth-Anything-V2)."
        ),
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
            "car": car_merged,
            "motorcycle": counts["motorcycle"],
            "utility_pole": counts["utility_pole"],
            "tree": counts["tree"],
            "sign_blocking_visibility": blocking_count,
        },
        "detections": detections,
        "scene_verified": scene_verified,
        "scene": scene_info,
        "note": (
            "YOLOv8m đếm xe máy trên cả khung và lưới nửa khung ở ngưỡng 0,15, gộp theo tâm rồi bỏ hộp cha quá lỏng. "
            "Ô tô, buýt và tải trên cả khung hình lấy từ ngưỡng 0,18. "
            "Xe buýt ở cuối đường được tìm thêm bằng một ô giữa khung, chỉ nhận lớp bus từ ngưỡng 0,45. "
            "Ngưỡng xe máy 0,15, ô tô 0,18, ô buýt giữa khung và khoảng cách tách tán được chọn khi đối chiếu năm ảnh Hà Nội trong thư mục fixtures, chưa có tập kiểm tra tách riêng. "
            "Tách người đi bộ (person) khỏi người ngồi trên xe hai bánh (rider, kể cả người ngồi sau). "
            "Cây khử trùng lặp giữa street tree (YOLO-World) và vùng tán cây lớp vegetation (SegFormer). "
            "Cột điện loại trừ trụ đỡ biển báo và vùng chồng lấn người. "
            "Biển báo, cột điện và cây được kiểm tra đồng thời nền công trình phía sau (>= 30%) và độ sâu đứng trước mặt tiền (depth_lead >= 0,12) bằng Depth-Anything-V2."
        ),
    }


def main():
    import logging
    import warnings

    import torch
    from transformers import (
        AutoImageProcessor,
        AutoModelForDepthEstimation,
        SegformerForSemanticSegmentation,
        SegformerImageProcessor,
    )
    from ultralytics import YOLO, YOLOWorld

    warnings.filterwarnings("ignore")
    logging.disable(logging.CRITICAL)

    required_files = [
        COCO_WEIGHTS,
        WORLD_WEIGHTS,
        WORLD_PROMPTS,
        SEG_DIR / "model.safetensors",
        DEPTH_DIR / "model.safetensors",
    ]
    if not all(path.is_file() for path in required_files):
        emit({"ready": False, "error": "Thiếu trọng số YOLOv8m, YOLO-World, SegFormer hoặc Depth-Anything-V2"})
        return 1

    saved = torch.load(WORLD_PROMPTS, map_location="cpu", weights_only=False)
    if saved.get("prompts") != PROMPTS:
        emit({"ready": False, "error": "Tệp prompt không khớp danh sách đã chốt"})
        return 1

    shas = {
        "coco": sha256(COCO_WEIGHTS),
        "world": sha256(WORLD_WEIGHTS),
        "segmenter": sha256(SEG_DIR / "model.safetensors"),
        "depth": sha256(DEPTH_DIR / "model.safetensors"),
    }

    coco_model = YOLO(str(COCO_WEIGHTS))
    world_model = YOLOWorld(str(WORLD_WEIGHTS))
    world_model.model.txt_feats = saved["txt_feats"]
    world_model.model.model[-1].nc = len(PROMPTS)
    world_model.model.names = {index: name for index, name in enumerate(PROMPTS)}

    seg_proc = SegformerImageProcessor.from_pretrained(SEG_DIR)
    seg_model = SegformerForSemanticSegmentation.from_pretrained(SEG_DIR).eval()

    depth_proc = AutoImageProcessor.from_pretrained(DEPTH_DIR)
    depth_model = AutoModelForDepthEstimation.from_pretrained(DEPTH_DIR, dtype=torch.float32).eval()

    emit({
        "ready": True,
        "weights_file": COCO_WEIGHTS.name,
        "coco_sha256": shas["coco"],
        "world_file": WORLD_WEIGHTS.name,
        "weights_sha256": shas["world"],
        "segmenter_sha256": shas["segmenter"],
        "depth_sha256": shas["depth"],
        "class_count": len(PROMPTS),
        "device": "cpu",
        "confidence_threshold": CONFIDENCE,
    })

    for line in sys.stdin:
        image = line.strip()
        if not image:
            continue
        try:
            emit(
                predict(
                    coco_model,
                    world_model,
                    seg_model,
                    seg_proc,
                    depth_model,
                    depth_proc,
                    shas,
                    Path(image),
                )
            )
        except Exception as exc:  # noqa: BLE001
            emit({"error": str(exc)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
