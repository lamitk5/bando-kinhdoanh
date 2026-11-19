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

    batch = [img_bgr[y0:y1, x0:x1] for (x0, y0, x1, y1, _) in crops]
    results = coco_model.predict(
        source=batch,
        conf=CONFIDENCE,
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
            if key is None:
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
            boxes.append([bx1, by1, bx2, by2])
            scores.append(float(conf))
            clss.append(cid)

    if not boxes:
        return [], total_inf_ms

    bt = torch.tensor(boxes, dtype=torch.float32)
    st = torch.tensor(scores, dtype=torch.float32)
    ct = torch.tensor(clss, dtype=torch.int64)
    # Use IoU 0.54 for motorcycles (dense oblique parked rows) and 0.46 for other classes
    keep = []
    for cid_unique in set(clss):
        idxs = [idx for idx, c in enumerate(clss) if c == cid_unique]
        sub_keep = tv_ops.nms(
            bt[idxs], st[idxs], 0.54 if cid_unique == 3 else 0.46
        ).tolist()
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

    # Separate active two-wheeler riders ("rider") from walking/standing pedestrians ("person")
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
    x1 = max(0, int(round(xs.min() / scale - 0.08 * width)))
    x2 = min(width, int(round(xs.max() / scale + 0.08 * width)))
    y1 = max(0, int(round(ys.min() / scale - 0.15 * height)))
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

    # SegFormer vegetation (class 8) canopy regions excluding low foreground flower beds
    veg_bin = ((label_map == 8) & (probs_np[8] >= SEG_PIXEL_PROB)).astype("uint8")
    vcnt, vlabels, vstats, _ = cv2.connectedComponentsWithStats(veg_bin, connectivity=8)
    seg_trees = []
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
        comp_prob = float(probs_np[8][vlabels == idx].mean())
        if comp_prob < SEG_COMPONENT_MIN_PROB:
            continue
        full_box = [x / scale, y / scale, (x + bw) / scale, (y + bh) / scale]
        covered = any(box_iomin(full_box, tb) >= 0.35 for _, _, tb, _ in world_trees)
        if not covered:
            seg_trees.append(("tree", comp_prob, full_box, "segformer-cityscapes"))

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
        "min_area_fraction": MIN_AREA_FRACTION,
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
            "YOLOv8m đa tỷ lệ khử trùng lặp liên lớp cho ô tô (car, bus, truck) và tách người đi bộ (person) khỏi người điều khiển xe hai bánh (rider). "
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
