#!/usr/bin/env python3
"""Cách học thứ ba cho bề rộng vỉa hè.

Các ứng viên được chốt trước khi xem tập kiểm tra:
- đặc trưng dải và biên trên lưới 128×96, hồi quy ridge
- cùng đặc trưng, trung bình k láng giềng, k trong 5, 11, 21
- cùng đặc trưng, ridge trên log của bề rộng, rồi đổi về mét
- histogram xám và bão hòa, ridge

Hệ số phạt hoặc k được chọn trên tập chọn. Ứng viên có sai số học nhỏ hơn một nửa
sai số chọn thì bị loại vì nhớ mẫu. Người thắng phải có sai số chọn thấp hơn 0,719 m
của mạng đang dùng thì mới thay hệ số. Tập kiểm tra chỉ tính một lần cho người thắng.
"""

from __future__ import annotations

import csv
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2] / "data" / "raw" / "sidewalk-seoul"
OUT_PATH = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "vision" / "sidewalk_width_model.json"
REPORT_PATH = Path(__file__).resolve().parents[2] / "etl" / "vision" / "geometry_selection.json"
GRID_W = 128
GRID_H = 96
ALPHAS = (0.1, 1.0, 10.0, 100.0, 1000.0, 10000.0)
NEIGHBORS = (5, 11, 21)
CURRENT_VAL_MAE = 0.7190039108932567
BANDS = (0.90, 1.50, 3.00)


def split_of(name: str) -> str:
    bucket = int(hashlib.sha256(name.encode()).hexdigest()[:8], 16) % 10
    if bucket <= 1:
        return "test"
    if bucket == 2:
        return "val"
    return "train"


def load_labels() -> list[tuple[str, float]]:
    labels = []
    with (ROOT / "summary_attributes.csv").open(newline="") as handle:
        for row in csv.DictReader(handle):
            name = row.get("filename") or row.get("\ufefffilename")
            labels.append((name, float(row["width"])))
    if len(labels) != 514:
        raise SystemExit(f"CSV phải có 514 dòng nhãn, đang có {len(labels)}")
    return labels


def index_files() -> dict[str, Path]:
    found = {}
    for path in (ROOT / "images").rglob("*"):
        if "__MACOSX" in path.parts or path.name.startswith("."):
            continue
        if path.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
            continue
        found[path.stem] = path
    return found


def block_means(channel: np.ndarray, grid_h: int, grid_w: int) -> np.ndarray:
    height, width = channel.shape
    integral = np.pad(channel.cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    out = np.empty((grid_h, grid_w), dtype=np.float64)
    x0 = (np.arange(grid_w) * width) // grid_w
    x1 = np.maximum(x0 + 1, ((np.arange(grid_w) + 1) * width) // grid_w)
    span_x = x1 - x0
    for y in range(grid_h):
        y0 = y * height // grid_h
        y1 = max(y0 + 1, (y + 1) * height // grid_h)
        row = integral[y1] - integral[y0]
        out[y] = (row[x1] - row[x0]) / ((y1 - y0) * span_x)
    return out


def grids_from_path(path: Path) -> tuple[np.ndarray, np.ndarray]:
    with Image.open(path) as image:
        rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"), dtype=np.float64)
    red = block_means(rgb[:, :, 0], GRID_H, GRID_W)
    green = block_means(rgb[:, :, 1], GRID_H, GRID_W)
    blue = block_means(rgb[:, :, 2], GRID_H, GRID_W)
    gray = (299.0 * red + 587.0 * green + 114.0 * blue) / 1000.0
    peak = np.maximum(np.maximum(red, green), blue)
    saturation = np.divide(peak - np.minimum(np.minimum(red, green), blue), peak, out=np.zeros_like(peak), where=peak > 0)
    return gray, saturation


def structure_features(gray: np.ndarray, saturation: np.ndarray) -> np.ndarray:
    features = []
    for band in range(8):
        rows = slice(band * 12, (band + 1) * 12)
        region = gray[rows]
        color = saturation[rows]
        features.append(region.mean() / 255.0)
        features.append(region.std() / 255.0)
        features.append(region[:, 0:43].mean() / 255.0)
        features.append(region[:, 43:86].mean() / 255.0)
        features.append(region[:, 86:128].mean() / 255.0)
        features.append(float(color.mean()))
    for step in range(16):
        row = gray[3 + step * 6]
        diff = np.abs(np.diff(row))
        total = float(diff.sum()) + 1e-6
        center = float((diff * np.arange(127)).sum()) / total / 126.0
        peak = float(diff.max())
        if peak < 1.0:
            left = 0.0
            span = 0.0
        else:
            index = np.flatnonzero(diff > 0.25 * peak)
            left = float(index[0]) / 126.0
            span = float(index[-1] - index[0]) / 126.0
        features.extend((center, span, left))
    return np.array(features, dtype=np.float64)


def histogram_features(gray: np.ndarray, saturation: np.ndarray) -> np.ndarray:
    def hist(values: np.ndarray, bins: int, limit: float) -> np.ndarray:
        counts, _ = np.histogram(values, bins=bins, range=(0.0, limit))
        return counts / max(1, values.size)

    lower = gray[48:]
    return np.concatenate([
        hist(gray.ravel() / 255.0, 16, 1.0),
        hist(lower.ravel() / 255.0, 16, 1.0),
        hist(saturation.ravel(), 8, 1.0),
    ])


def mae(pred: np.ndarray, target: np.ndarray) -> float:
    return float(np.mean(np.abs(pred - target)))


def fit_ridge(x: np.ndarray, y: np.ndarray, alpha: float) -> np.ndarray:
    gram = x.T @ x
    gram.flat[:: x.shape[1] + 1] += alpha
    return np.linalg.solve(gram, x.T @ y)


def stable(train_mae: float, val_mae: float) -> bool:
    return train_mae >= 0.5 * val_mae


def band_of(width: float) -> str:
    if width < BANDS[0]:
        return "none"
    if width < BANDS[1]:
        return "narrow"
    if width < BANDS[2]:
        return "enough"
    return "wide"


def main() -> int:
    labels = load_labels()
    files = index_files()
    names, targets, structure, histograms = [], [], [], []
    for name, width in labels:
        names.append(name)
        targets.append(width)
        gray, saturation = grids_from_path(files[Path(name).stem])
        structure.append(structure_features(gray, saturation))
        histograms.append(histogram_features(gray, saturation))
    y = np.array(targets, dtype=np.float64)
    groups = np.array([split_of(name) for name in names])
    train = groups == "train"
    val = groups == "val"
    test = groups == "test"
    y_train = y[train]
    y_val = y[val]
    candidates = []

    def add_linear(kind: str, matrix: np.ndarray, space: str) -> None:
        mean = matrix[train].mean(axis=0)
        std = np.where(matrix[train].std(axis=0) < 1e-6, 1.0, matrix[train].std(axis=0))
        scaled = (matrix - mean) / std
        target = np.log(y) if space == "log" else y
        center = float(target[train].mean())
        best = None
        for alpha in ALPHAS:
            weights = fit_ridge(scaled[train], target[train] - center, alpha)
            train_pred = scaled[train] @ weights + center
            val_pred = scaled[val] @ weights + center
            if space == "log":
                train_pred = np.exp(train_pred)
                val_pred = np.exp(val_pred)
            train_mae = mae(train_pred, y_train)
            val_mae = mae(val_pred, y_val)
            if best is None or val_mae < best["val_mae_m"]:
                best = {
                    "kind": kind,
                    "space": space,
                    "family": "ridge",
                    "alpha": alpha,
                    "weights": weights,
                    "mean": mean,
                    "std": std,
                    "center": center,
                    "train_mae_m": train_mae,
                    "val_mae_m": val_mae,
                    "stable": stable(train_mae, val_mae),
                    "scaled": scaled,
                }
        candidates.append(best)

    structure_matrix = np.vstack(structure)
    histogram_matrix = np.vstack(histograms)
    add_linear("band-edge-v3", structure_matrix, "meters")
    add_linear("band-edge-v3", structure_matrix, "log")
    add_linear("histogram-v3", histogram_matrix, "meters")

    mean = structure_matrix[train].mean(axis=0)
    std = np.where(structure_matrix[train].std(axis=0) < 1e-6, 1.0, structure_matrix[train].std(axis=0))
    scaled = (structure_matrix - mean) / std
    for k in NEIGHBORS:
        train_points = scaled[train]
        def predict(block: np.ndarray) -> np.ndarray:
            distance = ((block[:, None, :] - train_points[None, :, :]) ** 2).sum(axis=2)
            nearest = np.argpartition(distance, k - 1, axis=1)[:, :k]
            return y_train[nearest].mean(axis=1)
        train_pred = predict(scaled[train])
        val_pred = predict(scaled[val])
        train_mae = mae(train_pred, y_train)
        val_mae = mae(val_pred, y_val)
        candidates.append({
            "kind": "band-edge-v3",
            "space": "meters",
            "family": "knn",
            "k": k,
            "mean": mean,
            "std": std,
            "train_features": structure_matrix[train],
            "train_targets": y_train,
            "train_mae_m": train_mae,
            "val_mae_m": val_mae,
            "stable": stable(train_mae, val_mae),
            "scaled": scaled,
        })

    usable = [item for item in candidates if item["stable"]]
    pool = usable or candidates
    chosen = min(pool, key=lambda item: item["val_mae_m"])
    report = {
        "current_val_mae_m": CURRENT_VAL_MAE,
        "candidates": [
            {
                "kind": item["kind"],
                "family": item["family"],
                "space": item["space"],
                "alpha": item.get("alpha"),
                "k": item.get("k"),
                "train_mae_m": round(item["train_mae_m"], 4),
                "val_mae_m": round(item["val_mae_m"], 4),
                "stable": item["stable"],
            }
            for item in candidates
        ],
        "replaced": bool(chosen["stable"] and chosen["val_mae_m"] < CURRENT_VAL_MAE),
    }
    if report["replaced"]:
        if chosen["family"] == "ridge":
            pred = chosen["scaled"][test] @ chosen["weights"] + chosen["center"]
            if chosen["space"] == "log":
                pred = np.exp(pred)
        else:
            train_points = chosen["scaled"][train]
            distance = ((chosen["scaled"][test][:, None, :] - train_points[None, :, :]) ** 2).sum(axis=2)
            nearest = np.argpartition(distance, chosen["k"] - 1, axis=1)[:, : chosen["k"]]
            pred = chosen["train_targets"][nearest].mean(axis=1)
        test_mae = mae(pred, y[test])
        baseline = mae(np.full(test.sum(), float(y_train.mean())), y[test])
        true_bands = [band_of(value) for value in y[test]]
        pred_bands = [band_of(float(value)) for value in pred]
        hits = sum(left == right for left, right in zip(true_bands, pred_bands, strict=True))
        payload = {
            "format": 3,
            "dataset": "Seoul Sidewalk Accessibility Image Dataset",
            "doi": "10.5281/zenodo.22908353",
            "license": "CC0-1.0",
            "target": "width_m field-measured",
            "feature_kind": chosen["kind"],
            "resize": [GRID_W, GRID_H],
            "selected": chosen["family"] if chosen["space"] == "meters" else "ridge_log",
            "train_count": int(train.sum()),
            "val_count": int(val.sum()),
            "test_count": int(test.sum()),
            "target_mean_m": float(y_train.mean()) if chosen["space"] == "meters" else chosen["center"],
            "feature_mean": chosen["mean"].tolist(),
            "feature_std": chosen["std"].tolist(),
            "band_cuts_m": list(BANDS),
            "band_rule": "Quy ước mét: dưới 0,90 none, dưới 1,50 narrow, dưới 3,00 enough, còn lại wide. Không phải nhãn người chấm.",
            "test_band_accuracy": hits / int(test.sum()),
            "train_mae_m": chosen["train_mae_m"],
            "val_mae_m": chosen["val_mae_m"],
            "test_mae_m": test_mae,
            "baseline_test_mae_m": baseline,
            "beats_baseline": bool(test_mae < baseline),
            "qwen_weights_updated": False,
            "visibility_trained": False,
            "domain": "Ảnh vỉa hè Seoul, không phải mặt tiền bốn quận Hà Nội.",
            "previous_test_mae_m": 0.8962524457859952,
        }
        if chosen["family"] == "ridge":
            payload["ridge"] = {"alpha": chosen["alpha"], "weights": chosen["weights"].tolist()}
            payload["mlp"] = {"w1": [[0.0]], "b1": [0.0], "w2": [0.0], "b2": 0.0}
        else:
            payload["knn"] = {
                "k": chosen["k"],
                "train_features": chosen["train_features"].tolist(),
                "train_targets_m": chosen["train_targets"].tolist(),
            }
            payload["ridge"] = {"alpha": None, "weights": []}
            payload["mlp"] = {"w1": [[0.0]], "b1": [0.0], "w2": [0.0], "b2": 0.0}
        OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False))
        report["selected"] = payload["selected"]
        report["test_mae_m"] = test_mae
        report["baseline_test_mae_m"] = baseline
        report["test_band_accuracy"] = payload["test_band_accuracy"]
        report["train_mae_m"] = chosen["train_mae_m"]
        report["val_mae_m"] = chosen["val_mae_m"]
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
